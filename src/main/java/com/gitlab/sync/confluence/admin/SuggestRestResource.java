package com.gitlab.sync.confluence.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.gitlab.sync.confluence.GitsyncPaths;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabConnection;
import com.gitlab.sync.confluence.security.PatEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Autocomplete (suggest) endpoints for the git-sync macro dialog fields.
 *
 * Registered at /rest/gitsync/1.0/suggest by atlassian-plugin.xml.
 *
 * <ul>
 *   <li>{@code GET /suggest/connection?q=} — stored connection names
 *       (case-insensitive substring match; empty q returns all).</li>
 *   <li>{@code GET /suggest/ref?connection=&q=} — GitLab branches + tags
 *       for the connection's project (GitLab-side search).</li>
 *   <li>{@code GET /suggest/path?connection=&ref=&q=} — repository file
 *       paths by per-segment prefix: the input is split at the last
 *       '/', that directory is listed, and its entries are filtered by
 *       the remaining prefix. Directories come back with a trailing
 *       '/' so the user can keep typing into them.</li>
 * </ul>
 *
 * All endpoints are read-only, available to any authenticated user, and
 * never expose the PAT. On GitLab errors a 502 with the flattened
 * GitLab message is returned (the PAT value is not part of it).
 */
@Path("/suggest")
@Produces(MediaType.APPLICATION_JSON)
public class SuggestRestResource {

