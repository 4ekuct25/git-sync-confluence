package com.gitlab.sync.confluence.links;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves relative markdown links inside GitLab documents into URLs the
 * browser can follow, and appends a "source" link back to the GitLab blob.
 *
 * <p>All link handling here operates on the <em>rendered HTML</em> (after
 * markdown → HTML) so the results can be embedded directly in a Confluence
 * page.</p>
 *
 * <p>Two target forms are produced for repo-relative targets:</p>
 * <ul>
 *   <li>documents (and other non-downloadable files) — canonical GitLab web
 *       URL {@code {base}/{project}/-/blob/{ref}/{path}};</li>
 *   <li>attachments (json / csv / pdf / images / ...) — the plugin's
 *       attachment proxy {@code /rest/gitsync/1.0/attachments/download?
 *       connection={id}&amp;path={path}&amp;ref={ref}}, so a page reader
 *       downloads the file through Confluence instead of hitting the GitLab
 *       login wall. Markdown images use the proxy too, which lets them
 *       render inline in the page.</li>
 * </ul>
 */
public final class GitLabLinkResolver {

    private static final Logger LOG = LoggerFactory.getLogger(GitLabLinkResolver.class);

    /** Prefix of the attachment proxy endpoint (relative to the Confluence host). */
    static final String ATTACHMENT_PROXY = "/rest/gitsync/1.0/attachments/download";

