package com.gitlab.sync.confluence.macro;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.Macro.BodyType;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.macro.Macro.OutputType;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import com.gitlab.sync.confluence.links.GitLabLinkResolver;
import com.gitlab.sync.confluence.links.PageEmbeddingIndex;
import com.gitlab.sync.confluence.security.SanitizeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * Confluence macro that live-renders a Markdown file from a configured GitLab
 * connection straight into a Confluence page.
 *
 * <p>Parameters:</p>
 * <ul>
 *   <li>{@code connection} — id of the GitLab connection to use (required)</li>
 *   <li>{@code path}       — repo-relative path of the Markdown file (required)</li>
 *   <li>{@code ref}        — branch / tag / SHA to read (default {@code main})</li>
 * </ul>
 *
 * <p>Pipeline: fetch raw Markdown → convert to Confluence Storage Format (with
 * Kroki diagrams) → resolve relative links → sanitise → append a source link →
 * wrap in a {@code <div class="git-sync-content">} for the client-side sync
 * widget.</p>
 *
 * <p>This class implements <em>both</em> macro SPIs required by Confluence
 * 8.x: the atlassian-renderer v2 SPI
 * ({@code com.atlassian.renderer.v2.macro.Macro} — the one the macro module
 * descriptor instantiates and registers) and the legacy
 * {@code com.atlassian.confluence.macro.Macro} SPI used by the legacy
 * render pipeline. Implementing only the legacy SPI makes the descriptor's
 * {@code createModule()} cast fail and the macro silently unregister.</p>
 *
 * <p><strong>Safe-failure rule:</strong> any exception in the pipeline is
 * caught and surfaced as a readable red error box. This macro never returns
 * empty content and never lets a page fail to render because of a Git-Sync
 * error.</p>
 */
public class GitSyncMacro implements Macro, com.atlassian.renderer.v2.macro.Macro {

    private static final Logger LOG = LoggerFactory.getLogger(GitSyncMacro.class);

    private static final String DEFAULT_REF = "main";

    @Override
    public String execute(Map<String, String> params, String body, ConversionContext context)
            throws MacroExecutionException {
        return render(param(params, "connection"), param(params, "path"),
                paramOr(params, "ref", DEFAULT_REF), pageIdOrNull(context));
    }

    @Override
    public String execute(Map<String, String> params, String body, com.atlassian.renderer.RenderContext context)
            throws com.atlassian.renderer.v2.macro.MacroException {
        // Confluence 8.5 passes a com.atlassian.confluence.renderer.PageContext
        // (extends RenderContext, NOT ConversionContext) whose getEntity()
        // carries the rendered page.
        String pageId = pageIdFromV2Context(context);
        return render(param(params, "connection"), param(params, "path"),
                paramOr(params, "ref", DEFAULT_REF), pageId);
    }

