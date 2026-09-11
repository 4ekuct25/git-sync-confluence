package com.gitlab.sync.confluence.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.sync.confluence.settings.SettingsStore;
import com.gitlab.sync.confluence.settings.SettingsStore.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

/**
 * JAX-RS resource for the plugin's runtime settings — the "Настройки" screen
 * of the admin panel (FR02) and the configurable limits behind NFR01
 * (timeouts) and FR05 (maximum markdown file size).
 *
 * <p>Registered at {@code /rest/gitsync/1.0/settings}. Both GET and PUT
 * require the "administrators" group, mirroring the write operations of
 * {@link ConnectionRestResource}. PUT accepts a JSON object in which every
 * field is optional — omitted fields keep their current value:</p>
 * <pre>
 *   { "gitTimeoutMs": 15000,
 *     "renderTimeoutMs": 10000,
 *     "maxMarkdownFileSizeBytes": 5242880 }
 * </pre>
 */
@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsRestResource {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsRestResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sane bounds for the PUT validation. */
    private static final long MAX_TIMEOUT_MS = 1L * 60 * 60 * 1000; // 1 hour
    private static final long MAX_FILE_SIZE_BYTES = 1L * 1024 * 1024 * 1024; // 1 GiB

    // ----------------------------------------------------------------
    //  GET /settings — current settings (admin only)
    // ----------------------------------------------------------------

    @GET
    @RolesAllowed("administrators")
    public Response getSettings() {
        return jsonResponse(Status.OK, SettingsStore.instance().current().toJson(MAPPER));
    }

    // ----------------------------------------------------------------
    //  PUT /settings — update (admin only); omitted fields keep their value
    // ----------------------------------------------------------------

    @PUT
    @RolesAllowed("administrators")
    public Response updateSettings(String body) {
        JsonNode node;
        try {
            node = body == null ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            return badRequest("Неверный JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            return badRequest("Ожидается JSON-объект с полями gitTimeoutMs / "
                    + "renderTimeoutMs / maxMarkdownFileSizeBytes");
        }

        Settings cur = SettingsStore.instance().current();
        Integer git = readPositiveInt(node, "gitTimeoutMs", cur.gitTimeoutMs, MAX_TIMEOUT_MS);
        if (git == null) {
            return badRequest("Поле 'gitTimeoutMs' должно быть положительным числом (мс)");
        }
        Integer render = readPositiveInt(node, "renderTimeoutMs", cur.renderTimeoutMs, MAX_TIMEOUT_MS);
        if (render == null) {
            return badRequest("Поле 'renderTimeoutMs' должно быть положительным числом (мс)");
        }
        Long maxSize = readPositiveLong(node, "maxMarkdownFileSizeBytes",
                cur.maxMarkdownFileSizeBytes, MAX_FILE_SIZE_BYTES);
        if (maxSize == null) {
            return badRequest("Поле 'maxMarkdownFileSizeBytes' должно быть положительным числом (байт)");
        }

        try {
            Settings stored = SettingsStore.instance().update(git, render, maxSize);
            LOG.info("Settings updated via REST: gitTimeoutMs={} renderTimeoutMs={} maxMarkdownFileSizeBytes={}",
                    git, render, maxSize);
            return jsonResponse(Status.OK, stored.toJson(MAPPER));
        } catch (RuntimeException e) {
            LOG.warn("Failed to persist settings: {}", e.getMessage());
            return jsonResponse(Status.INTERNAL_SERVER_ERROR,
                    errorBody("Не удалось сохранить настройки: " + e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /**
     * @return the validated value, the default when the field is absent, or
     *         {@code null} when the field is present but invalid
     */
    private static Integer readPositiveInt(JsonNode node, String field, int def, long max) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        if (!v.isIntegralNumber()) {
            return null;
        }
        long l = v.asLong();
        return (l >= 1 && l <= max) ? (int) l : null;
    }

    private static Long readPositiveLong(JsonNode node, String field, long def, long max) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        if (!v.isIntegralNumber()) {
            return null;
        }
        long l = v.asLong();
        return (l >= 1 && l <= max) ? l : null;
    }

    private static Response badRequest(String message) {
        return jsonResponse(Status.BAD_REQUEST, errorBody(message));
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
}