    /**
     * File extensions served by the attachment proxy as downloads (MUST 8).
     * Single source of truth: the proxy's download allowlist is this set —
     * anything not listed here is never served by the proxy.
     *
     * <p>{@code html}/{@code htm} are served for the requirement R1 sandboxed
     * iframe rendering: the macro embeds an {@code <iframe sandbox>} whose
     * src is this proxy, so the page-scoped reference check must be able to
     * resolve the file the same way as any other attachment.</p>
     */
    public static final java.util.Set<String> ATTACHMENT_EXTS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "json", "csv", "pdf", "zip", "txt", "xml", "yaml", "yml",
                    "png", "jpg", "jpeg", "gif", "svg", "html", "htm"));

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(<img\\b[^>]*?)src\\s*=\\s*\"([^\"]*)\"([^>]*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "<a\\b([^>]*?)href\\s*=\\s*\"([^\"]*)\"([^>]*)>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private GitLabLinkResolver() {}

    /**
     * Build the attachment-proxy URL for a repo file, page-scoped — the same
     * URL {@link #rewriteTarget} produces for attachment links. Used by the
     * macro for requirement R1: an {@code .html} file is embedded as a
     * sandboxed iframe whose src is this URL.
     */
    public static String proxyUrl(String connectionId, String path, String ref, String pageId) {
        String url = ATTACHMENT_PROXY + "?connection="
                + UriUtils.encodeSegment(connectionId)
                + "&path=" + pathToSegments(path)
                + "&ref=" + UriUtils.encodeSegment(ref);
        if (pageId != null && !pageId.isEmpty()) {
            url += "&page=" + UriUtils.encodeSegment(pageId);
        }
        return url;
    }

    /** System property that overrides the Confluence base URL (R11 links). */
    public static final String CONFLUENCE_URL_PROPERTY = "gitsync.confluence.url";

    /** Default Confluence base URL used when the property is not set. */
    public static final String DEFAULT_CONFLUENCE_URL = "http://localhost:8090";

    public static String getConfluenceBaseUrl() {
        String url = System.getProperty(CONFLUENCE_URL_PROPERTY, DEFAULT_CONFLUENCE_URL);
        if (url == null || url.isEmpty()) {
            url = DEFAULT_CONFLUENCE_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Rewrite every relative link and image in the rendered HTML into a URL
     * the browser can resolve.
     *
     * <p>Rules (both for {@code <a href>} and {@code <img src>}):</p>
     * <ul>
     *   <li>absolute URLs ({@code http://}, {@code https://}, {@code mailto:})
     *       are left untouched;</li>
     *   <li>in-page anchor links ({@code #section}) are left untouched;</li>
     *   <li>relative / repo-absolute paths are resolved against
     *       {@code currentFilePath};</li>
     *   <li>resolved paths with an attachment extension (json / csv / pdf /
     *       images / ...) are rewritten to the plugin's attachment proxy —
     *       requires {@code connectionId};</li>
     *   <li>everything else (documents, unknown extensions) is rewritten to
     *       the Confluence page that embeds the target file, when one is
     *       known (requirement R11 — context-dependent links; uses
     *       {@code embeddingIndex}); otherwise to the canonical GitLab web
     *       URL {@code {gitlabBaseUrl}/{projectPath}/-/blob/{ref}/{resolvedPath}}
     *       (GitLab's web UI does not route {@code /projects/{id}/-/blob/},
     *       so the project's {@code path_with_namespace} is required).</li>
     * </ul>
     *
     * <p>When the context needed for a target form is missing (no
     * {@code projectPath} for blob URLs, no {@code connectionId} for proxy
     * URLs) the link is left as-is — safer than emitting a URL the browser
     * cannot resolve.</p>
     *
     * @param html             rendered HTML of the current document
     * @param currentFilePath  repo-relative path of the current file,
     *                          e.g. {@code docs/sub/page.md}
     * @param gitlabBaseUrl    base URL of the GitLab instance, e.g.
     *                          {@code https://git.example.com}
     * @param projectPath      project {@code path_with_namespace}, e.g.
     *                          {@code root/test-docs} (may be {@code null} or empty)
     * @param ref              branch / tag / SHA the document was read from
     * @param connectionId     id of the GitLab connection (for the attachment
     *                          proxy; may be {@code null} or empty)
     * @param pageId           id of the Confluence page being rendered
     *                          (scopes the attachment proxy; may be {@code null} or empty)
     * @param embeddingIndex   R11 index of embedded files (may be {@code null})
     * @param confluenceBaseUrl base URL of the Confluence instance for R11
     *                          page links (may be {@code null} or empty)
     * @return HTML with relative links/images rewritten to resolvable URLs
     */
    public static String resolveRelativeLinks(String html,
                                              String currentFilePath,
                                              String gitlabBaseUrl,
                                              String projectPath,
                                              String ref,
                                              String connectionId,
                                              String pageId,
                                              PageEmbeddingIndex embeddingIndex,
                                              String confluenceBaseUrl) {
        if (html == null || html.isEmpty()) {
            return html == null ? "" : html;
        }
        String base = (gitlabBaseUrl != null && !gitlabBaseUrl.isEmpty())
                ? gitlabBaseUrl
                : "";
        if (!base.isEmpty() && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        boolean blobAvailable = !base.isEmpty()
                && projectPath != null && !projectPath.isEmpty();
        if (!blobAvailable) {
            LOG.warn("resolveRelativeLinks: GitLab blob rewriting unavailable "
                    + "(file={}, base={}, projectPath={}); attachment-proxy "
                    + "rewriting still applies", currentFilePath,
                    gitlabBaseUrl, projectPath);
        }
        String currentDir = parentDir(currentFilePath);

        // 1. Anchors, then 2. markdown images.
        String out = rewrite(html, ANCHOR_PATTERN, true,
                currentDir, base, projectPath, ref, connectionId, pageId,
                embeddingIndex, confluenceBaseUrl);
        out = rewrite(out, IMG_PATTERN, false,
                currentDir, base, projectPath, ref, connectionId, pageId,
                embeddingIndex, confluenceBaseUrl);
        return out;
    }

    /**
     * Collect the {@code (path, ref)} pairs of every repo file this document's
     * rendered links/images would send to the attachment proxy — i.e. exactly
     * the set of files a page reader could download from the rendered page.
     * Keys are {@code path + "\u0000" + ref}.
     *
     * <p>Used by the attachment proxy to enforce "a download is only served
     * if the requested file is actually linked from the page's rendering".</p>
     */
    public static java.util.Set<String> referencedAttachmentKeys(String html,
                                                                 String currentFilePath,
                                                                 String gitlabBaseUrl,
                                                                 String projectPath,
                                                                 String ref,
                                                                 String connectionId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (html == null || html.isEmpty()
                || connectionId == null || connectionId.isEmpty()) {
            return keys;
        }
        String currentDir = parentDir(currentFilePath);
        collectKeys(html, ANCHOR_PATTERN, true, currentDir, ref, keys);
        collectKeys(html, IMG_PATTERN, false, currentDir, ref, keys);
        return keys;
    }

    private static void collectKeys(String html, Pattern pattern, boolean anchor,
                                    String currentDir, String ref,
                                    java.util.Set<String> keys) {
        Matcher m = pattern.matcher(html);
        while (m.find()) {
            String href = m.group(2);
            if (href == null || href.isEmpty()) {
                continue;
            }
            String trimmed = href.trim();
            if (isExternal(trimmed) || trimmed.startsWith("#")) {
                continue;
            }
            String pathPart = trimmed;
            int hash = trimmed.indexOf('#');
            if (hash >= 0) {
                pathPart = trimmed.substring(0, hash);
            }
            if (pathPart.isEmpty()) {
                continue;
            }
            String resolved = resolvePath(pathPart, currentDir);
            // Same condition rewriteTarget() uses to route to the proxy.
            boolean attachment = !anchor || isAttachmentExtension(resolved);
            if (attachment) {
                keys.add(resolved + "\u0000" + ref);
            }
        }
    }

    private static String rewrite(String html, Pattern pattern, boolean anchor,
                                  String currentDir, String base,
                                  String projectPath, String ref,
                                  String connectionId, String pageId,
                                  PageEmbeddingIndex embeddingIndex,
                                  String confluenceBaseUrl) {
        Matcher m = pattern.matcher(html);
        StringBuffer sb = new StringBuffer(html.length());
        while (m.find()) {
            String href = m.group(2);
            String newHref = rewriteTarget(href, currentDir, base,
                    projectPath, ref, connectionId, pageId, !anchor,
                    embeddingIndex, confluenceBaseUrl);
            String replacement;
            if (anchor) {
                replacement = "<a" + m.group(1) + "href=\"" + newHref + "\""
                        + m.group(3) + ">" + m.group(4) + "</a>";
            } else {
                replacement = m.group(1) + "src=\"" + newHref + "\""
                        + m.group(3) + ">";
            }
            // Escape $ and \ in replacement (Matcher.appendReplacement semantics).
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Append a small right-aligned "Source in GitLab" link at the bottom of
     * the rendered content.
     *
     * @param renderedHtml current rendered HTML
     * @param gitlabBaseUrl GitLab base URL
     * @param projectPath  project {@code path_with_namespace}
     * @param ref          branch / tag / SHA
     * @param filePath     repo-relative path of the document
     * @return HTML with the source link appended (original content unchanged;
     *         the link is omitted when {@code projectPath} is missing)
     */
    public static String addSourceLink(String renderedHtml,
                                       String gitlabBaseUrl,
                                       String projectPath,
                                       String ref,
                                       String filePath) {
        String body = renderedHtml == null ? "" : renderedHtml;
        if (gitlabBaseUrl == null || gitlabBaseUrl.isEmpty() || filePath == null
                || projectPath == null || projectPath.isEmpty()) {
            return body;
        }
        String base = gitlabBaseUrl.endsWith("/")
                ? gitlabBaseUrl.substring(0, gitlabBaseUrl.length() - 1)
                : gitlabBaseUrl;
        String url = base + "/" + pathToSegments(projectPath) + "/-/blob/"
                + UriUtils.encodeSegment(ref) + "/"
                + pathToSegments(filePath);

        String suffix = "<p style=\"text-align:right;font-size:0.85em;color:#888\">"
                + "<a href=\"" + url + "\">Исходник в GitLab</a></p>";
        return body + suffix;
    }

    // ------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------

    /**
     * Rewrite one relative target into its final URL.
     *
     * @param isImage {@code true} for {@code <img src>} — images always go
     *                through the proxy (inline rendering), documents keep
     *                the extension rule.
     */
    static String rewriteTarget(String href, String currentDir,
                                String base, String projectPath, String ref,
                                String connectionId, String pageId, boolean isImage,
                                PageEmbeddingIndex embeddingIndex,
                                String confluenceBaseUrl) {
        if (href == null || href.isEmpty()) {
            return href;
        }
        String trimmed = href.trim();

        // Leave absolute URLs, mailto, and in-page anchors untouched.
        if (isExternal(trimmed)) {
            return href;
        }
        if (trimmed.startsWith("#")) {
            return href;
        }

        // Split off any fragment (#section) so it can be re-attached later.
        String fragment = null;
        String pathPart = trimmed;
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            fragment = trimmed.substring(hash);
            pathPart = trimmed.substring(0, hash);
        }
        if (pathPart.isEmpty()) {
            return href;
        }

        String resolved = resolvePath(pathPart, currentDir);

        // Attachments and images: the plugin's proxy (no GitLab login wall).
        boolean attachment = isImage || isAttachmentExtension(resolved);
        if (attachment && connectionId != null && !connectionId.isEmpty()) {
            String url = ATTACHMENT_PROXY + "?connection="
                    + UriUtils.encodeSegment(connectionId)
                    + "&path=" + pathToSegments(resolved)
                    + "&ref=" + UriUtils.encodeSegment(ref);
            // The page id scopes the download: the proxy only serves a file
            // when the requesting page's rendering links it.
            if (pageId != null && !pageId.isEmpty()) {
                url += "&page=" + UriUtils.encodeSegment(pageId);
            }
            return url; // fragments are meaningless for downloads/images
        }
        // Documents, requirement R11: in a Confluence context a link to a
        // file that is itself embedded in a Confluence page must point at
        // that page, not at GitLab. The same raw file keeps its GitLab
        // meaning in a GitLab context (this resolver only runs for the
        // Confluence rendering).
        if (embeddingIndex != null && confluenceBaseUrl != null
                && !confluenceBaseUrl.isEmpty()
                && connectionId != null && !connectionId.isEmpty()) {
            String confluencePage = embeddingIndex.findPage(connectionId, resolved, ref);
            if (confluencePage != null) {
                String pageUrl = confluenceBaseUrl + "/pages/viewpage.action"
                        + "?pageId=" + confluencePage;
                if (fragment != null) {
                    pageUrl += fragment;
                }
                return pageUrl;
            }
        }
        // Documents: canonical GitLab web URL.
        if (base.isEmpty() || projectPath == null || projectPath.isEmpty()) {
            return href;
        }
        String url = base + "/" + pathToSegments(projectPath) + "/-/blob/"
                + UriUtils.encodeSegment(ref) + "/"
                + pathToSegments(resolved);
        if (fragment != null) {
            url += fragment;
        }
        return url;
    }

    public static boolean isAttachmentExtension(String repoPath) {
        int slash = repoPath.lastIndexOf('/');
        int dot = repoPath.lastIndexOf('.');
        if (dot < 0 || dot < slash) {
            return false;
        }
        return ATTACHMENT_EXTS.contains(
                repoPath.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isExternal(String href) {
        String lower = href.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("mailto:")
                || lower.startsWith("ftp://")
                || lower.startsWith("tel:")
                || href.startsWith("//");
    }

    /**
     * Resolve a link target (which may be relative, absolute within repo, or
     * with {@code ../}) against the directory of the current file.
     */
    static String resolvePath(String target, String currentDir) {
        String t = target;
        // Normalize leading "./"
        while (t.startsWith("./")) {
            t = t.substring(2);
        }

        String[] result;
        if (t.startsWith("/")) {
            // Repo-absolute path.
            result = new String[]{t};
        } else {
            String base = currentDir == null ? "" : currentDir;
            // base already ends with '/' or is "".
            String joined = base + t;
            result = normalizeSegments(joined.split("/"));
        }
        return join(result);
    }

    /**
     * Collapse {@code .} and {@code ..} segments out of a path, never
     * escaping above the repo root.
     */
    private static String[] normalizeSegments(String[] parts) {
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String p : parts) {
            if (p == null || p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                // Do not pop past root; GitLab would 404 otherwise.
            } else {
                stack.addLast(p);
            }
        }
        // addLast/removeLast keep insertion order, so no reversal is needed.
        return stack.toArray(new String[0]);
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * Return the directory portion of a repo-relative path, with a trailing
     * slash (or "" for files at the root).
     */
    static String parentDir(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return filePath.substring(0, lastSlash + 1);
    }

    /**
     * URL-encode each path segment and join with '/'.
     */
    public static String pathToSegments(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(UriUtils.encodeSegment(p));
        }
        return sb.toString();
    }

    /** Tiny internal URL encoding helper (UTF-8, RFC 3986 friendly). */
    private static final class UriUtils {
        private UriUtils() {}

        static String encodeSegment(String segment) {
            if (segment == null) return "";
            return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }
    }
}