    private String render(String connectionId, String path, String ref, String pageId) {
        try {
            if (connectionId == null || connectionId.isEmpty()) {
                return error("Параметр connection не задан", path, connectionId, ref);
            }
            if (path == null || path.isEmpty()) {
                return error("Параметр path не задан", path, connectionId, ref);
            }

            // 2. Connection store (shared singletons populated at boot).
            ConnectionStore store = PluginContext.getStore();
            if (store == null) {
                return error("Компонент Git-Sync не инициализирован (PluginContext.store == null)",
                        path, connectionId, ref);
            }

            // 3. Look up the connection — by id first, then by name
            //    (macro parameters commonly reference the human-readable name).
            Optional<GitLabConnection> found = store.getById(connectionId);
            if (!found.isPresent()) {
                for (GitLabConnection c : store.getAll()) {
                    if (connectionId.equalsIgnoreCase(c.getName())) {
                        found = Optional.of(c);
                        break;
                    }
                }
            }
            if (!found.isPresent()) {
                return error("Соединение " + connectionId + " не найдено",
                        path, connectionId, ref);
            }
            GitLabConnection connection = found.get();
            String baseUrl = connection.getBaseUrl();

            // 4. Fetch the raw markdown file.
            GitLabClient client = PluginContext.newClient(connection);

            // 4b. Requirement R1: an .html file renders as a visual page —
            //     a sandboxed iframe over the attachment proxy. Scripts run
            //     only inside the iframe's sandbox (allow-scripts), never on
            //     the Confluence page itself; the proxy keeps the usual
            //     page-scoped authorization (the file must be this page's
            //     macro source or a link inside its rendering).
            if (isHtmlFile(path)) {
                String htmlProjectPath = connection.getProjectPath();
                if (htmlProjectPath == null || htmlProjectPath.isEmpty()) {
                    htmlProjectPath = client.fetchProjectPath();
                }
                // R11: the embedding index learns about this page — links
                // from other Confluence pages to this .html file must point
                // here, not at GitLab.
                PageEmbeddingIndex.instance().record(
                        connection.getId(), path, ref, pageId);
                String iframeUrl = GitLabLinkResolver.proxyUrl(
                        connection.getId(), path, ref, pageId);
                String iframe = "<div class=\"git-sync-content\""
                        + " data-page-id=\"" + escape(pageId) + "\""
                        + " data-source-path=\"" + escape(path) + "\""
                        + " data-ref=\"" + escape(ref) + "\""
                        + "><iframe src=\"" + escape(iframeUrl) + "\""
                        + " sandbox=\"allow-scripts\""
                        + " style=\"width:100%;min-height:600px;border:1px solid #ddd;border-radius:4px\">"
                        + "<p>HTML-страница недоступна (браузер блокирует iframe)</p>"
                        + "</iframe>"
                        + "<p style=\"text-align:right;font-size:0.85em;color:#888\">"
                        + "<a href=\"" + escape(baseUrl + "/"
                                + GitLabLinkResolver.pathToSegments(htmlProjectPath)
                                + "/-/blob/"
                                + GitLabLinkResolver.pathToSegments(ref)
                                + "/" + GitLabLinkResolver.pathToSegments(path))
                                + "\">Исходник в GitLab</a></p>"
                        + "</div>";
                return iframe;
            }

            String markdown = client.fetchRawFile(path, ref);

            // 4c. FR05: a markdown file larger than the configured limit
            //     (maxMarkdownFileSizeBytes, default 5 MiB, admin panel) is
            //     NOT converted — converting a multi-megabyte document pins
            //     a render thread for minutes and can stall page opens.
            long limit = com.gitlab.sync.confluence.settings.SettingsStore
                    .instance().current().maxMarkdownFileSizeBytes;
            int sizeBytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (sizeBytes > limit) {
                LOG.warn("git-sync macro: file {}@{} is {} bytes, limit is {} — not converting",
                        path, ref, sizeBytes, limit);
                com.gitlab.sync.confluence.errors.ErrorJournal.record(
                        "macro", "path=" + path + " connection=" + connectionId + " ref=" + ref,
                        new java.io.IOException(
                                "File exceeds the size limit (" + limit + " bytes)"));
                return error("Файл превышает лимит размера ("
                        + humanBytes(limit) + "; фактический размер "
                        + humanBytes(sizeBytes) + ")",
                        path, connectionId, ref);
            }

            // 5. Convert markdown → Confluence Storage Format (with Kroki
            //    diagrams). The storage format uses <a href> for links, which
            //    the link resolver below rewrites.
            String krokiUrl = KrokiRenderer.getKrokiBaseUrl();
            String rendered = MarkdownToStorageConverter.convert(markdown, krokiUrl);

            // 5b. Requirement R2/R3 error scenario: pre-fetch every diagram
            //     so a syntax error shows a message (with the line number
            //     when the upstream tool reports it) instead of an empty
            //     block. Working diagrams are left untouched.
            rendered = KrokiRenderer.validateDiagrams(rendered, krokiUrl);

            // 6. Rewrite relative links to absolute (canonical) GitLab URLs.
            //    The project path (path_with_namespace) is stored with the
            //    connection; fall back to a live lookup for connections
            //    created before the field existed.
            String projectPath = connection.getProjectPath();
            if (projectPath == null || projectPath.isEmpty()) {
                projectPath = client.fetchProjectPath();
            }
            // R11: ask the embedding index whether each linked file is itself
            // embedded in a Confluence page (→ point at that page).
            PageEmbeddingIndex embeddingIndex = PageEmbeddingIndex.instance();
            rendered = GitLabLinkResolver.resolveRelativeLinks(
                    rendered, path, baseUrl, projectPath, ref,
                    connection.getId(), pageId,
                    embeddingIndex, GitLabLinkResolver.getConfluenceBaseUrl());

            // 7. Sanitise for safe embedding into a page.
            rendered = SanitizeUtil.sanitizeForStorage(rendered);

            // 8. Append a "source in GitLab" link.
            rendered = GitLabLinkResolver.addSourceLink(
                    rendered, baseUrl, projectPath, ref, path);

            // 8b. R11: this page now embeds the file — record it so other
            //     pages' links to this file point at this page.
            embeddingIndex.record(connection.getId(), path, ref, pageId);

            // 9. Wrap with the data attributes the client-side JS widget reads.
            //    data-connection-id routes line comments to native GitLab
            //    commit discussions (FR09 git-native mode).
            String wrapped = "<div class=\"git-sync-content\""
                    + " data-page-id=\"" + escape(pageId) + "\""
                    + " data-connection-id=\"" + escape(connection.getId()) + "\""
                    + " data-source-path=\"" + escape(path) + "\""
                    + " data-ref=\"" + escape(ref) + "\""
                    + ">" + rendered + "</div>";

            return wrapped;
        } catch (Exception e) {
            LOG.warn("git-sync macro failed for connection={} path={} ref={}: {}",
                    connectionId, path, ref, e.getMessage(), e);
            com.gitlab.sync.confluence.errors.ErrorJournal.record(
                    "macro", "path=" + path + " connection=" + connectionId + " ref=" + ref, e);
            return error(safeMessage(e), path, connectionId, ref);
        }
    }

