package com.gitlab.sync.confluence.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Renders diagram source (Mermaid, PlantUML, ...) to a Confluence Storage
 * Format image by calling a self-hosted {@link
 * <a href="https://kroki.io">Kroki</a>} instance.
 *
 * <p>Kroki exposes a REST endpoint of the form
 * {@code {base}/{diagramType}/svg/{base64url-encoded-source}} which returns an
 * SVG image. Because macro rendering must be a pure read (no attachment
 * writes are allowed during a page render), the diagram is referenced as an
 * external image URL rather than being downloaded and re-hosted as a Confluence
 * attachment. The page simply embeds
 * {@code <img src="{kroki}/{type}/svg/{b64}"/>} and the browser fetches the SVG
 * directly from the Kroki host when the page is viewed.</p>
 *
 * <p>The Kroki URL is built deterministically from the diagram source, so no
 * network round-trip is required to produce the macro output — only when a
 * viewer actually loads the page does the browser request the SVG. This keeps
 * rendering fast and side-effect free. If the diagram source contains
 * characters that would break the URL, the source is base64url-encoded (no
 * padding), which is the form Kroki accepts.</p>
 *
 * <p>All methods here are static and side-effect free.</p>
 */
public final class KrokiRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(KrokiRenderer.class);

    /** Default Kroki endpoint used when no system property overrides it. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8000";

    /** System property key that overrides the Kroki base URL. */
    public static final String KROKI_URL_PROPERTY = "gitsync.kroki.url";

    /**
     * System property key for the Kroki base URL used by the server-side
     * pre-flight probe only. The public {@code <img>} URLs keep using
     * {@link #KROKI_URL_PROPERTY} (they are fetched by the viewer's
     * browser); the probe runs inside Confluence's JVM and may need a
     * different host (e.g. a docker service name vs. {@code localhost}).
     * Defaults to the public URL.
     */
    public static final String KROKI_INTERNAL_URL_PROPERTY = "gitsync.kroki.internal.url";

    private KrokiRenderer() {
        // static utility — no instances
    }

    /**
     * @return the Kroki base URL from the {@value #KROKI_URL_PROPERTY} system
     *         property, defaulting to {@value #DEFAULT_BASE_URL}
     */
    public static String getKrokiBaseUrl() {
        String url = System.getProperty(KROKI_URL_PROPERTY, DEFAULT_BASE_URL);
        if (url == null || url.isEmpty()) {
            url = DEFAULT_BASE_URL;
        }
        // Normalise: strip any trailing slash so URL concatenation is uniform.
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * @return the Kroki base URL for server-side probes from the
     *         {@value #KROKI_INTERNAL_URL_PROPERTY} system property,
     *         defaulting to the public base URL
     */
    public static String getKrokiInternalBaseUrl() {
        String url = System.getProperty(KROKI_INTERNAL_URL_PROPERTY);
        if (url == null || url.isEmpty()) {
            return getKrokiBaseUrl();
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * @param language the fenced code block language tag, e.g. {@code mermaid}
     * @return {@code true} if the language identifies a diagram type Kroki
     *         renders (currently {@code mermaid} or {@code plantuml})
     */
    public static boolean isDiagramBlock(String language) {
        if (language == null) {
            return false;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return "mermaid".equals(lang) || "plantuml".equals(lang);
    }

    /**
     * Build a Confluence Storage Format image element whose {@code src} points
     * at a self-hosted Kroki SVG endpoint for the given diagram.
     *
     * <p>The fragment is a bare {@code <img>} rather than
     * {@code <ac:image>...<ri:attachment>}: the Kroki URL is not a Confluence
     * attachment, so an {@code ri:attachment} reference would point at a
     * missing file and render as broken. The browser fetches the SVG directly
     * from Kroki when the page is viewed; if Kroki is down the image simply
     * does not load (no server-side fallback, no render error).</p>
     *
     * @param language     the diagram type, e.g. {@code mermaid} or {@code plantuml}
     * @param code         the raw diagram source
     * @param krokiBaseUrl the Kroki base URL (may be {@code null}; the default
     *                     is used)
     * @return storage-format XML for the image, or an empty string if the
     *         arguments are unusable
     */
    public static String renderDiagram(String language, String code, String krokiBaseUrl) {
        String url = diagramUrl(language, code, krokiBaseUrl);
        if (url.isEmpty()) {
            return "";
        }
        String type = language.trim().toLowerCase(Locale.ROOT);
        String alt = type + " diagram";
        return "<p><img src=\"" + xmlEscape(url) + "\" alt=\"" + xmlEscape(alt)
                + "\" width=\"800\" style=\"max-width:100%\"/>\n</p>"
                + "<p style=\"font-size:0.8em;color:#888;text-align:center\">"
                + "<em>" + alt + " — loaded from Kroki</em></p>";
    }

    /**
     * Build the Kroki SVG URL for a diagram source (empty string when the
     * language is not a diagram type or the source is empty).
     *
     * <p>Kroki URL payloads are zlib-deflated source, base64url-encoded.
     * Deflate in a loop until finished() — a single pass into a fixed
     * buffer would silently truncate incompressible payloads.</p>
     */
    public static String diagramUrl(String language, String code, String krokiBaseUrl) {
        String type = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        String source = code == null ? "" : code;
        if (!isDiagramBlock(type) || source.isEmpty()) {
            return "";
        }
        String base = (krokiBaseUrl == null || krokiBaseUrl.isEmpty())
                ? getKrokiBaseUrl() : krokiBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        byte[] raw = source.getBytes(StandardCharsets.UTF_8);
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(raw);
        deflater.finish();
        java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            deflated.write(buf, 0, n);
        }
        deflater.end();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(deflated.toByteArray());
        return base + "/" + type + "/svg/" + encoded;
    }

    /**
     * Pre-flight validation of every Kroki diagram image in a rendered
     * document (requirement R2/R3 error scenario: a broken diagram must show
     * a message with the offending line, not an empty block).
     *
     * <p>Each {@code <img src="...{krokiBase}/...">} emitted by
     * {@link #renderDiagram} is fetched synchronously; on HTTP error the
     * image (and its caption) is replaced with an error box carrying the
     * upstream message (PlantUML reports "line: N", Mermaid reports its
     * parser error when it includes one). On network failure the box says
     * Kroki is unreachable. URLs that return 200 are left untouched.</p>
     *
     * <p>The probe follows {@link #KROKI_INTERNAL_URL_PROPERTY} when set:
     * the {@code <img>} src is for the viewer's browser, while this
     * pre-flight runs inside Confluence's JVM, where the public host
     * (e.g. {@code localhost}) may be unreachable.</p>
     *
     * @param html         rendered HTML containing Kroki diagram images
     * @param krokiBaseUrl the Kroki base URL the images were built with
     * @return HTML with failing diagrams replaced by error boxes
     */
    public static String validateDiagrams(String html, String krokiBaseUrl) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String base = (krokiBaseUrl == null || krokiBaseUrl.isEmpty())
                ? getKrokiBaseUrl() : krokiBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String internalBase = getKrokiInternalBaseUrl();
        java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile(
                "<p><img src=\"([^\"]*)\"[^>]*/>\\s*</p>\\s*"
                        + "<p style=\"font-size:0.8em;color:#888;text-align:center\">"
                        + "<em>([^<]*) — loaded from Kroki</em></p>",
                java.util.regex.Pattern.DOTALL);
        // The URL inside the attribute is xmlEscape()-d, so match the
        // escaped form and unescape for the HTTP request.
        java.util.regex.Matcher m = imgPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            // The URL inside the attribute is xmlEscape()-d by renderDiagram.
            String url = xmlUnescape(m.group(1));
            if (!url.startsWith(base + "/")) {
                continue;
            }
            String rest = url.substring(base.length() + 1);
            int end = rest.indexOf('/');
            String type = end > 0 ? rest.substring(0, end) : rest;
            // Probe through the server-side base: the img src above is for
            // the viewer's browser, this fetch runs in Confluence's JVM.
            String probeUrl = internalBase.equals(base)
                    ? url
                    : internalBase + url.substring(base.length());
            ProbeResult r = probe(probeUrl, type);
            if (r.ok) {
                continue; // keep the working image
            }
            String replacement = "<div class=\"git-sync-diagram-error\""
                    + " style=\"border:1px solid #e74c3c;padding:1em;background:#fdf0f0\">"
                    + "<p><strong>⚠ Ошибка диаграммы (" + xmlEscape(type) + ")</strong></p>"
                    + "<p>" + xmlEscape(r.message) + "</p>"
                    + "</div>";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final class ProbeResult {
        final boolean ok;
        final String message;
        ProbeResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * Fetch one Kroki diagram URL. 200 → ok; HTTP error → the upstream
     * error message; unreachable → a generic message.
     *
     * <p>Timeouts (NFR01): the connect timeout is the admin-panel setting
     * {@code renderTimeoutMs} (default 10 000 ms — the value that used to be
     * hard-coded here); the read timeout stays 2× the connect timeout
     * (default 20 000 ms, again matching the previous hard-coded pair).
     * The setting is read per probe — probes are rare (one per diagram
     * block per render), so no caching layer is needed.</p>
     */
    private static ProbeResult probe(String url, String type) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            long connectMs = com.gitlab.sync.confluence.settings.SettingsStore
                    .instance().current().renderTimeoutMs;
            conn.setConnectTimeout((int) Math.min(connectMs, Integer.MAX_VALUE));
            conn.setReadTimeout((int) Math.min(connectMs * 2L, Integer.MAX_VALUE));
            conn.setRequestMethod("GET");
            // Kroki content-negotiates error bodies: HttpURLConnection's
            // default Accept ("text/html, ...") gets the web-UI HTML page
            // instead of the API error message. Ask for JSON explicitly.
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, "");
            }
            String body = readErrorBody(conn);
            String msg = extractErrorMessage(body);
            if (msg.isEmpty()) {
                msg = "Kroki вернул HTTP " + status;
            }
            LOG.warn("Kroki diagram validation failed ({}): {}", type, msg);
            return new ProbeResult(false, msg);
        } catch (java.io.IOException e) {
            LOG.warn("Kroki unreachable for diagram {}: {}", type, e.getMessage());
            return new ProbeResult(false,
                    "Сервис Kroki недоступен (" + e.getMessage() + ") — диаграмма не проверена");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readErrorBody(java.net.HttpURLConnection conn) {
        java.io.InputStream in = null;
        try {
            in = conn.getErrorStream();
            if (in == null) {
                return "";
            }
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n < 0) {
                return "";
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * Kroki error bodies are JSON or plain text depending on the diagram
     * type and the requested {@code Accept} type. For JSON, the shape is
     * {@code {"error": {"code": 400, "message": "..."}}} (a nested object),
     * so a plain "error" string extraction would grab the wrong thing —
     * prefer the "message" string value, then a plain "error" string.
     */
    static String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String trimmed = body.trim();
        String msg = "";
        if (trimmed.startsWith("{")) {
            msg = jsonStringValue(trimmed, "\"message\"");
            if (msg.isEmpty()) {
                msg = jsonStringValue(trimmed, "\"error\"");
            }
        }
        if (msg.isEmpty()) {
            msg = trimmed.replaceAll("\\s+", " ").trim();
        }
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return msg;
    }

    /**
     * Extract the string value of {@code key} from a flat JSON document.
     * Returns an empty string when the key is absent or its value is not a
     * plain string (e.g. a nested object).
     */
    private static String jsonStringValue(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return "";
        }
        int open = json.indexOf('"', colon);
        if (open < 0) {
            return "";
        }
        // The first quote after the colon must open the value itself;
        // anything before it (e.g. '{' of a nested object) means no string.
        String between = json.substring(colon + 1, open).trim();
        if (!between.isEmpty()) {
            return "";
        }
        int close = -1;
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
            } else if (c == '"') {
                close = i;
                break;
            }
        }
        if (close <= open) {
            return "";
        }
        return json.substring(open + 1, close)
                .replace("\\n", " ").replace("\\\"", "\"");
    }

    private static String xmlUnescape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