    private static final Logger LOG = LoggerFactory.getLogger(SuggestRestResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The macro's default ref (kept in sync with GitSyncMacro.DEFAULT_REF). */
    private static final String DEFAULT_REF = "main";

    /** Max candidates per field returned to the dialog. */
    private static final int PER_PAGE = 20;

    /** Tree listing page size (100 lets the client see truncation). */
    private static final int TREE_PAGE = 100;

    private volatile ConnectionStore store;
    private volatile byte[] encryptionKey;

    private ConnectionStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    store = new ConnectionStore(GitsyncPaths.secureDir());
                    encryptionKey = PatEncryptor.resolveOrCreateKey();
                    LOG.info("SuggestRestResource: ConnectionStore initialized");
                }
            }
        }
        return store;
    }

    private byte[] getKey() {
        if (encryptionKey == null) {
            synchronized (this) {
                if (encryptionKey == null) {
                    encryptionKey = PatEncryptor.resolveOrCreateKey();
                }
            }
        }
        return encryptionKey;
    }

    // ----------------------------------------------------------------
    //  GET /suggest/connection
    // ----------------------------------------------------------------

    @GET
    @Path("/connection")
    @RolesAllowed("users")
    public Response suggestConnections(@QueryParam("q") String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (GitLabConnection c : getStore().getAll()) {
            if (needle.isEmpty()
                    || c.getName().toLowerCase(Locale.ROOT).contains(needle)
                    || c.getId().toLowerCase(Locale.ROOT).startsWith(needle)) {
                names.add(c.getName());
            }
        }
        ArrayNode items = MAPPER.createArrayNode();
        names.forEach(items::add);
        return jsonResponse(Status.OK, items);
    }

    // ----------------------------------------------------------------
    //  GET /suggest/ref
    // ----------------------------------------------------------------

    @GET
    @Path("/ref")
    @RolesAllowed("users")
    public Response suggestRefs(@QueryParam("connection") String connection,
                                @QueryParam("q") String q) {
        Optional<GitLabConnection> conn = resolveConnection(connection);
        if (conn.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, errorBody("Соединение не найдено: " + connection));
        }
        try {
            GitLabClient client = new GitLabClient(conn.get(), getKey());
            ObjectNode out = MAPPER.createObjectNode();
            out.set("branches", namesOf(client.listBranches(q, PER_PAGE), "name"));
            out.set("tags", namesOf(client.listTags(q, PER_PAGE), "name"));
            return jsonResponse(Status.OK, out);
        } catch (GitLabClient.GitLabApiException e) {
            return gitLabError(e);
        } catch (Exception e) {
            LOG.warn("suggest/ref failed for connection={}: {}", connection, e.getMessage());
            return jsonResponse(502, errorBody("Ошибка запроса к GitLab: " + e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    //  GET /suggest/path
    // ----------------------------------------------------------------

    @GET
    @Path("/path")
    @RolesAllowed("users")
    public Response suggestPaths(@QueryParam("connection") String connection,
                                 @QueryParam("ref") String ref,
                                 @QueryParam("q") String q) {
        Optional<GitLabConnection> conn = resolveConnection(connection);
        if (conn.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, errorBody("Соединение не найдено: " + connection));
        }
        String query = q == null ? "" : q.trim();
        String useRef = (ref == null || ref.isBlank()) ? DEFAULT_REF : ref.trim();

        // Split at the last '/': list that directory, filter by the rest.
        String dir;
        String prefix;
        int slash = query.lastIndexOf('/');
        if (slash < 0) {
            dir = "";
            prefix = query;
        } else {
            dir = query.substring(0, slash);
            prefix = query.substring(slash + 1);
        }

        try {
            GitLabClient client = new GitLabClient(conn.get(), getKey());
            JsonNode entries = client.listTree(dir, useRef, TREE_PAGE);
            boolean truncated = entries != null && entries.isArray() && entries.size() >= TREE_PAGE;

            ArrayNode items = MAPPER.createArrayNode();
            if (entries != null && entries.isArray()) {
                for (JsonNode e : entries) {
                    String name = e.path("name").asText("");
                    if (name.isEmpty()) {
                        continue;
                    }
                    if (!prefix.isEmpty()
                            && !name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String type = e.path("type").asText("");
                    items.add(dir.isEmpty() ? name + (type.equals("tree") ? "/" : "")
                                            : dir + "/" + name + (type.equals("tree") ? "/" : ""));
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.set("items", items);
            out.put("truncated", truncated);
            return jsonResponse(Status.OK, out);
        } catch (GitLabClient.GitLabApiException e) {
            return gitLabError(e);
        } catch (Exception e) {
            LOG.warn("suggest/path failed for connection={}: {}", connection, e.getMessage());
            return jsonResponse(502, errorBody("Ошибка запроса к GitLab: " + e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /** Resolve a connection by id first, then by name (the macro accepts both). */
    private Optional<GitLabConnection> resolveConnection(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String k = key.trim();
        Optional<GitLabConnection> byId = getStore().getById(k);
        if (byId.isPresent()) {
            return byId;
        }
        return getStore().getAll().stream()
                .filter(c -> c.getName().equalsIgnoreCase(k))
                .findFirst();
    }

    /** Flatten a GitLab JSON array of {name: …} objects into ["…", …]. */
    private static ArrayNode namesOf(JsonNode arr, String field) {
        ArrayNode out = MAPPER.createArrayNode();
        if (arr != null && arr.isArray()) {
            arr.forEach(e -> out.add(e.path(field).asText("")));
        }
        return out;
    }

    private static Response gitLabError(GitLabClient.GitLabApiException e) {
        int code = e.isAccessDenied() ? 401 : 502;
        return jsonResponse(code, errorBody(e.getMessage()));
    }

    private static ObjectNode errorBody(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    /**
     * Serialize with the plugin's own Jackson mapper and return a plain
     * String entity — the DMZ's JAX-RS 1.1 container cannot serialize
     * Jackson 2.x node types (same constraint as ConnectionRestResource).
     * The int overload covers statuses missing from the JAX-RS 1.1
     * Response.Status enum (e.g. 502 BAD_GATEWAY, added in JAX-RS 2.0).
     */
    private static Response jsonResponse(Status status, Object json) {
        return jsonResponse(status.getStatusCode(), json);
    }

    private static Response jsonResponse(int status, Object json) {
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
}
