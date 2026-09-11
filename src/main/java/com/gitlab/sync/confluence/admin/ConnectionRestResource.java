package com.gitlab.sync.confluence.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.gitlab.sync.confluence.GitsyncPaths;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import com.gitlab.sync.confluence.security.PatEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;



import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * JAX-RS REST resource for managing GitLab connections.
 *
 * Registered at /rest/gitsync/1.0/connections by atlassian-plugin.xml.
 *
 * All responses are JSON. The PAT is never returned in any response —
 * only the encrypted reference (or nothing at all). Responses contain
 * a safe subset: id, name, baseUrl, projectId, createdAt, updatedAt.
 *
 * Write operations (POST, PUT, DELETE) require the "administrators" group.
 * Read operations (GET list, GET single) are available to any authenticated user.
 */
@Path("/connections")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectionRestResource {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionRestResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private volatile ConnectionStore store;
    private volatile byte[] encryptionKey;

    /**
     * Lazy-init the ConnectionStore. Called on first request.
     * ConnectionStore is not a Confluence-managed component, so we construct
     * it here using Confluence's secure directory.
     */
    private ConnectionStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    store = new ConnectionStore(GitsyncPaths.secureDir());
                    encryptionKey = getOrCreateEncryptionKey();
                    LOG.info("ConnectionStore initialized");
                }
            }
        }
        return store;
    }

    private byte[] getEncryptionKey() {
        if (encryptionKey == null) {
            synchronized (this) {
                if (encryptionKey == null) {
                    encryptionKey = getOrCreateEncryptionKey();
                }
            }
        }
        return encryptionKey;
    }

    /**
     * Resolve or generate the AES key. Single source of truth is
     * {@link PatEncryptor#resolveOrCreateKey()} — the same method the
     * decryption path uses, so keys never diverge between write and read.
     * Resolution order: {@code -Dgitsync.encryption.key} → persisted
     * {@code {secure}/gitsync/key.bin} → generate-and-persist.
     */
    private byte[] getOrCreateEncryptionKey() {
        return PatEncryptor.resolveOrCreateKey();
    }

    // ----------------------------------------------------------------
    //  GET /connections — list all (safe fields only)
    // ----------------------------------------------------------------

    @GET
    @RolesAllowed("users")
    public Response listConnections() {
        List<ObjectNode> list = getStore().getAll().stream()
                .map(ConnectionRestResource::toSafeJson)
                .collect(java.util.stream.Collectors.toList());
        return jsonResponse(Status.OK, list);
    }

    // ----------------------------------------------------------------
    //  GET /connections/{id}
    // ----------------------------------------------------------------

    @GET
    @Path("/{id}")
    @RolesAllowed("users")
    public Response getConnection(@PathParam("id") String id) {
        Optional<GitLabConnection> conn = getStore().getById(id);
        return conn
                .map(c -> jsonResponse(Status.OK, toSafeJson(c)))
                .orElseGet(() -> notFound(id));
    }

    // ----------------------------------------------------------------
    //  POST /connections — create (admin only)
    // ----------------------------------------------------------------

    @POST
    @RolesAllowed("administrators")
    public Response createConnection(String body) {
        // Parse
        CreateUpdateRequest req;
        try {
            req = MAPPER.readValue(body, CreateUpdateRequest.class);
        } catch (Exception e) {
            return badRequest("Неверный JSON: " + e.getMessage());
        }

        // Validate
        String validationError = validate(req);
        if (validationError != null) {
            return badRequest(validationError);
        }
        // pat is mandatory on create (on update an empty pat keeps the old one)
        if (req.pat == null || req.pat.isBlank()) {
            return badRequest("Поле 'pat' обязательно");
        }

        // Encrypt the PAT
        String encryptedPat = PatEncryptor.encrypt(req.pat, getEncryptionKey());

        // Generate ID
        String id = java.util.UUID.randomUUID().toString();

        GitLabConnection conn = new GitLabConnection(id, req.name, req.baseUrl, req.projectId, encryptedPat);
        // Best-effort: resolve the project path now so rendered pages get
        // canonical GitLab web URLs without a per-render API call.
        try {
            conn.setProjectPath(new GitLabClient(conn, getEncryptionKey()).fetchProjectPath());
        } catch (Exception e) {
            LOG.warn("Could not resolve project path at create time: {}", e.getMessage());
        }
        getStore().add(conn);
        LOG.info("Created connection id={} name={} projectPath={}", id, req.name, conn.getProjectPath());

        return jsonResponse(Status.CREATED, toSafeJson(conn));
    }

    // ----------------------------------------------------------------
    //  PUT /connections/{id} — update (admin only)
    // ----------------------------------------------------------------

    @PUT
    @Path("/{id}")
    @RolesAllowed("administrators")
    public Response updateConnection(@PathParam("id") String id, String body) {
        CreateUpdateRequest req;
        try {
            req = MAPPER.readValue(body, CreateUpdateRequest.class);
        } catch (Exception e) {
            return badRequest("Неверный JSON: " + e.getMessage());
        }

        String validationError = validate(req);
        if (validationError != null) {
            return badRequest(validationError);
        }

        Optional<GitLabConnection> existing = getStore().getById(id);
        if (existing.isEmpty()) {
            return notFound(id);
        }

        String encryptedPat;
        if (req.pat != null && !req.pat.isBlank()) {
            encryptedPat = PatEncryptor.encrypt(req.pat.trim(), getEncryptionKey());
        } else {
            // If no new PAT provided, keep the old one
            encryptedPat = existing.get().getEncryptedPat();
        }

        Optional<GitLabConnection> updated =
                getStore().update(id, req.name, req.baseUrl, req.projectId, encryptedPat);
        if (updated.isEmpty()) {
            return notFound(id);
        }

        // The cached project path belongs to the endpoint it was resolved
        // from: when baseUrl or projectId change it is stale and must be
        // re-resolved (best-effort; on failure clear it so renders fall
        // back to a live lookup instead of linking to the wrong project).
        GitLabConnection before = existing.get();
        GitLabConnection after = updated.get();
        boolean endpointChanged = !java.util.Objects.equals(
                        before.getBaseUrl(), after.getBaseUrl())
                || before.getProjectId() != after.getProjectId();
        if (endpointChanged) {
            try {
                String pp = new GitLabClient(after, getEncryptionKey()).fetchProjectPath();
                getStore().setProjectPath(id, pp == null ? "" : pp);
            } catch (Exception e) {
                LOG.warn("Could not re-resolve project path at update time (id={}): {}",
                        id, e.getMessage());
                getStore().setProjectPath(id, "");
            }
        }

        return jsonResponse(Status.OK, toSafeJson(after));
    }

    // ----------------------------------------------------------------
    //  DELETE /connections/{id} — delete (admin only)
    // ----------------------------------------------------------------

    @DELETE
    @Path("/{id}")
    @RolesAllowed("administrators")
    public Response deleteConnection(@PathParam("id") String id) {
        boolean deleted = getStore().delete(id);
        if (deleted) {
            LOG.info("Deleted connection id={}", id);
            return javax.ws.rs.core.Response.noContent().build();
        }
        return notFound(id);
    }

    // ----------------------------------------------------------------
    //  POST /connections/{id}/test — validate PAT (admin only)
    // ----------------------------------------------------------------

    @POST
    @Path("/{id}/test")
    @RolesAllowed("administrators")
    public Response testConnection(@PathParam("id") String id) {
        Optional<GitLabConnection> conn = getStore().getById(id);
        if (conn.isEmpty()) {
            return notFound(id);
        }

        GitLabConnection c = conn.get();
        try {
            GitLabClient client = new GitLabClient(c, getEncryptionKey());
            boolean valid = client.validateConnection();
            ObjectNode result = MAPPER.createObjectNode();
            result.put("valid", valid);
            result.put("message", valid
                    ? "Соединение успешно проверено"
                    : "Проверка не удалась: неверный PAT или проект недоступен");

            if (valid) {
                // Try to fetch the project to double-check (and persist its
                // path_with_namespace for canonical link URLs).
                try {
                    JsonNode projNode = client.fetchProject();
                    if (projNode != null) {
                        result.put("projectName",
                                projNode.has("name") ? projNode.get("name").asText() : "неизвестно");
                        JsonNode pwn = projNode.get("path_with_namespace");
                        if (pwn != null && !pwn.asText().isEmpty()) {
                            getStore().setProjectPath(id, pwn.asText());
                            result.put("projectPath", pwn.asText());
                        }
                    }
                } catch (Exception projEx) {
                    result.put("projectName", "проект недоступен: " + projEx.getMessage());
                    result.put("projectError", true);
                }

                // Write capability: native GitLab comments (FR09 git mode)
                // need Developer (access_level >= 30) on the project.
                try {
                    int access = client.projectAccessLevel();
                    result.put("accessLevel", access);
                    result.put("writeAccess", access >= 30);
                    if (access < 30) {
                        result.put("writeError", access >= 0
                                ? "Пользователю PAT недостаточно прав для комментирования (нужен Developer, 30+)"
                                : "Не удалось определить права доступа к проекту");
                    }
                } catch (Exception ex) {
                    result.put("writeAccess", false);
                }
            }

            return jsonResponse(Status.OK, result);

        } catch (Exception e) {
            LOG.warn("Test connection failed for id={}: {}", id, e.getMessage());
            ObjectNode result = MAPPER.createObjectNode();
            result.put("valid", false);
            result.put("writeAccess", false);
            result.put("message", "Ошибка при проверке: " + e.getMessage());
            return jsonResponse(Status.OK, result);
        }
    }

    // ----------------------------------------------------------------
    //  POST /connections/{id}/migrate-comments — one-shot migration of
    //  legacy local line comments into native GitLab commit discussions
    //  (admin only, idempotent)
    // ----------------------------------------------------------------

    @POST
    @Path("/{id}/migrate-comments")
    @RolesAllowed("administrators")
    public Response migrateComments(@PathParam("id") String id) {
        Optional<GitLabConnection> conn = getStore().getById(id);
        if (conn.isEmpty()) {
            return notFound(id);
        }
        try {
            GitLabClient client = new GitLabClient(conn.get(), getEncryptionKey());
            com.gitlab.sync.confluence.comments.git.CommentMigrator migrator =
                    new com.gitlab.sync.confluence.comments.git.CommentMigrator();
            ObjectNode summary = migrator.migrate(conn.get(), client);
            return jsonResponse(Status.OK, summary);
        } catch (Exception e) {
            LOG.warn("Migrate comments failed for id={}: {}", id, e.getMessage(), e);
            return jsonResponse(Status.OK, errorBody(
                    "Ошибка миграции: " + e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private static ObjectNode toSafeJson(GitLabConnection c) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", c.getId());
        node.put("name", c.getName());
        node.put("baseUrl", c.getBaseUrl());
        node.put("projectId", c.getProjectId());
        node.put("createdAt", c.getCreatedAt());
        node.put("updatedAt", c.getUpdatedAt());
        return node;
    }

    private static String validate(CreateUpdateRequest req) {
        if (req.name == null || req.name.isBlank()) {
            return "Поле 'name' обязательно";
        }
        if (req.baseUrl == null || req.baseUrl.isBlank()) {
            return "Поле 'baseUrl' обязательно";
        }
        try {
            new URI(req.baseUrl.trim());
        } catch (Exception e) {
            return "Поле 'baseUrl' должно быть корректным URL";
        }
        if (req.projectId <= 0) {
            return "Поле 'projectId' должно быть положительным числом";
        }
        return null;
    }

    private static Response badRequest(String message) {
        return jsonResponse(Status.BAD_REQUEST, errorBody(message));
    }

    private static Response notFound(String id) {
        return jsonResponse(Status.NOT_FOUND, errorBody("Соединение не найдено: " + id));
    }

    private static ObjectNode errorBody(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    /**
     * Serialize the payload with the plugin's own Jackson mapper and return
     * it as a plain String entity: the DMZ's JAX-RS 1.1 container cannot
     * serialize Jackson 2.x node types (it would bean-introspect them), so
     * the JSON text is produced here and written verbatim.
     */
    private static Response jsonResponse(Status status, Object json) {
        try {
            return Response.status(status)
                    .entity(MAPPER.writeValueAsString(json))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            return Response.status(500)
                    .entity("{\"error\":\"serialization failed\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    // ----------------------------------------------------------------
    //  Request DTO
    // ----------------------------------------------------------------

    /**
     * JSON body for create and update requests.
     */
    public static class CreateUpdateRequest {
        public String name;
        public String baseUrl;
        public int projectId;
        public String pat;

        public CreateUpdateRequest() {}

        // Jackson uses field access; explicit getters/setters for clarity
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getProjectId() { return projectId; }
        public void setProjectId(int projectId) { this.projectId = projectId; }

        public String getPat() { return pat; }
        public void setPat(String pat) { this.pat = pat; }
    }
}
