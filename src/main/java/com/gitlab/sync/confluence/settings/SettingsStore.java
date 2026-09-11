package com.gitlab.sync.confluence.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.sync.confluence.GitsyncPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Persists the Git-Sync runtime settings as a JSON file in Confluence's
 * plugin data directory:
 * <pre>
 *   {confluence.home}/secure/gitsync/settings.json
 * </pre>
 *
 * <p>Fields (all optional on disk; a missing or non-positive value falls
 * back to the documented default):</p>
 * <ul>
 *   <li>{@code gitTimeoutMs} — timeout for requests to the GitLab REST API
 *       (default {@value #DEFAULT_GIT_TIMEOUT_MS} — the value that used to be
 *       hard-coded in {@code GitLabClient});</li>
 *   <li>{@code renderTimeoutMs} — connect timeout of the Kroki pre-flight
 *       probe (default {@value #DEFAULT_RENDER_TIMEOUT_MS} — the previous
 *       hard-coded probe connect timeout; the probe read timeout is derived
 *       as 2× the connect timeout, i.e. 20 s by default, matching the
 *       previous hard-coded pair 10 s / 20 s);</li>
 *   <li>{@code maxMarkdownFileSizeBytes} — files larger than this are NOT
 *       converted; the macro shows a FR08 error box instead (default
 *       5 MiB, per FR05 / «Рекомендованные решения»).</li>
 * </ul>
 *
 * <p>Thread-safety: the in-memory snapshot is an immutable
 * {@link Settings}; concurrent readers either share it or, when the file's
 * mtime+size changed on disk (the admin panel wrote a new one), one thread
 * reloads under a lock and swaps the reference. Rendering therefore never
 * re-parses the file while its content is unchanged — the hot path is two
 * volatile reads plus one {@code stat()}.</p>
 */
public final class SettingsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default GitLab request timeout — the value previously hard-coded in {@code GitLabClient}. */
    public static final int DEFAULT_GIT_TIMEOUT_MS = 15_000;
    /** Default Kroki probe connect timeout; the probe read timeout is 2× this. */
    public static final int DEFAULT_RENDER_TIMEOUT_MS = 10_000;
    /** Default maximum markdown file size: 5 MiB (FR05). */
    public static final long DEFAULT_MAX_MARKDOWN_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private static volatile SettingsStore instance;

    /**
     * @return the process-wide store bound to Confluence's secure directory
     */
    public static SettingsStore instance() {
        SettingsStore s = instance;
        if (s == null) {
            synchronized (SettingsStore.class) {
                s = instance;
                if (s == null) {
                    s = new SettingsStore(GitsyncPaths.secureDir());
                    instance = s;
                }
            }
        }
        return s;
    }

    /** Immutable snapshot of the settings — safe to share across threads. */
    public static final class Settings {
        public final int gitTimeoutMs;
        public final int renderTimeoutMs;
        public final long maxMarkdownFileSizeBytes;

        public Settings(int gitTimeoutMs, int renderTimeoutMs, long maxMarkdownFileSizeBytes) {
            this.gitTimeoutMs = gitTimeoutMs;
            this.renderTimeoutMs = renderTimeoutMs;
            this.maxMarkdownFileSizeBytes = maxMarkdownFileSizeBytes;
        }

        static Settings defaults() {
            return new Settings(DEFAULT_GIT_TIMEOUT_MS, DEFAULT_RENDER_TIMEOUT_MS,
                    DEFAULT_MAX_MARKDOWN_FILE_SIZE_BYTES);
        }

        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("gitTimeoutMs", gitTimeoutMs);
            node.put("renderTimeoutMs", renderTimeoutMs);
            node.put("maxMarkdownFileSizeBytes", maxMarkdownFileSizeBytes);
            return node;
        }
    }

    private final Path dataFile;
    private final Object lock = new Object();
    private volatile Settings cached;
    private volatile long cachedMtime = -1;
    private volatile long cachedSize = -1;

    private SettingsStore(File secureDir) {
        Path dir = secureDir.toPath().resolve("gitsync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("Cannot create gitsync data dir: {}", e.getMessage());
        }
        this.dataFile = dir.resolve("settings.json");
        this.cached = loadOrDefaults();
        this.cachedMtime = mtimeMillis();
        this.cachedSize = fileSize();
    }

    /**
     * @return the current settings, reloading the file only when its
     *         mtime+size changed since the last load
     */
    public Settings current() {
        long m = mtimeMillis();
        long s = fileSize();
        if (m == cachedMtime && s == cachedSize) {
            return cached;
        }
        synchronized (lock) {
            if (m != cachedMtime || s != cachedSize) {
                cached = loadOrDefaults();
                cachedMtime = m;
                cachedSize = s;
            }
            return cached;
        }
    }

    /**
     * Validate, persist and update the in-memory snapshot.
     *
     * @return the settings actually stored
     * @throws IllegalArgumentException when a value is not a positive number
     */
    public Settings update(int gitTimeoutMs, int renderTimeoutMs, long maxMarkdownFileSizeBytes) {
        if (gitTimeoutMs <= 0 || renderTimeoutMs <= 0 || maxMarkdownFileSizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "gitTimeoutMs, renderTimeoutMs and maxMarkdownFileSizeBytes must be positive");
        }
        Settings s = new Settings(gitTimeoutMs, renderTimeoutMs, maxMarkdownFileSizeBytes);
        synchronized (lock) {
            try {
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(dataFile.toFile(), s.toJson(MAPPER));
            } catch (IOException e) {
                LOG.error("Failed to persist settings", e);
                throw new RuntimeException("Failed to persist settings.json", e);
            }
            cached = s;
            cachedMtime = mtimeMillis();
            cachedSize = fileSize();
            LOG.info("Settings updated: gitTimeoutMs={} renderTimeoutMs={} maxMarkdownFileSizeBytes={}",
                    s.gitTimeoutMs, s.renderTimeoutMs, s.maxMarkdownFileSizeBytes);
            return s;
        }
    }

    // ------------------------------------------------------------------
    //  internals
    // ------------------------------------------------------------------

    private Settings loadOrDefaults() {
        try {
            if (Files.exists(dataFile)) {
                JsonNode node = MAPPER.readValue(Files.readString(dataFile), JsonNode.class);
                return new Settings(
                        positiveInt(node, "gitTimeoutMs", DEFAULT_GIT_TIMEOUT_MS),
                        positiveInt(node, "renderTimeoutMs", DEFAULT_RENDER_TIMEOUT_MS),
                        positiveLong(node, "maxMarkdownFileSizeBytes",
                                DEFAULT_MAX_MARKDOWN_FILE_SIZE_BYTES));
            }
        } catch (IOException e) {
            // Corrupt/partial file: keep defaults (or the previous snapshot
            // on a reload) instead of crashing every render.
            LOG.warn("Failed to load settings.json, using defaults: {}", e.getMessage());
        }
        return Settings.defaults();
    }

    private long mtimeMillis() {
        try {
            FileTime t = Files.getLastModifiedTime(dataFile);
            return t == null ? -1 : t.toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private long fileSize() {
        try {
            return Files.exists(dataFile) ? Files.size(dataFile) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static int positiveInt(JsonNode node, String field, int def) {
        JsonNode v = node.get(field);
        if (v == null || !v.canConvertToInt()) {
            return def;
        }
        int i = v.asInt();
        return i > 0 ? i : def;
    }

    private static long positiveLong(JsonNode node, String field, long def) {
        JsonNode v = node.get(field);
        if (v == null || !v.canConvertToLong()) {
            return def;
        }
        long l = v.asLong();
        return l > 0 ? l : def;
    }
}
