package com.gitlab.sync.confluence.comments;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.sync.confluence.GitsyncPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists line comments as a JSON file in Confluence's
 * plugin data directory:
 *   {confluence.home}/secure/gitsync/comments.json
 *
 * <p>Thread-safe via CopyOnWriteArrayList. Mutations are additionally
 * serialized with a lock so that read-modify-write sequences
 * (e.g. update-then-persist) are atomic across threads.
 */
public class CommentStore {

    private static final Logger LOG = LoggerFactory.getLogger(CommentStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dataFile;
    private final Object writeLock = new Object();
    private final List<LineComment> comments = new CopyOnWriteArrayList<>();

    /** One shared store for the plugin lifetime. The REST resource (a Jersey
     *  singleton) and the one-shot {@code CommentMigrator} must observe each
     *  other's writes; each instance otherwise caches {@code comments.json}
     *  in memory and re-persists its own view, so a separate migrator instance
     *  would silently resurrect comments the REST view already removed (and
     *  vice versa). All callers go through {@link #instance()}. */
    private static volatile CommentStore INSTANCE;

    public static CommentStore instance() {
        CommentStore i = INSTANCE;
        if (i == null) {
            synchronized (CommentStore.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new CommentStore(GitsyncPaths.secureDir());
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    public CommentStore(File secureDir) {
        Path dir = secureDir.toPath().resolve("gitsync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create gitsync data dir", e);
        }
        this.dataFile = dir.resolve("comments.json");
        load();
    }

    private void load() {
        try {
            if (Files.exists(dataFile)) {
                List<LineComment> loaded = MAPPER.readValue(
                        Files.readString(dataFile),
                        new TypeReference<List<LineComment>>() {});
                comments.addAll(loaded);
                LOG.info("Loaded {} line comment(s)", loaded.size());
            }
        } catch (IOException e) {
            LOG.warn("Failed to load comments.json: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(dataFile.toFile(), new ArrayList<>(comments));
        } catch (IOException e) {
            LOG.error("Failed to persist line comments", e);
        }
    }

    /** All comments for a page, in the order they were added. */
    public List<LineComment> getAll(long pageId) {
        return comments.stream()
                .filter(c -> c.getPageId() == pageId)
                .map(CommentStore::copy)
                .toList();
    }

    /** Distinct page ids that have at least one local comment. */
    public java.util.Set<Long> pageIdsWithComments() {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (LineComment c : comments) {
            out.add(c.getPageId());
        }
        return out;
    }

    /** All comments anchored to a specific block of a page. */
    public List<LineComment> getByPageAndAnchor(long pageId, int lineAnchor) {
        return comments.stream()
                .filter(c -> c.getPageId() == pageId && c.getLineAnchor() == lineAnchor)
                .map(CommentStore::copy)
                .toList();
    }

    /** Adds a new comment (id/timestamps are set by the caller's constructor). */
    public LineComment add(LineComment comment) {
        synchronized (writeLock) {
            comments.add(comment);
            persist();
        }
        return comment;
    }

    /**
     * Updates a comment's body and/or resolved flag. Pass {@code null}
     * for {@code body} to leave the existing body unchanged.
     *
     * @return the updated comment, or empty if the id is unknown
     */
    public Optional<LineComment> update(String id, String body, boolean resolved) {
        synchronized (writeLock) {
            Optional<LineComment> existing = findByIdLocked(id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            LineComment c = existing.get();
            if (body != null) {
                c.setBody(body);
            }
            c.setResolved(resolved);
            c.setUpdatedAt(System.currentTimeMillis());
            persist();
            return Optional.of(copy(c));
        }
    }

    /**
     * Resolves or reopens a comment without changing its body.
     *
     * @return the updated comment, or empty if the id is unknown
     */
    public Optional<LineComment> setResolved(String id, boolean resolved) {
        synchronized (writeLock) {
            Optional<LineComment> existing = findByIdLocked(id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            LineComment c = existing.get();
            c.setResolved(resolved);
            c.setUpdatedAt(System.currentTimeMillis());
            persist();
            return Optional.of(copy(c));
        }
    }

    public boolean delete(String id) {
        synchronized (writeLock) {
            boolean removed = comments.removeIf(c -> c.getId().equals(id));
            if (removed) {
                persist();
            }
            return removed;
        }
    }

    /**
     * Returns a defensive copy of the stored comment so callers cannot
     * mutate the in-memory list entries.
     */
    public Optional<LineComment> getById(String id) {
        synchronized (writeLock) {
            return findByIdLocked(id).map(CommentStore::copy);
        }
    }

    /** Lookup without locking — returns the LIVE list element so that
     *  in-place mutations by update()/setResolved() are persisted.
     *  Callers must already hold {@code writeLock}. */
    private Optional<LineComment> findByIdLocked(String id) {
        return comments.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    /**
     * Marks a comment as migrated into a native GitLab commit discussion
     * (one-shot migration idempotency).
     *
     * @return the updated comment, or empty if the id is unknown
     */
    public Optional<LineComment> setMigratedTo(String id, String migratedTo) {
        synchronized (writeLock) {
            Optional<LineComment> existing = findByIdLocked(id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            LineComment c = existing.get();
            c.setMigratedTo(migratedTo);
            persist();
            return Optional.of(copy(c));
        }
    }

    /**
     * Creates a shallow copy so external mutation of a returned comment
     * cannot corrupt the in-memory store.
     */
    public static LineComment copy(LineComment src) {
        LineComment c = new LineComment();
        c.setId(src.getId());
        c.setParentId(src.getParentId());
        c.setPageId(src.getPageId());
        c.setLineAnchor(src.getLineAnchor());
        c.setLineText(src.getLineText());
        c.setAuthor(src.getAuthor());
        c.setBody(src.getBody());
        c.setResolved(src.isResolved());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setMigratedTo(src.getMigratedTo());
        return c;
    }
}
