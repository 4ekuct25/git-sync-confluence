package com.gitlab.sync.confluence.attachments;

import com.atlassian.confluence.core.ContentPermissionManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.security.ContentPermission;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.user.User;
import com.gitlab.sync.confluence.GitsyncPaths;
import com.gitlab.sync.confluence.GitsyncServices;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import com.gitlab.sync.confluence.links.GitLabLinkResolver;
import com.gitlab.sync.confluence.macro.KrokiRenderer;
import com.gitlab.sync.confluence.macro.MarkdownToStorageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderNames;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS proxy that streams attachment files straight from the linked GitLab
 * repository to the Confluence browser.
 *
 * <p>Endpoint: {@code /rest/gitsync/1.0/attachments/download}</p>
 *
 * <p>Query params:</p>
 * <ul>
 *   <li>{@code connection} — id of a stored {@link GitLabConnection}</li>
 *   <li>{@code path}       — file path in the repository (repo-relative)</li>
 *   <li>{@code ref}        — branch / tag / SHA to read the file from</li>
 *   <li>{@code page}       — id of the Confluence page whose rendering links
 *       the file (required; the download is scoped to that page)</li>
 * </ul>
 *
 * <h2>Authorization model</h2>
 * The proxy serves <em>only what a user who may view the rendering could
 * already see</em>: the caller must be a logged-in user, must hold the
 * View permission on the page named by {@code page}, and the requested
 * {@code (path, ref)} must be a file that the page's git-sync macro
 * rendering actually links (same pipeline the macro uses: fetch the
 * document, convert it, collect the attachment targets). Anything else —
 * arbitrary repo files, markdown sources, other pages' attachments,
 * anonymous requests — is refused.
 *
 * <p>The file is streamed back with a proper {@code Content-Type} and a
 * {@code Content-Disposition: attachment} header so the browser offers to
 * save it instead of rendering raw bytes inline.</p>
 */
