package com.gitlab.sync.confluence.comments.git;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.sync.confluence.GitsyncPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local "resolved" markers for GitLab commit-discussion notes.
 *
 * <p>GitLab does not support resolving notes on commit discussions (the
 * resolve API answers 404/400 «Note is not resolvable» — verified on CE
 * 18.8), so the resolved state of a git-native comment is a Confluence-side
 * marker persisted next to the legacy comments store:</p>
 * <pre>
 *   {confluence.home}/secure/gitsync/git-resolved.json
 *   { "&lt;connectionId&gt;:&lt;noteId&gt;": { "resolved": true, "by": "user", "at": 123 }, … }
 * </pre>
 *
 * <p>The marker key is {@code connectionId + ":" + GitLab note id} — the
 * note id is unique per GitLab instance, the connection id keeps it
 * unambiguous across multiple GitLab instances.</p>
 */
public class GitResolvedMarkers {

    private static final Logger LOG = LoggerFactory.getLogger(GitResolvedMarkers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile GitResolvedMarkers instance;

    private final Path dataFile;
    private final Object lock = new Object();
    private final Map<String, Entry> markers = new TreeMap<>();

    public static final class Entry {
        public boolean resolved;
        public String by;
        public long at;

        public Entry() {}

        public Entry(boolean resolved, String by, long at) {
            this.resolved = resolved;
            this.by = by;
            this.at = at;
        }
    }

    public static GitResolvedMarkers instance() {
        GitResolvedMarkers m = instance;
        if (m == null) {
            synchronized (GitResolvedMarkers.class) {
                m = instance;
                if (m == null) {
                    m = new GitResolvedMarkers(GitsyncPaths.secureDir());
                    instance = m;
                }
            }
        }
        return m;
    }

    private GitResolvedMarkers(File secureDir) {
        Path dir = secureDir.toPath().resolve("gitsync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create gitsync data dir", e);
        }
        this.dataFile = dir.resolve("git-resolved.json");
        load();
    }

    private void load() {
        try {
            if (Files.exists(dataFile)) {
                Map<String, Entry> loaded = MAPPER.readValue(
                        Files.readString(dataFile),
                        new TypeReference<TreeMap<String, Entry>>() {});
                markers.putAll(loaded);
                LOG.info("Loaded {} git resolved marker(s)", loaded.size());
            }
        } catch (IOException e) {
            LOG.warn("Failed to load git-resolved.json: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(dataFile.toFile(), new TreeMap<>(markers));
        } catch (IOException e) {
            LOG.error("Failed to persist git resolved markers", e);
        }
    }

    /** @return true when the note is currently marked resolved */
    public boolean isResolved(String connectionId, long noteId) {
        synchronized (lock) {
            Entry e = markers.get(key(connectionId, noteId));
            return e != null && e.resolved;
        }
    }

    /** Set (or clear) the resolved state for a note. */
    public void setResolved(String connectionId, long noteId, boolean resolved, String by) {
        synchronized (lock) {
            if (resolved) {
                markers.put(key(connectionId, noteId),
                        new Entry(true, by, System.currentTimeMillis()));
            } else {
                markers.remove(key(connectionId, noteId));
            }
            persist();
        }
    }

    /** Drop markers of deleted notes (housekeeping). */
    public void remove(String connectionId, long noteId) {
        synchronized (lock) {
            if (markers.remove(key(connectionId, noteId)) != null) {
                persist();
            }
        }
    }

    private static String key(String connectionId, long noteId) {
        return connectionId + ":" + noteId;
    }
}
