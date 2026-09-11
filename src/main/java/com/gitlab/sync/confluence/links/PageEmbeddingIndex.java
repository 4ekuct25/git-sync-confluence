package com.gitlab.sync.confluence.links;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.sync.confluence.GitsyncPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requirement R11 — which Confluence page embeds which Git file.
 *
 * <p>Every git-sync macro render records {@code (connection, path, ref) →
 * pageId}. When the link resolver rewrites a repo-relative document link, it
 * first asks this index: if the target file is itself embedded in a
 * Confluence page (of the same connection), the link points at that page —
 * not at the GitLab blob. The same link in a GitLab context (the raw file)
 * keeps its GitLab meaning, so the link is context-dependent as required.</p>
 *
 * <p>File-backed (a JSON map in the plugin secure dir) so the index
 * survives Confluence restarts; per key the LAST rendered page wins
 * (LinkedHashSet, most recently seen order). Known limitation: a page that
 * has never been rendered since the file was embedded is not indexed yet —
 * first render of the embedding page populates the entry.</p>
 */
public final class PageEmbeddingIndex {

    private static final Logger LOG = LoggerFactory.getLogger(PageEmbeddingIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_SEP = "\u0000";

    private static volatile PageEmbeddingIndex INSTANCE;

    private final Path dataFile;
    private final Object writeLock = new Object();
    /** key = connectionId SEP path SEP ref → pageIds, most recently rendered last */
    private final Map<String, Set<String>> index = new ConcurrentHashMap<>();

    private PageEmbeddingIndex(File secureDir) {
        Path dir = secureDir.toPath().resolve("gitsync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create gitsync data dir", e);
        }
        this.dataFile = dir.resolve("embeddings.json");
        load();
    }

    /** Shared singleton — the index only makes sense across renders. */
    public static PageEmbeddingIndex instance() {
        PageEmbeddingIndex i = INSTANCE;
        if (i == null) {
            synchronized (PageEmbeddingIndex.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new PageEmbeddingIndex(GitsyncPaths.secureDir());
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    private void load() {
        try {
            if (Files.exists(dataFile)) {
                Map<String, Set<String>> loaded = MAPPER.readValue(
                        Files.readString(dataFile),
                        new TypeReference<Map<String, Set<String>>>() {});
                for (Map.Entry<String, Set<String>> e : loaded.entrySet()) {
                    index.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }
                LOG.info("Loaded {} page embedding(s)", index.size());
            }
        } catch (IOException e) {
            LOG.warn("Failed to load embeddings.json: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(dataFile.toFile(), index);
        } catch (IOException e) {
            LOG.error("Failed to persist embeddings.json", e);
        }
    }

    /** Record that the page rendered the given file of the given connection. */
    public void record(String connectionId, String path, String ref, String pageId) {
        if (connectionId == null || connectionId.isEmpty()
                || path == null || path.isEmpty()
                || pageId == null || pageId.isEmpty()) {
            return;
        }
        String key = connectionId + KEY_SEP + path + KEY_SEP + (ref == null ? "" : ref);
        synchronized (writeLock) {
            Set<String> pages = index.computeIfAbsent(key, k -> new LinkedHashSet<>());
            pages.remove(pageId);
            pages.add(pageId); // most recently rendered last
            persist();
        }
    }

    /**
     * The most recently rendered Confluence page that embeds the file, or
     * empty when the file is not (yet) embedded anywhere.
     */
    public String findPage(String connectionId, String path, String ref) {
        String key = connectionId + KEY_SEP + path + KEY_SEP + (ref == null ? "" : ref);
        Set<String> pages = index.get(key);
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        String last = null;
        synchronized (writeLock) {
            for (String p : pages) {
                last = p;
            }
        }
        return last;
    }
}