@Path("/attachments")
@Produces(MediaType.WILDCARD)
public class AttachmentProxyResource {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentProxyResource.class);
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L; // 10 MB

    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        // Only the extensions of the shared allowlist (single source of
        // truth: GitLabLinkResolver.ATTACHMENT_EXTS) get a friendly type;
        // anything else is refused before it reaches the network.
        CONTENT_TYPES.put("json", "application/json");
        CONTENT_TYPES.put("csv", "text/csv");
        CONTENT_TYPES.put("pdf", "application/pdf");
        CONTENT_TYPES.put("zip", "application/zip");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("txt", "text/plain");
        CONTENT_TYPES.put("xml", "application/xml");
        CONTENT_TYPES.put("yaml", "text/yaml");
        CONTENT_TYPES.put("yml", "text/yaml");
        // Requirement R1: an .html document embedded via a sandboxed iframe
        // must be served as a renderable page, not a download.
        CONTENT_TYPES.put("html", "text/html");
        CONTENT_TYPES.put("htm", "text/html");
    }

    /** One git-sync macro instance inside a page's storage body. Confluence
     * stores macros as either the modern {@code <ac:structured-macro>} or the
     * legacy {@code <ac:macro>} form (older pages / direct storage writes keep
     * the legacy tag), so the reference check must accept both. */
    private static final Pattern MACRO_BLOCK = Pattern.compile(
            "<(?:ac:structured-macro|ac:macro)[^>]*ac:name=\"git-sync\"[^>]*>"
                    + "(.*?)</(?:ac:structured-macro|ac:macro)>",
            Pattern.DOTALL);
    private static final Pattern MACRO_PARAM = Pattern.compile(
            "<ac:parameter ac:name=\"(connection|path|ref)\">([^<]*)</ac:parameter>");

    @GET
    @Path("/download")
    @RolesAllowed("users")
    public Response download(@Context SecurityContext security,
                             @QueryParam("connection") String connectionId,
                             @QueryParam("path") String path,
                             @QueryParam("ref") String ref,
                             @QueryParam("page") String pageParam) {
        if (connectionId == null || connectionId.isEmpty()) {
            return badRequest("missing connection id");
        }
        if (path == null || path.isEmpty()) {
            return badRequest("missing path");
        }
        if (ref == null || ref.isEmpty()) {
            return badRequest("missing ref");
        }
        if (pageParam == null || pageParam.isEmpty()) {
            return badRequest("missing page id");
        }
        if (!pageParam.matches("\\d+")) {
            return badRequest("invalid page id");
        }
        if (path.contains("..")) {
            // Reject path traversal.
            return badRequest("invalid path");
        }
        // Extension allowlist (shared with the link resolver — the proxy
        // can only serve files the rendered page could link to).
        if (!GitLabLinkResolver.isAttachmentExtension(path)) {
            return badRequest("file type not allowed");
        }

        ConnectionStore store;
        try {
            store = getConnectionStore();
        } catch (RuntimeException e) {
            LOG.error("Cannot access secure dir for ConnectionStore", e);
            com.gitlab.sync.confluence.errors.ErrorJournal.record(
                    "rest:attachments", "path=" + path + " connection=" + connectionId, e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"secure dir unavailable\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        Optional<GitLabConnection> connOpt = store.getById(connectionId);
        if (connOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"connection not found\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        GitLabConnection conn = connOpt.get();

        // --- page-scoped authorization ------------------------------------
        UserAccessor userAccessor = GitsyncServices.userAccessor();
        PageManager pageManager = GitsyncServices.pageManager();
        ContentPermissionManager permissionManager = GitsyncServices.contentPermissionManager();
        if (userAccessor == null || pageManager == null || permissionManager == null) {
            LOG.error("Confluence content services are not available "
                    + "(userAccessor={}, pageManager={}, permissionManager={})",
                    userAccessor != null, pageManager != null, permissionManager != null);
            com.gitlab.sync.confluence.errors.ErrorJournal.record(
                    "rest:attachments", "content services unavailable", null);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"content services unavailable\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // DMZ JAX-RS runtime is 1.1 (jsr311-api-1.1.1): SecurityContext has
        // NO getUsername()/getName() — only getUserPrincipal()/isUserInRole()/
        // isSecure()/getAuthenticationScheme(). Principal.getName() is JDK.
        String username = (security == null || security.getUserPrincipal() == null)
                ? null
                : security.getUserPrincipal().getName();
        User user = username == null ? null : userAccessor.getUser(username);
        if (user == null) {
            return forbidden("unknown user");
        }
        Page page = pageManager.getPage(Long.parseLong(pageParam));
        if (page == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"page not found\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (!permissionManager.hasContentLevelPermission(
                user, ContentPermission.VIEW_PERMISSION, page)) {
            return forbidden("no view permission on page " + pageParam);
        }
        // The file must actually be linked from this page's git-sync
        // rendering — otherwise the proxy is just a repo file browser.
        if (!pageReferencesAttachment(pageBodyAsString(page), conn, path, ref, pageParam)) {
            return forbidden("file is not referenced by page " + pageParam);
        }
        // --- end page-scoped authorization --------------------------------

        GitLabClient client = new GitLabClient(conn);

        byte[] bytes;
        try {
            bytes = client.fetchRawFileBytes(path, ref);
        } catch (GitLabClient.FileSizeLimitExceededException e) {
            com.gitlab.sync.confluence.errors.ErrorJournal.record(
                    "rest:attachments", "path=" + path + " connection=" + connectionId + " ref=" + ref, e);
            return Response.status(413) // PAYLOAD TOO LARGE (413)
                    .entity("{\"error\":\"file exceeds 10MB limit\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            LOG.warn("Failed to fetch {} (ref={}) from GitLab: {}",
                    path, ref, e.getMessage());
            com.gitlab.sync.confluence.errors.ErrorJournal.record(
                    "rest:attachments", "path=" + path + " connection=" + connectionId + " ref=" + ref, e);
            return Response.status(502) // BAD GATEWAY
                    .entity("{\"error\":\"failed to fetch from GitLab\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (bytes.length > MAX_FILE_SIZE) {
            return Response.status(413) // PAYLOAD TOO LARGE (413)
                    .entity("{\"error\":\"file exceeds 10MB limit\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String contentType = guessContentType(path);
        String filename = basename(path);

        // Constrain Content-Disposition to a safe filename (avoid header
        // injection via CRLF in the original filename).
        String safeFilename = filename.replaceAll("[\\r\\n\"]", "_");

        // byte[] (NOT a stream) as the JAX-RS entity: streams with a
        // Content-Type that has a registered provider (e.g. application/json)
        // get handoff-serialized by the JSON provider and blow up with
        // JsonMappingException("No serializer found for ByteArrayInputStream").
        // byte[] is a standard entity type and is written verbatim.
        // Requirement R1: .html files are served inline so a sandboxed
        // iframe renders them as a page; everything else stays a download.
        boolean inline = "text/html".equals(contentType);
        Response response = Response.ok(bytes)
                .type(contentType)
                .header(HeaderNames.CONTENT_DISPOSITION,
                        (inline ? "inline; " : "attachment; ")
                                + "filename=\"" + safeFilename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .build();
        return response;
    }

    /**
     * True when at least one git-sync macro on the page (a) points at the
     * given connection (by id or name — the macro parameters commonly carry
     * the human-readable name) and (b) whose rendering links the requested
     * {@code (path, ref)} attachment. The check reuses the exact pipeline
     * the macro uses (fetch → convert → collect attachment targets), so the
     * answer matches what a page reader actually sees.
     */
    private static boolean pageReferencesAttachment(String body,
                                                    GitLabConnection conn,
                                                    String path,
                                                    String ref,
                                                    String pageId) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String wanted = path + "\u0000" + ref;
        Matcher blocks = MACRO_BLOCK.matcher(body);
        while (blocks.find()) {
            Map<String, String> params = new HashMap<>();
            Matcher p = MACRO_PARAM.matcher(blocks.group(1));
            while (p.find()) {
                params.put(p.group(1), p.group(2).trim());
            }
            String c = params.get("connection");
            if (c == null || (!c.equals(conn.getId()) && !c.equals(conn.getName()))) {
                continue;
            }
            String docPath = params.get("path");
            String docRef = params.getOrDefault("ref", "main");
            if (docPath == null || docPath.isEmpty()) {
                continue;
            }
            // The macro's own source file counts as referenced: for an
            // .html file (requirement R1) the macro's output IS the iframe
            // whose src points at this very file, and nothing else on the
            // page links it.
            if (path.equals(docPath) && ref.equals(docRef)) {
                return true;
            }
            try {
                GitLabClient client = new GitLabClient(conn);
                String markdown = client.fetchRawFile(docPath, docRef);
                String rendered = MarkdownToStorageConverter.convert(
                        markdown, KrokiRenderer.getKrokiBaseUrl());
                Set<String> keys = GitLabLinkResolver.referencedAttachmentKeys(
                        rendered, docPath, conn.getBaseUrl(), conn.getProjectPath(),
                        docRef, conn.getId());
                if (keys.contains(wanted)) {
                    return true;
                }
            } catch (Exception e) {
                LOG.debug("page-reference check: doc {}@{} unreadable: {}",
                        docPath, docRef, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Storage body of the page. {@code getBodyAsString()} is a concrete
     * method on the imported {@code ContentEntityObject} ABSTRACT CLASS — a
     * direct call from the bundle fails OSGi linkage (same class of failure
     * the macro hit with {@code getIdAsString}), so go through the concrete
     * class. The parameter is a Page (class, not interface) so javac emits
     * invokevirtual for getClass() — an interface stub would emit
     * invokeinterface and die with IncompatibleClassChangeError at runtime.
     */
    private static String pageBodyAsString(Page page) {
        try {
            Object v = page.getClass().getMethod("getBodyAsString").invoke(page);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            LOG.warn("getBodyAsString reflection failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Fresh store per request: a cached instance would go stale when the
     * admin panel creates or edits a connection after the first download.
     */
    private ConnectionStore getConnectionStore() {
        return new ConnectionStore(GitsyncPaths.secureDir());
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response forbidden(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"" + message + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    static String guessContentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "application/octet-stream";
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    static String basename(String path) {
        if (path == null || path.isEmpty()) {
            return "file";
        }
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return name.isEmpty() ? "file" : name;
    }
}
