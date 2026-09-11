package com.gitlab.sync.confluence.mail;

import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.mail.Email;
import com.atlassian.mail.server.MailServerManager;
import com.atlassian.mail.server.SMTPMailServer;
import com.atlassian.user.User;
import com.gitlab.sync.confluence.GitsyncServices;
import com.gitlab.sync.confluence.links.GitLabLinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email notifications for line comments (R10): when a comment is created or
 * deleted, the page creator and every user @-mentioned in the comment body
 * receive a plain-text email through Confluence's default SMTP server.
 *
 * <p>Delivery errors must never break comment creation/deletion: every step
 * is guarded, and the worst outcome is a LOG.warn and skipped mail.</p>
 */
public final class CommentNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(CommentNotifier.class);

    /** @-mentions in the comment text, e.g. {@code @ivan.petrov}. */
    private static final Pattern MENTIONS = Pattern.compile("@([A-Za-z0-9._-]+)");

    /** The comment body is truncated to this many characters in the mail. */
    private static final int MAX_BODY_CHARS = 300;

    private CommentNotifier() {
    }

    /** Notify the page creator and @-mentioned users that a comment was added. */
    public static void notifyCommentCreated(String pageId, String actorName, String commentBody) {
        doNotify(pageId, actorName, "новый комментарий", commentBody);
    }

    /** Notify the page creator and @-mentioned users that a comment was removed. */
    public static void notifyCommentDeleted(String pageId, String actorName, String commentBody) {
        doNotify(pageId, actorName, "комментарий удалён", commentBody);
    }

    private static void doNotify(String pageId, String actorName, String eventLabel, String commentBody) {
        try {
            PageManager pm = GitsyncServices.pageManager();
            if (pm == null) {
                LOG.warn("PageManager not available — mail notification for page {} skipped", pageId);
                MailDiagnostics.record("page=" + pageId + " skipped: PageManager unavailable");
                return;
            }
            Page page;
            try {
                page = pm.getPage(Long.parseLong(pageId));
            } catch (NumberFormatException e) {
                LOG.warn("Invalid pageId {} — mail notification skipped", pageId);
                return;
            }
            if (page == null) {
                LOG.debug("Page {} not found — mail notification skipped", pageId);
                return;
            }

            LinkedHashSet<String> emails = new LinkedHashSet<>();

            // Recipient 1: the page creator (unless the actor is the creator).
            String creatorName = page.getCreatorName();
            if (creatorName != null && !creatorName.isBlank()
                    && !creatorName.equalsIgnoreCase(actorName)) {
                String creatorEmail = lookupEmail(creatorName);
                if (creatorEmail != null) {
                    addEmail(emails, creatorEmail);
                }
            }

            // Recipients 2..n: unique @-mentions in the comment body.
            if (commentBody != null) {
                Matcher m = MENTIONS.matcher(commentBody);
                LinkedHashSet<String> names = new LinkedHashSet<>();
                while (m.find()) {
                    names.add(m.group(1));
                }
                for (String name : names) {
                    if (name.equalsIgnoreCase(actorName)) {
                        continue;
                    }
                    String email = lookupEmail(name);
                    if (email != null) {
                        addEmail(emails, email);
                    } else {
                        LOG.debug("Mentioned user {} not found in Confluence — skipping", name);
                    }
                }
            }

            if (emails.isEmpty()) {
                LOG.debug("no mail recipients for page {}", pageId);
                MailDiagnostics.record("page=" + pageId + " skipped: no recipients "
                        + "(creator=" + creatorName + ", actor=" + actorName + ")");
                return;
            }
            MailDiagnostics.record("page=" + pageId + " creator=" + creatorName
                    + " actor=" + actorName + " recipients=" + emails);

            MailServerManager mgr = GitsyncServices.mailServerManager();
            if (mgr == null) {
                LOG.warn("MailServerManager not available — mail notifications skipped");
                MailDiagnostics.record("page=" + pageId + " skipped: MailServerManager unavailable");
                return;
            }
            // The admin UI does not make a configured server the "default"
            // one automatically, so fall back to the first registered
            // SMTP server when no default is defined.
            SMTPMailServer server = mgr.getDefaultSMTPMailServer();
            String serverSource = "default";
            if (server == null) {
                try {
                    java.util.List<SMTPMailServer> servers = mgr.getSmtpMailServers();
                    if (servers != null && !servers.isEmpty()) {
                        server = servers.get(0);
                        serverSource = "first-of-" + servers.size();
                    }
                } catch (Throwable t) {
                    MailDiagnostics.record("page=" + pageId + " getSmtpMailServers failed: " + t);
                }
            }
            if (server == null) {
                LOG.warn("No SMTP server configured in Confluence — mail notifications skipped");
                MailDiagnostics.record("page=" + pageId + " skipped: no SMTP server (default or list)");
                return;
            }

            String from = server.getDefaultFrom();
            if (from == null || from.isBlank()) {
                from = System.getProperty("gitsync.mail.from", "gitsync@localhost");
            }

            String title = page.getTitle() == null ? "" : page.getTitle();
            String subject = "Git-Sync: " + eventLabel + " — «" + title + "»";
            String pageUrl = GitLabLinkResolver.getConfluenceBaseUrl()
                    + "/pages/viewpage.action?pageId=" + pageId;
            String body = buildBody(title, capitalize(eventLabel), actorName, commentBody, pageUrl);

            for (String addr : emails) {
                try {
                    Email email = new Email(addr);
                    email.setFrom(from).setSubject(subject).setBody(body)
                            .setMimeType("text/plain");
                    sendWithAppClassLoader(server, email);
                    MailDiagnostics.record("page=" + pageId + " sent to " + addr
                            + " (from=" + from + ", server=" + serverSource + ")");
                } catch (Throwable t) {
                    LOG.warn("Failed to send comment notification to {}: {}", addr, t.toString());
                    MailDiagnostics.record("page=" + pageId + " send to " + addr + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Comment mail notification for page {} failed: {}", pageId, t.toString());
            MailDiagnostics.record("page=" + pageId + " notification failed: " + t);
        }
    }

    /**
     * Send with the thread-context classloader temporarily pointed at the
     * classloader that loaded the atlassian-mail classes.
     *
     * <p>jakarta.mail discovers its SMTP/IMAP providers via
     * {@code ServiceLoader.load(Provider.class)} — the single-argument
     * form, i.e. the <em>thread-context</em> classloader. In Confluence's
     * OSGi container a servlet thread's TCCL does not see the jakarta.mail
     * jars in {@code WEB-INF/lib}, so the send dies with
     * {@code ServiceConfigurationError: javax.mail.Provider ... not found}
     * until the TCCL is swapped for the duration of the send.</p>
     */
    private static void sendWithAppClassLoader(SMTPMailServer server, Email email)
            throws Throwable {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        boolean switched = false;
        try {
            try {
                thread.setContextClassLoader(
                        MailServerManager.class.getClassLoader());
                switched = true;
            } catch (Throwable ignored) {
                // security manager refused — try the send as-is
            }
            server.send(email);
        } finally {
            if (switched) {
                try {
                    thread.setContextClassLoader(original);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Resolve a Confluence username to a non-blank email address, or
     * {@code null} when the user does not exist or has no email.
     */
    private static String lookupEmail(String username) {
        UserAccessor ua = GitsyncServices.userAccessor();
        if (ua == null) {
            return null;
        }
        User u = ua.getUser(username);
        if (u == null) {
            return null;
        }
        String email = u.getEmail();
        return (email != null && !email.isBlank()) ? email : null;
    }

    /** Add an email address unless it is already present (case-insensitive). */
    private static void addEmail(LinkedHashSet<String> emails, String email) {
        for (String existing : emails) {
            if (existing.equalsIgnoreCase(email)) {
                return;
            }
        }
        emails.add(email);
    }

    /**
     * Plain-text mail body, exact contract:
     *
     * <pre>
     * Страница: {title}
     * Событие: {EventLabel}
     * Автор: {actorName}
     *
     * {truncated}
     *
     * {pageUrl}
     *
     * — Git-Sync plugin
     * </pre>
     */
    private static String buildBody(String title, String eventLabel, String actorName,
                                    String commentBody, String pageUrl) {
        String truncated;
        if (commentBody == null || commentBody.isBlank()) {
            truncated = "(пусто)";
        } else {
            truncated = commentBody.substring(0, Math.min(MAX_BODY_CHARS, commentBody.length()));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Страница: ").append(title).append('\n');
        sb.append("Событие: ").append(eventLabel).append('\n');
        sb.append("Автор: ").append(actorName).append('\n');
        sb.append('\n');
        sb.append(truncated).append('\n');
        sb.append('\n');
        sb.append(pageUrl).append('\n');
        sb.append('\n');
        sb.append("— Git-Sync plugin");
        return sb.toString();
    }

    /** "новый комментарий" -> "Новый комментарий" (body labels). */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
