package com.gitlab.sync.confluence.comments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.sync.confluence.GitsyncPaths;
import com.gitlab.sync.confluence.GitsyncServices;
import com.gitlab.sync.confluence.comments.git.GitLabCommentStore;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import com.gitlab.sync.confluence.mail.CommentNotifier;
import com.gitlab.sync.confluence.macro.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderNames;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * REST API for line comments:
 *
 * <pre>
 * GET    /rest/gitsync/1.0/comments?pageId={id}   — list comments for a page
 * POST   /rest/gitsync/1.0/comments               — create a comment
 * PUT    /rest/gitsync/1.0/comments/{id}          — update body and/or resolved flag
 * DELETE /rest/gitsync/1.0/comments/{id}          — delete a comment
 * </pre>
 */
@Path("/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommentRestResource {

    private static final Logger LOG = LoggerFactory.getLogger(CommentRestResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Context
    private SecurityContext securityContext;

    private CommentStore store;

    @PostConstruct
    public void init() {
        this.store = CommentStore.instance();
        LOG.info("CommentRestResource initialized, data dir: {}",
                GitsyncPaths.secureDir().getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // GET — list comments for a page
    // ------------------------------------------------------------------

    @GET
    @RolesAllowed("users")
    public Response list(@QueryParam("pageId") long pageId,
                         @QueryParam("connectionId") String connectionId,
                         @QueryParam("path") String path,
                         @QueryParam("ref") String ref,
                         @Context SecurityContext security) {
        Response denied = pageViewGate(pageId, security);
        if (denied != null) {
            return denied;
        }
        try {
            GitLabCommentStore git = gitStore(connectionId, path, ref);
            List<LineComment> comments = git != null
                    ? git.list(pageId)
                    : store.getAll(pageId);
            return Response.ok(MAPPER.writeValueAsString(comments))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (GitLabCommentStore.GitCommentException e) {
            return gitError(e, "Не удалось загрузить комментарии");
        } catch (Exception e) {
            return Response.status(500)
                    .entity(errorJson("Failed to list comments"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // POST — create a comment
    // ------------------------------------------------------------------

    @POST
    @RolesAllowed("users")
    public Response create(String body,
                           @HeaderParam(HeaderNames.AUTHORIZATION) String authorization,
                           @Context SecurityContext security) {
        try {
            JsonNode json = MAPPER.readTree(body);

            long pageId = json.path("pageId").asLong(0);
            if (pageId == 0) {
                return badRequest("pageId is required");
            }
            Response denied = pageViewGate(pageId, security);
            if (denied != null) {
                return denied;
            }
            int lineAnchor = json.path("lineAnchor").asInt(-1);
            if (lineAnchor < 0) {
                return badRequest("lineAnchor is required (0-based block index)");
            }
            String lineText = json.path("lineText").asText("");
            String commentBody = json.path("body").asText("");
            if (commentBody.isBlank()) {
                return badRequest("body is required");
            }
            String parentId = json.path("parentId").asText("");
            if (parentId.isEmpty()) {
                parentId = null;
            }

            // Git-native routing: the page embeds a git-sync macro and the
            // frontend passed its connection/path/ref → the comment becomes
            // a native GitLab commit discussion.
            String connectionId = json.path("connectionId").asText("");
            String srcPath = json.path("path").asText("");
            String srcRef = json.path("ref").asText("main");
            GitLabCommentStore git = gitStore(connectionId, srcPath, srcRef);

            String author = currentUserName(authorization);
            LineComment comment;
            if (git != null) {
                if (parentId != null && !GitLabCommentStore.isCompositeId(parentId)) {
                    return badRequest("parentId not found");
                }
                comment = git.add(lineAnchor, author, commentBody, parentId, lineText);
                comment.setPageId(pageId);
            } else {
                if (parentId != null) {
                    if (store.getById(parentId).isEmpty()) {
                        return badRequest("parentId not found");
                    } else if (store.getById(parentId).get().getPageId() != pageId) {
                        return badRequest("parentId belongs to another page");
                    }
                }
                comment = new LineComment(pageId, lineAnchor, lineText, author, commentBody);
                comment.setParentId(parentId);
                store.add(comment);
            }
            CommentNotifier.notifyCommentCreated(String.valueOf(pageId), author, commentBody);

            return Response.created(
                    javax.ws.rs.core.UriBuilder
                            .fromPath("/rest/gitsync/1.0/comments/" + comment.getId())
                            .build())
                    .entity(MAPPER.writeValueAsString(comment))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (GitLabCommentStore.GitCommentException e) {
            return gitError(e, "Не удалось создать комментарий");
        } catch (Exception e) {
            LOG.error("Failed to create comment", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("Failed to create comment: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // PUT — update body and/or resolved flag
    // ------------------------------------------------------------------

    @PUT
    @Path("/{id}")
    @RolesAllowed("users")
    public Response update(@PathParam("id") String id, String body,
                           @HeaderParam(HeaderNames.AUTHORIZATION) String authorization) {
        try {
            JsonNode json = MAPPER.readTree(body);
            String newBody = json.has("body") ? json.path("body").asText(null) : null;
            boolean resolved = json.path("resolved").asBoolean(false);

            // Determine if the caller explicitly set resolved
            boolean resolvedSet = json.has("resolved");
            String user = currentUserName(authorization);

            // Git-native id → the note lives in GitLab; the frontend also
            // passes the routing triple (connectionId/path/ref) in the body.
            if (GitLabCommentStore.isCompositeId(id)) {
                GitLabCommentStore git = gitStore(json.path("connectionId").asText(""),
                        json.path("path").asText(""), json.path("ref").asText(""));
                if (git == null) {
                    return badRequest("connectionId/path/ref are required for git comments");
                }
                if (!currentUserIsAdmin() && !git.authorOf(id).equals(user)) {
                    return forbidden();
                }
                boolean finalResolved = resolvedSet ? resolved : git.getById(id).isResolved();
                LineComment updated = git.update(id, newBody, finalResolved, user);
                return Response.ok(MAPPER.writeValueAsString(updated))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            var existing = store.getById(id);
            if (existing.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(errorJson("Comment not found: " + id))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            if (!canModify(existing.get(), authorization)) {
                return forbidden();
            }

            boolean finalResolved = resolvedSet ? resolved : existing.get().isResolved();
            store.update(id, newBody, finalResolved);

            var updated = store.getById(id);
            return Response.ok(MAPPER.writeValueAsString(updated.orElse(null)))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (GitLabCommentStore.GitCommentException e) {
            return gitError(e, "Не удалось обновить комментарий");
        } catch (Exception e) {
            LOG.error("Failed to update comment {}", id, e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("Failed to update comment: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // DELETE — remove a comment
    // ------------------------------------------------------------------

    @DELETE
    @Path("/{id}")
    @RolesAllowed("users")
    public Response delete(@PathParam("id") String id, String body,
                           @HeaderParam(HeaderNames.AUTHORIZATION) String authorization,
                           @Context SecurityContext security) {
        try {
            // Git-native id → delete the note (or the whole thread) in GitLab.
            if (GitLabCommentStore.isCompositeId(id)) {
                JsonNode json = (body == null || body.isBlank())
                        ? MAPPER.createObjectNode() : MAPPER.readTree(body);
                String user = currentUserName(authorization);
                GitLabCommentStore git = gitStore(json.path("connectionId").asText(""),
                        json.path("path").asText(""), json.path("ref").asText(""));
                if (git == null) {
                    return badRequest("connectionId/path/ref are required for git comments");
                }
                if (!currentUserIsAdmin() && !git.authorOf(id).equals(user)) {
                    return forbidden();
                }
                boolean removed = git.delete(id);
                if (!removed) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(errorJson("Comment not found: " + id))
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }
                long pageId = json.path("pageId").asLong(0);
                if (pageId > 0) {
                    CommentNotifier.notifyCommentDeleted(String.valueOf(pageId), user, "");
                }
                return Response.noContent().build();
            }
        } catch (GitLabCommentStore.GitCommentException e) {
            return gitError(e, "Не удалось удалить комментарий");
        } catch (Exception e) {
            LOG.error("Failed to delete git comment {}", id, e);
            return Response.status(500)
                    .entity(errorJson("Failed to delete comment: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        Optional<LineComment> existing = store.getById(id);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorJson("Comment not found: " + id))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        Response denied = pageViewGate(existing.get().getPageId(), security);
        if (denied != null) {
            return denied;
        }
        if (!canModify(existing.get(), authorization)) {
            return forbidden();
        }
        boolean removed = store.delete(id);
        if (!removed) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorJson("Comment not found: " + id))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        CommentNotifier.notifyCommentDeleted(String.valueOf(existing.get().getPageId()),
                currentUserName(authorization), existing.get().getBody());
        return Response.noContent().build();
    }

    /**
     * Only the comment author or an administrator may modify or delete a
     * comment. Returns 403 otherwise.
     */
    private boolean canModify(LineComment comment, String authorization) {
        if (currentUserIsAdmin()) {
            return true;
        }
        String author = comment.getAuthor();
        String user = currentUserName(authorization);
        return author != null && !author.isBlank() && author.equals(user);
    }

    private Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(errorJson("Only the comment author or an administrator "
                        + "can modify this comment"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Reflective role check — the DMZ SecurityContext implementation may be
     * loaded by a different classloader, so a direct isUserInRole call can
     * throw NoSuchMethodError (same reason currentUserName reflects).
     */
    private boolean currentUserIsAdmin() {
        if (securityContext == null) {
            return false;
        }
        try {
            for (Class<?> cc = securityContext.getClass(); cc != null;
                 cc = cc.getSuperclass()) {
                try {
                    java.lang.reflect.Method m =
                            cc.getMethod("isUserInRole", String.class);
                    Object r = m.invoke(securityContext, "administrators");
                    if (r instanceof Boolean) {
                        return (Boolean) r;
                    }
                } catch (NoSuchMethodException ignored) {
                    // keep walking the hierarchy
                }
            }
        } catch (Throwable t) {
            LOG.warn("SecurityContext.isUserInRole reflection failed: {}", t.toString());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String currentUserName(String authorization) {
        // The DMZ's SecurityContext implementation may implement an interface
        // loaded by a different classloader than the one this bundle was
        // compiled against, so a direct call can fail with NoSuchMethodError.
        // Resolve getPrincipal() reflectively on the runtime class instead.
        if (securityContext != null) {
            try {
                Class<?> c = securityContext.getClass();
                java.lang.reflect.Method m = null;
                for (String mn : new String[]{"getUserPrincipal", "getPrincipal"}) {
                    Class<?> cc = c;
                    while (cc != null && m == null) {
                        try {
                            m = cc.getMethod(mn);
                        } catch (NoSuchMethodException e) {
                            cc = cc.getSuperclass();
                        }
                    }
                }
                if (m == null) {
                    LOG.warn("SecurityContext impl {} exposes no user-principal method",
                            securityContext.getClass().getName());
                } else {
                    Object p = m.invoke(securityContext);
                    if (p instanceof java.security.Principal) {
                        String n = ((java.security.Principal) p).getName();
                        if (n != null && !n.isBlank()) {
                            return n;
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.warn("SecurityContext.getPrincipal() reflection failed: {}", t.toString());
            }
        }
        // Fallback: Basic auth header (API clients without a SecurityContext principal).
        // JAX-RS 1.1 UriInfo has no header accessors, so the header arrives as a
        // @HeaderParam instead of being read off the UriInfo.
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(
                        java.util.Base64.getDecoder().decode(authorization.substring(6).trim()),
                        java.nio.charset.StandardCharsets.UTF_8);
                int at = decoded.indexOf(':');
                if (at > 0) {
                    return decoded.substring(0, at);
                }
            } catch (IllegalArgumentException ignored) {
                // malformed Base64 — fall through to "unknown"
            }
        }
        return "unknown";
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorJson(msg))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static String errorJson(String msg) {
        try {
            return MAPPER.writeValueAsString(
                    java.util.Map.of("error", msg));
        } catch (Exception e) {
            return "{\"error\":\"" + msg + "\"}";
        }
    }

    /**
     * Build the GitLab-backed comment store for a git-sync page, or null when
     * the caller did not pass the routing triple (legacy local comment).
     * The connection is looked up by id first, then by name — the same
     * pattern as the git-sync macro, which accepts both.
     *
     * @throws GitLabCommentStore.GitCommentException when the connection
     *         is not found
     */
    private GitLabCommentStore gitStore(String connectionId, String path, String ref) {
        if (connectionId == null || connectionId.isBlank()
                || path == null || path.isBlank()) {
            return null;
        }
        ConnectionStore conns = PluginContext.getStore();
        Optional<GitLabConnection> found = conns.getById(connectionId.trim());
        if (found.isEmpty()) {
            for (GitLabConnection c : conns.getAll()) {
                if (connectionId.trim().equalsIgnoreCase(c.getName())) {
                    found = Optional.of(c);
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            throw new GitLabCommentStore.GitCommentException(
                    "Соединение не найдено: " + connectionId);
        }
        String useRef = (ref == null || ref.isBlank()) ? "main" : ref.trim();
        return new GitLabCommentStore(PluginContext.newClient(found.get()),
                found.get().getId(), path.trim(), useRef);
    }

    /** Uniform 502 for GitLab-side failures; the message is UI-safe. */
    private static Response gitError(GitLabCommentStore.GitCommentException e, String fallback) {
        String msg = e.getMessage();
        return Response.status(502)
                .entity(errorJson(msg != null && !msg.isBlank() ? msg : fallback))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
    /**
     * Page-scoped access gate — the same model as the attachment proxy:
     * reading or writing a page's comments requires VIEW on the page, so a
     * logged-in user without access cannot read comment threads of pages
     * they cannot open. Returns a 403/404 Response when denied, null when
     * allowed.
     */
    private Response pageViewGate(long pageId, SecurityContext security) {
        try {
            String username = (security != null && security.getUserPrincipal() != null)
                    ? security.getUserPrincipal().getName()
                    : null;
            com.atlassian.confluence.user.UserAccessor userAccessor = GitsyncServices.userAccessor();
            com.atlassian.confluence.pages.PageManager pageManager = GitsyncServices.pageManager();
            com.atlassian.confluence.core.ContentPermissionManager permissionManager =
                    GitsyncServices.contentPermissionManager();
            if (userAccessor == null || pageManager == null || permissionManager == null) {
                LOG.warn("content services unavailable; denying comments access");
                return Response.status(403)
                        .entity(errorJson("Content services unavailable"))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            com.atlassian.confluence.pages.Page page = pageManager.getPage(pageId);
            if (page == null) {
                return Response.status(404)
                        .entity(errorJson("Page not found: " + pageId))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            com.atlassian.user.User user =
                    username == null ? null : userAccessor.getUser(username);
            if (user == null
                    || !permissionManager.hasContentLevelPermission(
                            user,
                            com.atlassian.confluence.security.ContentPermission.VIEW_PERMISSION,
                            page)) {
                return Response.status(403)
                        .entity(errorJson("You do not have permission to view this page"))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            return null;
        } catch (Throwable t) {
            LOG.warn("page view gate failed: {}", t.getMessage());
            return Response.status(403)
                    .entity(errorJson("Access check failed"))
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

}
