package com.gitlab.sync.confluence.errors;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded in-memory journal of recent Git-Sync failures.
 *
 * Feeds the GET /rest/gitsync/1.0/error-journal endpoint required by the
 * "safe failure" spec: operators can see what failed, where and why,
 * without digging through application logs.
 *
 * Entries are newest-first, capped at {@link #MAX_ENTRIES}. The journal is
 * process-local by design — a restart clears it, which is acceptable for an
 * operational hint (the persistent source of truth remains the app log).
 */
public final class ErrorJournal {

    public static final int MAX_ENTRIES = 100;

    private static final Deque<Map<String, String>> ENTRIES = new ArrayDeque<>();

    private ErrorJournal() {
    }

    /** Record a failure. Never throws — journaling must not break the caller. */
    public static void record(String source, String context, Throwable error) {
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("source", source == null ? "unknown" : source);
            entry.put("context", context == null ? "" : context);
            entry.put("errorType", error == null ? "unknown" : error.getClass().getName());
            entry.put("message", error == null ? "" : String.valueOf(error.getMessage()));
            synchronized (ENTRIES) {
                ENTRIES.addFirst(entry);
                while (ENTRIES.size() > MAX_ENTRIES) {
                    ENTRIES.removeLast();
                }
            }
        } catch (Exception ignored) {
            // Journaling is best-effort.
        }
    }

    /** Newest-first snapshot of the current entries. */
    public static List<Map<String, String>> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }
}
