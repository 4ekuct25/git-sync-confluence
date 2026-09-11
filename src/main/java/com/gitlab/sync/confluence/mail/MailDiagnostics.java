package com.gitlab.sync.confluence.mail;

import com.gitlab.sync.confluence.GitsyncPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

/**
 * Append-only diagnostic log for the R10 mail path at
 * {@code {secure}/gitsync/mail-diag.log}.
 *
 * <p>The plugin's slf4j output is not reliably visible on a running
 * Confluence (no binding inside the bundle), so every decision point of
 * the mail path is appended here instead: skipped notifications, resolved
 * recipients, and per-recipient send results. Lines carry no secrets — at
 * most usernames, e-mail addresses and exception messages.</p>
 */
public final class MailDiagnostics {

    /** Above this size the log is left untouched (it is diagnostic only). */
    private static final long MAX_FILE_BYTES = 1_000_000;

    private MailDiagnostics() {
    }

    public static void record(String line) {
        try {
            Path file = GitsyncPaths.secureDir().toPath()
                    .resolve("gitsync").resolve("mail-diag.log");
            if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) {
                return;
            }
            Files.createDirectories(file.getParent());
            String stamped = new Date().toString() + " " + line
                    + System.lineSeparator();
            Files.write(file, stamped.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // diagnostics must never break the mail path
        }
    }
}
