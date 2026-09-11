package com.gitlab.sync.confluence.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin REST client for the GitLab CE API v4, built on the JDK
 * {@link HttpClient} (Confluence DC 8.5 runs on Java 17).
 *
 * <p>Atlassian SAL's HTTP client API ({@code com.atlassian.sal.api.net}) is
 * excluded from the plugin DMZ in DC 8.5, so all GitLab traffic goes through
 * the standard JDK client instead.</p>
 *
 * <p>Docker note: when Confluence itself runs in a container, a stored
 * {@code baseUrl} pointing at {@code localhost} refers to the host's
 * loopback and is unreachable from inside the container. On a connect
 * failure we retry the request via the container's default gateway, where
 * Docker forwards the host's published ports. The first variant that works
 * is remembered for subsequent requests.</p>
 */
public class GitLabClient {

    /** path_with_namespace cache: baseUrl|projectId -> path. */
    private static final java.util.Map<String, String> PROJECT_PATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final Logger LOG = LoggerFactory.getLogger(GitLabClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Remembered working gateway host per loopback baseUrl. */
    private static final Map<String, String> WORKING_HOST = new ConcurrentHashMap<>();

    private static volatile String dockerGateway;
    private static volatile boolean gatewayChecked;

    private final GitLabConnection connection;
    private final byte[] encryptionKey;
    private final HttpClient http;
    /**
     * Timeout for every GitLab request (connect + request). Read from the
     * admin-panel settings ({@code gitTimeoutMs}) at construction time; the
     * default (15 000 ms) is the value that used to be hard-coded here
     * (NFR01).
     */
    private final Duration timeout;

    public GitLabClient(GitLabConnection connection) {
        this(connection, null);
    }

    public GitLabClient(GitLabConnection connection, byte[] encryptionKey) {
        this.connection = connection;
        this.encryptionKey = encryptionKey;
        this.timeout = Duration.ofMillis(
                com.gitlab.sync.confluence.settings.SettingsStore.instance().current().gitTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetch a raw file from the repository.
     *
     * @param path file path relative to repo root
     * @param ref  branch / tag / SHA
     * @return raw file content (markdown)
     */
    public String fetchRawFile(String path, String ref) throws IOException {
        String url = rawUrl(path, ref);
        LOG.debug("GET {}", url);
        String body = doGet(url, timeout);
        if (body == null || body.isEmpty()) {
            throw new IOException("Empty response from GitLab for path=" + path);
        }
        return body;
    }

    /**
     * Fetch a raw file (binary) for attachment proxying.
     */
    /**
     * Hard cap for binary downloads: the attachment proxy serves at most
     * 10 MB. Reading stops at limit+1 bytes (the stream is not drained past
     * the cap); exceeding the cap raises {@link FileSizeLimitExceededException}
     * so the caller can answer 413 with a clear error.
     */
    static final long MAX_RAW_FETCH_BYTES = 10L * 1024 * 1024 + 1;

    /** Thrown when an upstream file is larger than the proxy's hard cap. */
    public static class FileSizeLimitExceededException extends IOException {
        public FileSizeLimitExceededException(long limit) {
            super("File exceeds the " + (limit / (1024 * 1024)) + " MB limit");
        }
    }

    /**
     * A GitLab API error with the HTTP status and a human-readable message.
     * 400 bodies (GitLab returns {@code {"message": {"field": ["…"]}}}) are
     * flattened so the caller can show them in the UI as-is.
     */
    public static class GitLabApiException extends IOException {
        private final int status;

        public GitLabApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        /** @return true for 401/403 — the PAT is missing or lacks access */
        public boolean isAccessDenied() {
            return status == 401 || status == 403;
        }
    }

    /** Flatten a GitLab error body into one readable line. */
    private static String describeError(int status, String body) {
        String detail = "";
        try {
            if (body != null && !body.isBlank()) {
                JsonNode n = MAPPER.readTree(body);
                JsonNode msg = n.get("message");
                if (msg == null) {
                    msg = n.get("error");
                }
                if (msg != null && msg.isTextual()) {
                    detail = msg.asText();
                } else if (msg != null && msg.isObject()) {
                    StringBuilder sb = new StringBuilder();
                    msg.fields().forEachRemaining(e -> {
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(e.getKey()).append(": ");
                        if (e.getValue().isArray()) {
                            e.getValue().forEach(v -> sb.append(v.asText()).append(" "));
                        } else {
                            sb.append(e.getValue().asText());
                        }
                    });
                    detail = sb.toString().trim();
                }
            }
        } catch (Exception ignored) {
            // keep the raw body below
        }
        String base;
        if (status == 401) {
            base = "GitLab: токен недействителен (401)";
        } else if (status == 403) {
            base = "GitLab: недостаточно прав (403) — проверьте scope (api) и роль (Developer+)";
        } else {
            base = "GitLab: HTTP " + status;
        }
        return detail.isEmpty() ? base : base + ": " + detail;
    }

    public byte[] fetchRawFileBytes(String path, String ref) throws IOException {
        String url = rawUrl(path, ref);
        byte[] body = doGetBytesLimited(url, Duration.ofSeconds(30), MAX_RAW_FETCH_BYTES);
        if (body.length == 0) {
            throw new IOException("Empty binary response from GitLab for path=" + path);
        }
        return body;
    }

    /**
     * Verify that the stored PAT is valid by hitting /user.
     */
    public boolean validateConnection() {
        try {
            String url = connection.getBaseUrl() + "/api/v4/user";
            String body = doGet(url, timeout);
            return body.contains("\"id\"");
        } catch (Exception e) {
            LOG.warn("Connection validation failed for {}: {}", connection.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Fetch project metadata; used by the admin "test connection" action.
     */
    public JsonNode fetchProject() throws IOException {
        String url = connection.getBaseUrl() + "/api/v4/projects/" + connection.getProjectId();
        String body = doGet(url, timeout);
        return MAPPER.readTree(body);
    }

    /**
     * Project {@code path_with_namespace} (e.g. {@code root/test-docs}) —
     * required for canonical GitLab web URLs. Fetched from the project
     * endpoint and cached per base-URL+project (the value only changes on a
     * project rename/move, which is rare and safe to ignore).
     *
     * @return the path, or {@code null} if it could not be resolved
     */
    public String fetchProjectPath() {
        String key = connection.getBaseUrl() + "|" + connection.getProjectId();
        String cached = PROJECT_PATH_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode proj = fetchProject();
            JsonNode pwn = proj.get("path_with_namespace");
            if (pwn != null && !pwn.asText().isEmpty()) {
                PROJECT_PATH_CACHE.put(key, pwn.asText());
                return pwn.asText();
            }
        } catch (Exception e) {
            LOG.warn("Could not resolve project path for project {}: {}",
                    connection.getProjectId(), e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Discussion / commit API (line comments → native GitLab notes)
    // ------------------------------------------------------------------

    /** The PAT's own user (GET /user). */
    public JsonNode getUser() throws IOException {
        return doApi("GET", connection.getBaseUrl() + "/api/v4/user", null);
    }

    /**
     * The PAT user's access level in this project (GET
     * /projects/:id/members/all/:user_id).
     *
     * @return the numeric access level (10 Guest … 50 Owner), or -1 when the
     *         user is not a project member
     */
    public int projectAccessLevel() {
        try {
            JsonNode user = getUser();
            long userId = user.path("id").asLong(0);
            if (userId == 0) {
                return -1;
            }
            JsonNode m = doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                    + "/members/all/" + userId), null);
            return m.path("access_level").asInt(-1);
        } catch (GitLabApiException e) {
            if (e.getStatus() == 404) {
                return -1; // not a member
            }
            return -1;
        } catch (IOException e) {
            LOG.warn("projectAccessLevel failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * The file's metadata at a ref (GET /repository/files/:path) — carries
     * {@code last_commit} (the newest commit that touched the file).
     */
    public JsonNode getFileMeta(String path, String ref) throws IOException {
        return doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/files/" + UriComponentEncoder.encode(path))
                + "?ref=" + UriComponentEncoder.encode(ref), null);
    }

    /**
     * Commits that touched a path, newest first (GET
     * /repository/commits?ref_name=&path=).
     */
    public List<JsonNode> getFileCommits(String path, String ref, int perPage) throws IOException {
        JsonNode arr = doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits")
                + "?ref_name=" + UriComponentEncoder.encode(ref)
                + "&path=" + UriComponentEncoder.encode(path)
                + "&per_page=" + perPage, null);
        List<JsonNode> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    /** Full commit details (GET /repository/commits/:sha) — parent_ids et al. */
    public JsonNode getCommit(String sha) throws IOException {
        return doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha), null);
    }

    /** The commit's diff entries (GET /repository/commits/:sha/diff — singular;
     *  the plural form is not a GitLab route and 404s). */
    public List<JsonNode> getCommitDiffs(String sha) throws IOException {
        JsonNode arr = doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/diff"), null);
        List<JsonNode> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    /**
     * Commit discussions (GET /repository/commits/:sha/discussions). NOTE:
     * the list entries do NOT carry {@code position} — use
     * {@link #showCommitDiscussion} for that.
     */
    public List<JsonNode> listCommitDiscussions(String sha) throws IOException {
        JsonNode arr = doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions"), null);
        List<JsonNode> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    /** A single commit discussion with full notes and position. */
    public JsonNode showCommitDiscussion(String sha, String discussionId) throws IOException {
        return doApi("GET", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions/" + discussionId), null);
    }

    /**
     * Create a note on a commit. With a non-null {@code position} (see
     * {@link #discussionPosition}) it becomes an inline diff annotation
     * (DiffNote); without it — a plain commit comment.
     *
     * @return the created discussion (carries id + notes)
     * @throws GitLabApiException 400 when the position does not match the
     *         commit's diff (line not changed/context) — the caller should
     *         fall back to a position-less note
     */
    public JsonNode createCommitDiscussion(String sha, String body, JsonNode position)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode req = MAPPER.createObjectNode();
        req.put("body", body);
        if (position != null) {
            req.set("position", position);
        }
        return doApi("POST", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions"),
                MAPPER.writeValueAsString(req));
    }

    /** Add a reply note to an existing commit discussion. */
    public JsonNode addCommitDiscussionNote(String sha, String discussionId, String body)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode req = MAPPER.createObjectNode();
        req.put("body", body);
        return doApi("POST", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions/" + discussionId + "/notes"),
                MAPPER.writeValueAsString(req));
    }

    /** Edit a commit-discussion note's body. Verified on CE 18.8: the note
     *  route is scoped to the commit AND its discussion (the unscoped
     *  {@code PUT /notes/:id} and the commit-only form both 404). */
    public JsonNode updateCommitNote(String sha, String discussionId, long noteId, String body)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode req = MAPPER.createObjectNode();
        req.put("body", body);
        return doApi("PUT", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions/" + discussionId
                + "/notes/" + noteId),
                MAPPER.writeValueAsString(req));
    }

    /** Delete a commit-discussion note (same commit+discussion-scoped route as
     *  {@link #updateCommitNote}; the unscoped {@code DELETE /notes/:id} 404s). */
    public void deleteCommitNote(String sha, String discussionId, long noteId) throws IOException {
        doApi("DELETE", apiUrl("/projects/" + connection.getProjectId()
                + "/repository/commits/" + sha + "/discussions/" + discussionId
                + "/notes/" + noteId), null);
    }

    /**
     * Build the {@code position} object for a commit DiffNote.
     *
     * <p>GitLab contract (verified on CE 18.8): {@code base_sha} and
     * {@code start_sha} are the commit's PARENT, {@code head_sha} the commit
     * itself. A position targets ONE side only — a position carrying both
     * {@code old_line} and {@code new_line} is rejected ("line_code must be a
     * valid line code") — so we anchor to the line as it exists in the NEW
     * file: {@code new_line} (1-based, a changed/context line of this
     * commit's diff) plus {@code new_line_code}, the SHA-256 of
     * {@code new_line} concatenated with the line's content (no trailing
     * newline).</p>
     *
     * @param newLine       1-based line in the new file, or null for a
     *                      position-less (plain) commit comment
     * @param newLineContent that line's content in the new file (no trailing
     *                       newline); null when the code cannot be computed
     */
    public static com.fasterxml.jackson.databind.node.ObjectNode discussionPosition(
            String baseSha, String headSha, String oldPath, String newPath,
            Integer newLine, String newLineContent) {
        com.fasterxml.jackson.databind.node.ObjectNode p = MAPPER.createObjectNode();
        p.put("base_sha", baseSha);
        p.put("start_sha", baseSha);
        p.put("head_sha", headSha);
        p.put("position_type", "text");
        p.put("old_path", oldPath);
        p.put("new_path", newPath);
        if (newLine != null) {
            p.put("new_line", newLine);
            if (newLineContent != null) {
                p.put("new_line_code", sha256Hex(newLine + newLineContent));
            }
        }
        return p;
    }

    /** SHA-256 of the UTF-8 bytes, lowercase hex (GitLab's line-code scheme). */
    static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String apiUrl(String path) {
        return connection.getBaseUrl() + "/api/v4" + path;
    }

    // ------------------------------------------------------------------
    //  low-level transport
    // ------------------------------------------------------------------

    private String rawUrl(String path, String ref) {
        return connection.getBaseUrl() + "/api/v4/projects/"
                + connection.getProjectId() + "/repository/files/"
                + UriComponentEncoder.encode(path) + "/raw"
                + "?ref=" + UriComponentEncoder.encode(ref);
    }

    /**
     * Perform a JSON API request (any method) and parse the JSON response.
     * Non-2xx answers raise {@link GitLabApiException} with a readable
     * message; the docker-gateway loopback remap applies as for GETs.
     */
    private JsonNode doApi(String method, String url, String jsonBody) throws IOException {
        HttpResponse<String> resp = send(method, url, jsonBody, timeout,
                HttpResponse.BodyHandlers.ofString(), false);
        if (resp.statusCode() >= 400) {
            throw new GitLabApiException(resp.statusCode(),
                    describeError(resp.statusCode(), resp.body()));
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IOException("GitLab returned non-JSON response for " + url, e);
        }
    }

    /**
     * Transport core: send a request, remapping a loopback host via the
     * docker gateway on connect failure. With {@code checkStatus} a non-2xx
     * answer throws a plain {@link IOException} (legacy GET behaviour);
     * without it the raw response is returned to the caller (doApi parses
     * the body into a {@link GitLabApiException}).
     */
    private <T> HttpResponse<T> send(String method, String url, String jsonBody,
                                     Duration timeout, HttpResponse.BodyHandler<T> handler,
                                     boolean checkStatus)
            throws IOException {
        String effective = effectiveUrl(url);
        try {
            return checkStatus
                    ? check(http.send(newRequest(effective, method, jsonBody, timeout), handler))
                    : http.send(newRequest(effective, method, jsonBody, timeout), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling GitLab", e);
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException e) {
            // A loopback baseUrl is unreachable from inside a container;
            // retry once via the docker gateway (host's published ports).
            String remapped;
            try {
                remapped = remapLoopback(url);
            } catch (Exception remapEx) {
                LOG.warn("remapLoopback failed for {}: {} {}", url,
                        remapEx.getClass().getName(), remapEx.getMessage());
                throw e;
            }
            LOG.warn("connect failed for {} ({}); gateway={} remapped={}",
                    url, e.getClass().getSimpleName(), gateway(), remapped);
            if (remapped == null) {
                throw e;
            }
            try {
                HttpResponse<T> resp = checkStatus
                        ? check(http.send(newRequest(remapped, method, jsonBody, timeout), handler))
                        : http.send(newRequest(remapped, method, jsonBody, timeout), handler);
                WORKING_HOST.put(connection.getBaseUrl(), gateway());
                return resp;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling GitLab", ie);
            } catch (Exception e2) {
                LOG.warn("gateway retry {} failed: {} {}", remapped,
                        e2.getClass().getName(), e2.getMessage());
                if (e2 instanceof IOException) {
                    throw (IOException) e2;
                }
                throw new IOException(e2);
            }
        }
    }

    /**
     * Perform a request and throw a PLAIN IOException on non-2xx — the
     * legacy behaviour of the original GET path (fetchRawFile and friends
     * report the URL, not a parsed body).
     */
    private <T> HttpResponse<T> send(String url, Duration timeout,
                                     HttpResponse.BodyHandler<T> handler) throws IOException {
        return send("GET", url, null, timeout, handler, true);
    }

    private String doGet(String url, Duration timeout) throws IOException {
        return send(url, timeout, HttpResponse.BodyHandlers.ofString()).body();
    }

    private byte[] doGetBytes(String url, Duration timeout) throws IOException {
        return send(url, timeout, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    /**
     * Like {@link #doGetBytes} but stops reading after {@code limit} bytes
     * (bounded heap usage for untrusted upstream sizes). Exceeding the cap
     * raises {@link FileSizeLimitExceededException} — the caller must not
     * serve a truncated body as if it were the whole file.
     */
    private byte[] doGetBytesLimited(String url, Duration timeout, long limit) throws IOException {
        java.net.http.HttpResponse<java.io.InputStream> resp =
                send(url, timeout, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream in = resp.body()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > limit) {
                    throw new FileSizeLimitExceededException(limit);
                }
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    private <T> HttpResponse<T> check(HttpResponse<T> resp) throws IOException {
        if (resp.statusCode() >= 400) {
            throw new IOException("GitLab returned HTTP " + resp.statusCode() + " for " + resp.request().uri());
        }
        return resp;
    }

    private HttpRequest newRequest(String url, String method, String jsonBody, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("PRIVATE-TOKEN", decryptPat());
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else if ("DELETE".equals(method)) {
            b.method("DELETE", HttpRequest.BodyPublishers.noBody());
        } else {
            b.GET();
        }
        return b.build();
    }

    /**
     * If a working gateway host was remembered for this connection's
     * loopback baseUrl, use it up front.
     */
    private String effectiveUrl(String url) {
        String base = connection.getBaseUrl();
        String host = WORKING_HOST.get(base);
        if (host == null || !isLoopback(url)) {
            return url;
        }
        return withHost(url, host);
    }

    /**
     * @return the URL with the loopback host replaced by the container's
     *         default gateway, or {@code null} if remapping is not
     *         applicable (non-loopback host, or no gateway found — i.e. not
     *         running in a Linux container)
     */
    private static String remapLoopback(String url) {
        if (!isLoopback(url)) {
            return null;
        }
        String gw = gateway();
        return gw == null ? null : withHost(url, gw);
    }

    private static boolean isLoopback(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("[::1]");
        } catch (Exception e) {
            return false;
        }
    }

    private static String withHost(String url, String host) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            int port = u.getPort();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return u.getScheme() + "://" + host + (port > 0 ? ":" + port : "") + path + query;
        } catch (Exception e) {
            return null;
        }
    }

    private static String gateway() {
        if (!gatewayChecked) {
            synchronized (GitLabClient.class) {
                if (!gatewayChecked) {
                    dockerGateway = parseDefaultGateway();
                    gatewayChecked = true;
                }
            }
        }
        return dockerGateway;
    }

    /**
     * Default gateway from /proc/net/route (Linux only) as a dotted-quad
     * string; {@code null} when unavailable.
     */
    private static String parseDefaultGateway() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/net/route"))) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                if (p.length > 2 && "00000000".equals(p[1]) && !"00000000".equals(p[2])) {
                    // The gateway is a 32-bit integer in host byte order
                    // (little-endian on x86/ARM): the first hex byte pair is
                    // the LAST octet of the address.
                    String h = p[2];
                    int b0 = Integer.parseUnsignedInt(h.substring(0, 2), 16);
                    int b1 = Integer.parseUnsignedInt(h.substring(2, 4), 16);
                    int b2 = Integer.parseUnsignedInt(h.substring(4, 6), 16);
                    int b3 = Integer.parseUnsignedInt(h.substring(6, 8), 16);
                    return b3 + "." + b2 + "." + b1 + "." + b0;
                }
            }
        } catch (Exception ignored) {
            // non-Linux or unreadable: no docker remap available
        }
        return null;
    }

    private String decryptPat() {
        if (encryptionKey != null) {
            return com.gitlab.sync.confluence.security.PatEncryptor.decrypt(
                    connection.getEncryptedPat(), encryptionKey);
        }
        return com.gitlab.sync.confluence.security.PatEncryptor.decrypt(
                connection.getEncryptedPat());
    }
}