    @Override
    public BodyType getBodyType() {
        // This macro takes no body — everything comes from parameters.
        return BodyType.NONE;
    }

    @Override
    public OutputType getOutputType() {
        // Output is a block-level wrapper div.
        return OutputType.BLOCK;
    }

    // ------------------------------------------------------------------
    // renderer v2 SPI
    // ------------------------------------------------------------------

    @Override
    public com.atlassian.renderer.TokenType getTokenType(Map<String, String> params, String body,
            com.atlassian.renderer.RenderContext context) {
        return com.atlassian.renderer.TokenType.BLOCK;
    }

    @Override
    public boolean isInline() {
        return false;
    }

    @Override
    public boolean hasBody() {
        return false;
    }

    @Override
    public com.atlassian.renderer.v2.RenderMode getBodyRenderMode() {
        return com.atlassian.renderer.v2.RenderMode.NO_RENDER;
    }

    @Override
    public boolean suppressSurroundingTagDuringWysiwygRendering() {
        return false;
    }

    @Override
    public boolean suppressMacroRenderingDuringWysiwyg() {
        return false;
    }

    @Override
    public com.atlassian.renderer.v2.macro.WysiwygBodyType getWysiwygBodyType() {
        return com.atlassian.renderer.v2.macro.WysiwygBodyType.PREFORMAT;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String pageIdFromV2Context(Object context) {
        if (context == null) {
            return "";
        }
        String id = "";
        try {
            // Confluence 8.5 page renders hand us a PageContext whose
            // getEntity() carries the rendered page (verified empirically:
            // "page: <title> v.<n> (<id>)").
            Object entity = context.getClass().getMethod("getEntity").invoke(context);
            if (entity != null) {
                id = entityIdOf(entity);
            }
        } catch (Throwable t) {
            LOG.debug("getEntity failed: {}", t.getMessage());
        }
        if (id.isEmpty()) {
            try {
                java.util.Map<Object, Object> params =
                        (java.util.Map<Object, Object>) context.getClass().getMethod("getParams").invoke(context);
                if (params != null) {
                    for (Object v : params.values()) {
                        String cand = entityIdOf(v);
                        if (!cand.isEmpty()) {
                            id = cand;
                        }
                    }
                }
            } catch (Throwable ignore) {
                // renderer params do not always carry the entity — optional
            }
        }
        if (id.isEmpty()) {
            id = pageIdFromValueStack(context);
        }
        return id;
    }

    /**
     * Extract the numeric id from a content entity without relying on the
     * compile-time ContentEntityObject type (OSGi classloading can split the
     * hierarchy): tries getIdAsString(), falls back to the primitive getId().
     */
    private static String entityIdOf(Object entity) {
        if (entity == null) {
            return "";
        }
        try {
            Object v = entity.getClass().getMethod("getIdAsString").invoke(entity);
            String s = v == null ? "" : String.valueOf(v).trim();
            if (s.matches("\\d+")) {
                return s;
            }
        } catch (Throwable t) {
            // OSGi: a direct call through the imported ContentEntityObject
            // type can fail to link; the reflection path below still works.
            LOG.debug("getIdAsString failed, falling back to getId: {}", t.getMessage());
        }
        try {
            Object v = entity.getClass().getMethod("getId").invoke(entity);
            String s = v == null ? "" : String.valueOf(v).trim();
            if (s.matches("\\d+")) {
                return s;
            }
        } catch (Throwable t) {
            LOG.debug("getId failed: {}", t.getMessage());
        }
        return "";
    }

    /** Value-stack lookup: the Struts2 root of a page render is the view action. */
    private static String pageIdFromValueStack(Object context) {
        try {
            Object vs = context.getClass().getMethod("getValueStack").invoke(context);
            if (vs == null) {
                return "";
            }
            for (String expr : new String[]{"page.id", "content.id"}) {
                Object v = vs.getClass().getMethod("findValue", String.class).invoke(vs, expr);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (s.matches("\\d+")) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignore) {
            // value-stack layout varies by render pipeline — optional
        }
        return "";
    }

    private static String pageIdOrNull(ConversionContext context) {
        if (context == null) {
            return "";
        }
        try {
            ContentEntityObject entity = context.getEntity();
            if (entity != null) {
                String id = entityIdOf(entity);
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not resolve page id: {}", e.getMessage());
        }
        return "";
    }

    private static String error(String message, String path, String connectionId, String ref) {
        String msg = (message == null || message.isEmpty()) ? "Неизвестная ошибка" : message;
        return "<div class=\"git-sync-error\" style=\"border:1px solid #e74c3c;padding:1em;background:#fdf0f0\">"
                + "<p><strong>⚠ Git-Sync ошибка</strong></p>"
                + "<p>Не удалось загрузить файл: " + escape(msg) + "</p>"
                + "<p style=\"font-size:0.85em;color:#888\">Путь: " + escape(path)
                + " | Конн: " + escape(connectionId)
                + " | Ref: " + escape(ref) + "</p>"
                + "</div>";
    }

    /** Human-readable size label: «5.0 МБ» / «204.8 КБ» / «100 байт». */
    private static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f КБ", bytes / 1024.0);
        }
        return bytes + " байт";
    }

    /** Reduce an exception to a short, user-safe message (no stack trace). */
    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Неизвестная ошибка";
        }
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 300) {
            m = m.substring(0, 300) + "…";
        }
        return m;
    }

    private static String param(Map<String, String> params, String key) {
        if (params == null) {
            return null;
        }
        String v = params.get(key);
        return v == null ? null : v.trim();
    }

    /** Requirement R1 — is the requested file an HTML document? */
    private static boolean isHtmlFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    private static String paramOr(Map<String, String> params, String key, String def) {
        String v = param(params, key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
