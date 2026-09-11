package com.gitlab.sync.confluence.comments.git;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.sync.confluence.GitsyncServices;
import com.gitlab.sync.confluence.comments.CommentStore;
import com.gitlab.sync.confluence.comments.LineComment;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot, idempotent migration of legacy local line comments
 * ({@code comments.json}) into native GitLab commit discussions.
 *
 * <p>For every Confluence page that embeds a {@code git-sync} macro of the
 * given connection, each not-yet-migrated local comment is recreated in
 * GitLab (top-level → new discussion anchored to the same block; reply →
 * note on its thread's discussion), its resolved state is copied as a
 * local marker, the legacy record is marked with {@code migratedTo} and
 * then removed from the local store.</p>
 *
 * <p>Idempotency: a comment with a non-blank {@code migratedTo} is skipped;
 * after a successful migration the legacy record is deleted, so a second
 * run migrates nothing. Comments whose anchor fragment no longer exists
 * ({@code lineAnchor < 0}) cannot be anchored in GitLab and are skipped
 * with a reason in the summary.</p>
 *
 * <p>Page enumeration is driven by the local comment store's page ids
 * (fetched one by one via {@code PageManager.getPage(long)}): the 8.5
 * runtime {@code PageManager} proxy has no no-arg {@code getPages()}, and
 * every page the migration cares about has at least one local comment.</p>
 */
public class CommentMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(CommentMigrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern MACRO = Pattern.compile(
            "<ac:structured-macro[^>]*ac:name=\"git-sync\"[^>]*>(.*?)</ac:structured-macro>",
            Pattern.DOTALL);
    private static final Pattern PARAM = Pattern.compile(
            "<ac:parameter\\s+ac:name=\"([^\"]+)\"[^>]*>(.*?)</ac:parameter>",
            Pattern.DOTALL);

    /** Shared with the REST resource (see {@link CommentStore#instance()}) so
     *  the migration's deletes are visible to the comment list. */
    private final CommentStore store = CommentStore.instance();

    /** A page that embeds a git-sync macro of the migrated connection.
     *
     *  <p>Plain class, NOT a {@code record}: Confluence 8.5's REST scanner
     *  (pre-ASM8) cannot parse record class files — a record in the bundle
     *  makes {@code JarIndexer.analyzeClassFile} throw
     *  "Record requires ASM8" and the whole gitsync-rest module fails to
     *  register (every {@code /rest/gitsync/1.0/*} → 404). */
    private static final class PageMacro {
        final long pageId;
        final String path;
        final String ref;

        PageMacro(long pageId, String path, String ref) {
            this.pageId = pageId;
            this.path = path;
            this.ref = ref;
        }
    }

    /**
     * Migrate all legacy comments of the given connection into native
     * GitLab commit discussions.
     *
     * @throws IllegalStateException when the PAT cannot write to the project
     * @throws RuntimeException when Confluence content services are unavailable
     */
    public ObjectNode migrate(GitLabConnection conn, GitLabClient client) {
        int access = client.projectAccessLevel();
        if (access < 30) {
            throw new IllegalStateException(access >= 0
                    ? "Миграция невозможна: пользователю PAT недостаточно прав "
                          + "(access_level=" + access + ", нужен Developer, 30+)"
                    : "Миграция невозможна: не удалось определить права доступа к проекту");
        }

        ObjectNode summary = MAPPER.createObjectNode();
        ArrayNode errors = summary.putArray("errors");
        int migrated = 0, skipped = 0, failed = 0;
        int pagesWithMacro = 0, pagesWithComments = 0;

        for (PageMacro pm : scanPages(conn)) {
            pagesWithMacro++;
            List<LineComment> local = store.getAll(pm.pageId);
            List<LineComment> toMigrate = new ArrayList<>();
            for (LineComment c : local) {
                if (c.getMigratedTo() == null || c.getMigratedTo().isBlank()) {
                    toMigrate.add(c);
                }
            }
            if (toMigrate.isEmpty()) {
                continue;
            }
            pagesWithComments++;

            GitLabCommentStore git = new GitLabCommentStore(client, conn.getId(), pm.path, pm.ref);

            // Legacy thread root per comment: GitLab replies attach to the
            // thread's top-level discussion, so any legacy nesting depth
            // collapses to the root.
            Map<String, LineComment> byId = new HashMap<>();
            for (LineComment c : local) {
                byId.put(c.getId(), c);
            }
            Map<String, String> rootOf = new HashMap<>();
            for (LineComment c : local) {
                LineComment r = c;
                Set<String> seen = new HashSet<>();
                while (r.getParentId() != null
                        && byId.containsKey(r.getParentId())
                        && seen.add(r.getId())) {
                    r = byId.get(r.getParentId());
                }
                rootOf.put(c.getId(), r.getId());
            }

            List<LineComment> roots = new ArrayList<>();
            List<LineComment> replies = new ArrayList<>();
            for (LineComment c : toMigrate) {
                if (rootOf.get(c.getId()).equals(c.getId())) {
                    roots.add(c);
                } else {
                    replies.add(c);
                }
            }
            roots.sort(Comparator.comparingLong(LineComment::getCreatedAt));
            replies.sort(Comparator.comparingLong(LineComment::getCreatedAt));

            // legacy root id -> composite git id of the new discussion
            Map<String, String> threadIds = new LinkedHashMap<>();

            for (LineComment c : roots) {
                if (c.getLineAnchor() < 0) {
                    skipped++;
                    errors.add(String.format(
                            "стр.%d: %s — «фрагмент не найден», якоря нет — пропущен",
                            pm.pageId, shortId(c.getId())));
                    continue;
                }
                String newId = migrateOne(git, c, null, pm, errors);
                if (newId != null) {
                    threadIds.put(c.getId(), newId);
                    migrated++;
                } else {
                    failed++;
                }
            }
            for (LineComment c : replies) {
                String thread = threadIds.get(rootOf.get(c.getId()));
                if (thread == null || thread.isBlank()) {
                    skipped++;
                    errors.add(String.format(
                            "стр.%d: %s — корень треда не перенесён — пропущен",
                            pm.pageId, shortId(c.getId())));
                    continue;
                }
                if (migrateOne(git, c, thread, pm, errors) != null) {
                    migrated++;
                } else {
                    failed++;
                }
            }
        }

        summary.put("connectionId", conn.getId());
        summary.put("connectionName", conn.getName());
        summary.put("pagesWithMacro", pagesWithMacro);
        summary.put("pagesWithComments", pagesWithComments);
        summary.put("migrated", migrated);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        return summary;
    }

    /**
     * Recreate one legacy comment in GitLab, copy its resolved state, mark
     * and delete the legacy record.
     *
     * @param threadId composite id of the parent thread ({@code :d:}), or
     *                 null for a top-level comment
     * @return the composite id of the created git comment, or null (with a
     *         reason appended to {@code errors}) when GitLab rejected it
     */
    private String migrateOne(GitLabCommentStore git, LineComment c,
                              String threadId, PageMacro pm, ArrayNode errors) {
        try {
            LineComment created = git.add(
                    c.getLineAnchor(),
                    c.getAuthor() == null ? "unknown" : c.getAuthor(),
                    c.getBody(),
                    threadId,
                    c.getLineText());
            if (c.isResolved()) {
                git.setResolved(created.getId(), true, "migrator");
            }
            store.setMigratedTo(c.getId(), created.getId());
            store.delete(c.getId());
            return created.getId();
        } catch (GitLabCommentStore.GitCommentException e) {
            LOG.warn("Migration of comment {} (page {}) failed: {}",
                    c.getId(), pm.pageId, e.getMessage());
            errors.add(String.format("стр.%d: %s — %s",
                    pm.pageId, shortId(c.getId()), e.getMessage()));
            return null;
        }
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? String.valueOf(id) : id.substring(0, 8);
    }

    // ------------------------------------------------------------------
    //  page scanning (reflection — see class javadoc)
    // ------------------------------------------------------------------

    private List<PageMacro> scanPages(GitLabConnection conn) {
        com.atlassian.confluence.pages.PageManager pm = GitsyncServices.pageManager();
        if (pm == null) {
            throw new RuntimeException(
                    "Confluence PageManager недоступен — миграция невозможна");
        }
        // Drive the scan from the local store's page ids: the 8.5 runtime
        // PageManager proxy has NO no-arg getPages() (the old reflection
        // NoSuchMethod'd on it), while getPage(long) is available and every
        // page the migration cares about has at least one local comment.
        List<PageMacro> out = new ArrayList<>();
        for (long pageId : store.pageIdsWithComments()) {
            Object page;
            try {
                page = pm.getPage(pageId);
            } catch (Exception e) {
                LOG.warn("getPage({}) failed during migration scan: {}",
                        pageId, e.getMessage());
                continue;
            }
            if (page == null) {
                continue;
            }
            try {
                // getBodyAsString() is the concrete storage-body accessor on the
                // 8.5 Page class — getContent() is not reachable here (the same
                // OSGi-linkage reason AttachmentProxyResource documents).
                Object bodyObj = page.getClass().getMethod("getBodyAsString").invoke(page);
                if (!(bodyObj instanceof String body) || body.isEmpty()) {
                    continue;
                }
                PageMacro found = macroOf(body, conn);
                if (found != null) {
                    out.add(new PageMacro(pageId, found.path, found.ref));
                }
            } catch (Exception e) {
                LOG.warn("Skipped page {} during migration scan: {}",
                        pageId, e.getMessage());
            }
        }
        return out;
    }

    /** The first git-sync macro in the storage body pointing at {@code conn}. */
    private static PageMacro macroOf(String storage, GitLabConnection conn) {
        Matcher macro = MACRO.matcher(storage);
        while (macro.find()) {
            Map<String, String> params = new HashMap<>();
            Matcher p = PARAM.matcher(macro.group(1));
            while (p.find()) {
                params.put(p.group(1), unescape(p.group(2).trim()));
            }
            String connection = params.getOrDefault("connection", "");
            if (!connection.equals(conn.getId())
                    && !connection.equalsIgnoreCase(conn.getName())) {
                continue;
            }
            String path = params.getOrDefault("path", "");
            if (path.isBlank()) {
                continue;
            }
            String ref = params.getOrDefault("ref", "");
            return new PageMacro(0, path, ref.isBlank() ? "main" : ref);
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
