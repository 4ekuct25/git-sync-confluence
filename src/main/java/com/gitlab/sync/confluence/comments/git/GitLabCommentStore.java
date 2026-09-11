package com.gitlab.sync.confluence.comments.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitlab.sync.confluence.comments.LineComment;
import com.gitlab.sync.confluence.gitlab.GitLabClient;
import com.gitlab.sync.confluence.gitlab.GitLabClient.GitLabApiException;
import com.gitlab.sync.confluence.macro.MarkdownToStorageConverter.BlockSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line comments stored as NATIVE GitLab commit discussions (DiffNotes).
 *
 * <p>GitLab CE 18.8 has no per-file blob discussion API, so a line comment
 * becomes a note on the commit that last modified the anchored line:</p>
 * <ul>
 *   <li><b>write</b> — the block's source lines are looked up through
 *       {@link LineAnchorMapper}; walking the file's commit history
 *       (newest first, {@value #HISTORY_SCAN_CAP} commits), the first commit
 *       whose diff contains a line of the block (changed or context) is used
 *       as the DiffNote's {@code position} (base/start = parent, head = the
 *       commit — verified on CE 18.8). When no such commit exists (or GitLab
 *       rejects the position) the note falls back to a plain commit comment
 *       without position.</li>
 *   <li><b>read</b> — recent commits of the file are scanned for
 *       discussions; notes whose position points at this file are mapped
 *       back to the CURRENT document block (block text at the note's commit
 *       matched by the R9 similarity algorithm, threshold 0.8). A note whose
 *       block was deleted or rewritten comes back with
 *       {@code lineAnchor = -1} — the frontend lists it under
 *       «Фрагмент не найден».</li>
 *   <li><b>resolve</b> — GitLab cannot resolve commit-discussion notes
 *       («Note is not resolvable»), so the resolved state is a local marker
 *       ({@link GitResolvedMarkers}), visible in Confluence only.</li>
 *   <li><b>attribution</b> — the note is authored by the PAT's user, so the
 *       Confluence author is embedded as a markdown bold prefix
 *       {@code **<user>**: text}; notes created directly in GitLab carry no
 *       prefix and show the GitLab author name.</li>
 * </ul>
 *
 * <p>Composite comment id: {@code <connectionId>:<sha>:d:<discussionId>}
 * (a thread's top note) or {@code <connectionId>:<sha>:n:<noteId>} (a
 * reply). The id is opaque to the frontend and self-describing to this
 * store — PUT/DELETE need no extra context.</p>
 */
public class GitLabCommentStore {

    private static final Logger LOG = LoggerFactory.getLogger(GitLabCommentStore.class);

    /** How many recent file commits to scan for discussions / anchor positions. */
    static final int HISTORY_SCAN_CAP = 50;

    /** TTL of the per-(connection, path, sha) lookups — the page re-renders
     *  often and GitLab calls are the expensive part. */
    private static final long CACHE_TTL_MS = 60_000;
    private static final int CACHE_MAX_ENTRIES = 500;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static final class CacheEntry {
        final Object value;
        final long at;

        CacheEntry(Object value) {
            this.value = value;
            this.at = System.currentTimeMillis();
        }
    }

    /**
     * {@code <connectionId>:<sha>:d|n:<discussionId>:<noteId>} — the
     * discussion id is GitLab's 40-hex sha1 (NOT a number), the note id is
     * the concrete note (the thread's top note for 'd', the reply for 'n').
     */
    static final Pattern ID_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):"
                    + "([0-9a-f]{7,40}):([dn]):([0-9a-f]{40}):(\\d+)$");

    /** {@code **<author>**: } prefix embedded into note bodies. */
    private static final Pattern AUTHOR_PREFIX = Pattern.compile("^\\*\\*([^*]+):\\*\\*\\s?");

    /** A user-facing error with a message safe to show in the UI. */
    public static class GitCommentException extends RuntimeException {
        public GitCommentException(String message) {
            super(message);
        }
    }

    public static final class ParsedId {
        public final String connectionId;
        public final String sha;
        /** 'd' — top-level note of a discussion, 'n' — a reply note */
        public final char kind;
        /** GitLab discussion id (40-hex); carried by every id of the store. */
        public final String discussionId;
        /** the concrete note's id (the thread's top note for 'd', the reply for 'n'). */
        public final long noteId;

        ParsedId(String connectionId, String sha, char kind, String discussionId, long noteId) {
            this.connectionId = connectionId;
            this.sha = sha;
            this.kind = kind;
            this.discussionId = discussionId;
            this.noteId = noteId;
        }

        public String discussionId() {
            return discussionId;
        }

        public long noteId() {
            return noteId;
        }
    }

    public static boolean isCompositeId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public static ParsedId parseId(String id) {
        Matcher m = ID_PATTERN.matcher(id == null ? "" : id);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a git comment id: " + id);
        }
        return new ParsedId(m.group(1), m.group(2), m.group(3).charAt(0),
                m.group(4), Long.parseLong(m.group(5)));
    }

    private final GitLabClient client;
    private final String connectionId;
    private final String path;
    private final String ref;
    private final GitResolvedMarkers markers = GitResolvedMarkers.instance();

    public GitLabCommentStore(GitLabClient client, String connectionId, String path, String ref) {
        this.client = client;
        this.connectionId = connectionId;
        this.path = path;
        this.ref = ref;
    }

    // ------------------------------------------------------------------
    //  read
    // ------------------------------------------------------------------

    /**
     * All comment threads on the file, mapped to the CURRENT document.
     *
     * @param pageId the Confluence page reading the comments (only echoed
     *               into the response shape)
     */
    public List<LineComment> list(long pageId) {
        try {
            List<JsonNode> commits = client.getFileCommits(path, ref, HISTORY_SCAN_CAP);
            String headSha = commits.isEmpty() ? null : commits.get(0).path("id").asText("");
            String currentContent = currentContent(headSha.isEmpty() ? null : headSha);
            List<BlockSpan> currentSpans = cachedSpansAtRef(currentContent);

            List<LineComment> out = new ArrayList<>();
            for (JsonNode c : commits) {
                String sha = c.path("id").asText("");
                if (sha.isEmpty()) {
                    continue;
                }
                List<JsonNode> discussions;
                try {
                    discussions = client.listCommitDiscussions(sha);
                } catch (GitLabApiException e) {
                    LOG.warn("listCommitDiscussions({}) failed: {}", sha, e.getMessage());
                    continue;
                }
                for (JsonNode d : discussions) {
                    String did = d.path("id").asText("");
                    if (did.isEmpty()) {
                        continue;
                    }
                    JsonNode detail = cachedShow(sha, did);
                    if (detail == null) {
                        continue;
                    }
                    collectNotes(detail, sha, did, currentSpans, pageId, out);
                }
            }
            out.sort(Comparator.comparingLong(LineComment::getCreatedAt));
            return out;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось загрузить комментарии из GitLab: "
                    + e.getMessage());
        }
    }

    private void collectNotes(JsonNode detail, String sha, String did,
                              List<BlockSpan> currentSpans, long pageId,
                              List<LineComment> out) {
        JsonNode notes = detail.path("notes");
        if (!notes.isArray() || notes.size() == 0) {
            return;
        }
        JsonNode pos = notes.get(0).path("position");
        String newPath = pos.path("new_path").asText("");
        if (!path.equals(newPath)) {
            return; // the discussion is about another file of this commit
        }
        Integer newLine = pos.hasNonNull("new_line") ? pos.get("new_line").asInt() : null;

        BlockSpan spanAt = null;
        int idxAt = -1;
        if (newLine != null) {
            List<BlockSpan> spansAt = cachedSpansAtSha(sha);
            idxAt = LineAnchorMapper.blockIndexAtLine(spansAt, newLine);
            if (idxAt >= 0) {
                spanAt = spansAt.get(idxAt);
            }
        }
        // Map the block at the note's commit to the current document.
        int anchor = -1;
        String lineText = "";
        if (spanAt != null) {
            BlockSpan current = LineAnchorMapper.bestMatch(currentSpans, spanAt.text, idxAt);
            if (current != null) {
                anchor = currentSpans.indexOf(current);
                lineText = current.text;
            } else {
                lineText = spanAt.text; // deleted/rewritten: keep the old quote
            }
        }

        for (int i = 0; i < notes.size(); i++) {
            JsonNode note = notes.get(i);
            long noteId = note.path("id").asLong(0);
            if (noteId == 0) {
                continue;
            }
            String rawBody = note.path("body").asText("");
            String author;
            String body;
            Matcher m = AUTHOR_PREFIX.matcher(rawBody);
            if (m.find() && m.start() == 0) {
                author = m.group(1).trim();
                body = rawBody.substring(m.end());
            } else {
                author = note.path("author").path("name").asText("");
                if (author.isBlank()) {
                    author = note.path("author").path("username").asText("—");
                }
                body = rawBody;
            }
            LineComment lc = new LineComment();
            long topNoteId = notes.get(0).path("id").asLong(0);
            lc.setId(connectionId + ":" + sha + (i == 0 ? ":d:" : ":n:") + did + ":" + noteId);
            lc.setParentId(i == 0 ? null : connectionId + ":" + sha + ":d:" + did + ":" + topNoteId);
            lc.setPageId(pageId);
            lc.setLineAnchor(anchor);
            lc.setLineText(lineText);
            lc.setAuthor(author);
            lc.setBody(body.trim());
            lc.setResolved(markers.isResolved(connectionId, noteId));
            lc.setCreatedAt(parseIso(note.path("created_at").asText()));
            lc.setUpdatedAt(parseIso(note.path("updated_at").asText()));
            out.add(lc);
        }
    }

    /**
     * @return one comment by composite id (the anchor is mapped to the
     *         current document the same way as in {@link #list})
     * @throws GitCommentException when the id is foreign to this store or
     *         the note no longer exists
     */
    public LineComment getById(String id) {
        ParsedId p = parseId(id);
        try {
            JsonNode detail = client.showCommitDiscussion(p.sha, p.discussionId());
            JsonNode notes = detail.path("notes");
            boolean found = false;
            List<BlockSpan> currentSpans = cachedSpansAtRef(currentContent(null));
            List<LineComment> out = new ArrayList<>();
            collectNotes(detail, p.sha, p.discussionId(), currentSpans, 0, out);
            for (LineComment c : out) {
                if (c.getId().equals(id)) {
                    found = true;
                    return c;
                }
            }
            if (!found && notes.isArray()) {
                // The note exists but did not map (foreign file / deleted
                // block) — still report it as an orphan so the UI can act.
                for (int i = 0; i < notes.size(); i++) {
                    JsonNode note = notes.get(i);
                    String nid = connectionId + ":" + p.sha
                            + (i == 0 ? ":d:" : ":n:") + p.discussionId()
                            + ":" + note.path("id").asLong();
                    if (nid.equals(id)) {
                        return orphanComment(detail, p.sha, p.discussionId(), note, i == 0);
                    }
                }
            }
            throw new GitCommentException("Комментарий не найден в GitLab: " + id);
        } catch (GitCommentException e) {
            throw e;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось загрузить комментарий: " + e.getMessage());
        }
    }

    private LineComment orphanComment(JsonNode detail, String sha, String did,
                                      JsonNode note, boolean top) {
        String rawBody = note.path("body").asText("");
        String author;
        String body;
        Matcher m = AUTHOR_PREFIX.matcher(rawBody);
        if (m.find() && m.start() == 0) {
            author = m.group(1).trim();
            body = rawBody.substring(m.end());
        } else {
            author = note.path("author").path("name").asText("—");
            body = rawBody;
        }
        LineComment lc = new LineComment();
        long topNoteId = 0;
        JsonNode allNotes = detail.path("notes");
        if (allNotes.isArray() && allNotes.size() > 0) {
            topNoteId = allNotes.get(0).path("id").asLong(0);
        }
        lc.setId(connectionId + ":" + sha + (top ? ":d:" : ":n:") + did + ":" + note.path("id").asLong());
        lc.setParentId(top ? null : connectionId + ":" + sha + ":d:" + did + ":" + topNoteId);
        lc.setPageId(0);
        lc.setLineAnchor(-1);
        lc.setLineText("");
        lc.setAuthor(author);
        lc.setBody(body.trim());
        lc.setResolved(markers.isResolved(connectionId, note.path("id").asLong()));
        lc.setCreatedAt(parseIso(note.path("created_at").asText()));
        lc.setUpdatedAt(parseIso(note.path("updated_at").asText()));
        return lc;
    }

    // ------------------------------------------------------------------
    //  write
    // ------------------------------------------------------------------

    /**
     * Create a comment (top-level or a reply).
     *
     * @param lineAnchor the frontend block index in the CURRENT document
     * @param author     the Confluence user's name (embedded as prefix)
     * @param body       the comment text (without the prefix)
     * @param parentId   composite id of the replied-to comment, or null
     * @param lineText   quoted block text from the frontend (informational)
     */
    public LineComment add(int lineAnchor, String author, String body,
                           String parentId, String lineText) {
        String prefixed = authorPrefix(author) + body;
        if (parentId != null) {
            return addReply(parentId, author, prefixed, lineAnchor, lineText);
        }
        try {
            String currentContent = currentContent(null);
            List<BlockSpan> spans = cachedSpansAtRef(currentContent);
            if (lineAnchor < 0 || lineAnchor >= spans.size()) {
                throw new GitCommentException(
                        "Блок не найден в файле (индекс " + lineAnchor + " вне диапазона)");
            }
            BlockSpan target = spans.get(lineAnchor);
            List<JsonNode> commits = client.getFileCommits(path, ref, HISTORY_SCAN_CAP);
            String headSha = null;
            for (JsonNode c : commits) {
                String sha = c.path("id").asText("");
                if (sha.isEmpty()) {
                    continue;
                }
                if (headSha == null) {
                    headSha = sha;
                }
                DiffPosition pos = findPositionForBlock(sha, target);
                if (pos == null) {
                    continue;
                }
                try {
                    JsonNode disc = client.createCommitDiscussion(sha, prefixed, pos.position);
                    return toTopComment(sha, disc, lineAnchor, target.text, author, body);
                } catch (GitLabApiException e) {
                    if (e.getStatus() == 400) {
                        // The position does not match GitLab's diff view —
                        // try the next commit, then fall back below.
                        LOG.warn("GitLab rejected position for block at line {} "
                                + "(commit {}): {}", target.startLine, sha, e.getMessage());
                        continue;
                    }
                    throw new GitCommentException(e.getMessage());
                }
            }
            if (headSha == null) {
                throw new GitCommentException("У файла нет истории коммитов — "
                        + "невозможно создать комментарий");
            }
            // Fallback: a plain commit comment without an inline position.
            JsonNode disc = client.createCommitDiscussion(headSha, prefixed, null);
            return toTopComment(headSha, disc, lineAnchor, target.text, author, body);
        } catch (GitCommentException e) {
            throw e;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось сохранить комментарий в GitLab: "
                    + e.getMessage());
        }
    }

    private LineComment addReply(String parentId, String author, String prefixed,
                                 int lineAnchor, String lineText) {
        ParsedId p = parseId(parentId);
        if (p.kind != 'd') {
            throw new GitCommentException("Ответ только на верхний комментарий треда");
        }
        try {
            JsonNode note = client.addCommitDiscussionNote(p.sha, p.discussionId(), prefixed);
            LineComment lc = new LineComment();
            lc.setId(connectionId + ":" + p.sha + ":n:" + p.discussionId() + ":" + note.path("id").asLong());
            lc.setParentId(parentId);
            lc.setPageId(0);
            lc.setLineAnchor(lineAnchor);
            lc.setLineText(lineText == null ? "" : lineText);
            lc.setAuthor(author);
            String body = prefixed.replaceFirst(Pattern.quote(authorPrefix(author)), "");
            lc.setBody(body);
            lc.setResolved(false);
            long at = parseIso(note.path("created_at").asText());
            lc.setCreatedAt(at);
            lc.setUpdatedAt(at);
            return lc;
        } catch (GitCommentException e) {
            throw e;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось сохранить ответ в GitLab: "
                    + e.getMessage());
        }
    }

    /**
     * Edit a comment's body (the author prefix is preserved/rebuilt) and/or
     * its resolved state (local marker).
     */
    public LineComment update(String id, String body, boolean resolved, String by) {
        ParsedId p = parseId(id);
        try {
            JsonNode detail = client.showCommitDiscussion(p.sha, p.discussionId());
            JsonNode notes = detail.path("notes");
            long noteId = p.noteId();
            if (body != null) {
                String current = "";
                if (notes.isArray()) {
                    for (JsonNode n : notes) {
                        if (n.path("id").asLong() == noteId) {
                            current = n.path("body").asText("");
                            break;
                        }
                    }
                }
                String prefix;
                Matcher m = AUTHOR_PREFIX.matcher(current);
                if (m.find() && m.start() == 0) {
                    prefix = "**" + m.group(1) + ":** ";
                } else {
                    prefix = authorPrefix(by);
                }
                client.updateCommitNote(p.sha, p.discussionId(), noteId, prefix + body);
            }
            markers.setResolved(connectionId, noteId, resolved, by);
            return getById(id);
        } catch (GitCommentException e) {
            throw e;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось обновить комментарий: " + e.getMessage());
        }
    }

    public LineComment setResolved(String id, boolean resolved, String by) {
        ParsedId p = parseId(id);
        long noteId = p.noteId();
        markers.setResolved(connectionId, noteId, resolved, by);
        return getById(id);
    }

    /**
     * Delete a comment. A top-level note ({@code :d:}) removes the whole
     * thread (all its notes); a reply ({@code :n:}) removes just that note.
     */
    public boolean delete(String id) {
        ParsedId p = parseId(id);
        try {
            JsonNode detail = client.showCommitDiscussion(p.sha, p.discussionId());
            JsonNode notes = detail.path("notes");
            if (!notes.isArray() || notes.size() == 0) {
                return false;
            }
            List<Long> toDelete = new ArrayList<>();
            if (p.kind == 'n') {
                for (JsonNode n : notes) {
                    if (n.path("id").asLong() == p.noteId()) {
                        toDelete.add(p.noteId());
                        break;
                    }
                }
            } else {
                for (int i = notes.size() - 1; i >= 0; i--) {
                    toDelete.add(notes.get(i).path("id").asLong());
                }
            }
            if (toDelete.isEmpty()) {
                return false;
            }
            for (long nid : toDelete) {
                try {
                    client.deleteCommitNote(p.sha, p.discussionId(), nid);
                } catch (GitLabApiException e) {
                    LOG.warn("deleteCommitNote({}) failed: {}", nid, e.getMessage());
                }
                markers.remove(connectionId, nid);
            }
            return true;
        } catch (IOException e) {
            throw new GitCommentException("Не удалось удалить комментарий: " + e.getMessage());
        }
    }

    /**
     * The author of a comment (for the author-or-admin modify check).
     */
    public String authorOf(String id) {
        LineComment c = getById(id);
        return c.getAuthor();
    }

    // ------------------------------------------------------------------
    //  anchoring: block ↔ commit diff
    // ------------------------------------------------------------------

    private static final class DiffPosition {
        final com.fasterxml.jackson.databind.node.ObjectNode position;

        DiffPosition(com.fasterxml.jackson.databind.node.ObjectNode position) {
            this.position = position;
        }
    }

    /**
     * Find a valid DiffNote position for {@code target} (a block of the
     * CURRENT file) on commit {@code sha}: the block is matched against the
     * file's version at that commit, and one of its lines must appear in the
     * commit's diff (changed or context).
     */
    private DiffPosition findPositionForBlock(String sha, BlockSpan target) throws IOException {
        List<BlockSpan> spansAt = cachedSpansAtSha(sha);
        int idxAt = LineAnchorMapper.blockIndexAtLine(spansAt, target.startLine);
        BlockSpan at = idxAt >= 0 ? spansAt.get(idxAt)
                : LineAnchorMapper.bestMatch(spansAt, target.text, idxAt);
        if (at == null) {
            return null; // the block did not exist (yet) at this commit
        }
        List<JsonNode> diffs = cachedDiffs(sha);
        JsonNode entry = null;
        for (JsonNode d : diffs) {
            if (path.equals(d.path("new_path").asText(""))
                    || path.equals(d.path("old_path").asText(""))) {
                entry = d;
                break;
            }
        }
        if (entry == null) {
            return null;
        }
        DiffLines lines = parseDiffNewSide(entry.path("diff").asText(""));
        String rawAtSha;
        try {
            rawAtSha = cachedRawAtSha(sha);
        } catch (IOException e) {
            return null;
        }
        for (int line = at.startLine; line <= at.endLine; line++) {
            Integer old = lines.newToOld.get(line);
            if (old == null) {
                continue; // the line is not part of this commit's diff view
            }
            String parent = parentOf(sha);
            com.fasterxml.jackson.databind.node.ObjectNode position =
                    GitLabClient.discussionPosition(parent, sha, path, path,
                            line, lineContentAt(rawAtSha, line));
            return new DiffPosition(position);
        }
        return null;
    }

    /** The parsed new-file side of a unified diff hunk. */
    private static final class DiffLines {
        /** new-line number (1-based) → old-line number, or -1 for pure additions. */
        final Map<Integer, Integer> newToOld = new HashMap<>();
    }

    private static DiffLines parseDiffNewSide(String diff) {
        DiffLines out = new DiffLines();
        if (diff == null || diff.isEmpty()) {
            return out;
        }
        int oldNo = 0;
        int newNo = 0;
        for (String raw : diff.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("@@")) {
                // @@ -a,b +c,d @@
                int a = line.indexOf('-');
                int c = line.indexOf('+');
                if (a >= 0 && c > a) {
                    oldNo = parseIntPart(line.substring(a + 1, line.indexOf(',', a)));
                    newNo = parseIntPart(line.substring(c + 1, line.indexOf(',', c + 1)));
                }
            } else if (line.startsWith("+++") || line.startsWith("---")
                    || line.startsWith("\\")) {
                // file headers / "\ No newline at end of file"
            } else if (line.startsWith("+")) {
                out.newToOld.put(newNo, -1);
                newNo++;
            } else if (line.startsWith("-")) {
                oldNo++;
            } else if (line.startsWith(" ")) {
                out.newToOld.put(newNo, oldNo);
                oldNo++;
                newNo++;
            }
            // an empty line inside a hunk is a context line with empty content
            else if (newNo > 0 && oldNo > 0 && (line.isEmpty())) {
                out.newToOld.put(newNo, oldNo);
                oldNo++;
                newNo++;
            }
        }
        return out;
    }

    private static int parseIntPart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The 1-based {@code line}-th line of {@code content}, without the
     * trailing newline ("" when out of range). Feeds GitLab's line-code.
     */
    private static String lineContentAt(String content, int line) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int idx = line - 1;
        if (idx < 0 || idx >= lines.length) {
            return "";
        }
        return lines[idx];
    }

    /**
     * The commit's parent sha; for an initial commit (no parent) GitLab's
     * diff refs fall back to the commit itself.
     */
    private String parentOf(String sha) throws IOException {
        Object cached = cacheGet("parent|" + connectionId + "|" + sha);
        if (cached instanceof String) {
            return (String) cached;
        }
        String parent = sha;
        try {
            JsonNode c = client.getCommit(sha);
            JsonNode parents = c.path("parent_ids");
            if (parents.isArray() && parents.size() > 0) {
                parent = parents.get(0).asText(sha);
            }
        } catch (GitLabApiException e) {
            LOG.warn("getCommit({}) failed: {}", sha, e.getMessage());
        }
        cachePut("parent|" + connectionId + "|" + sha, parent);
        return parent;
    }

    // ------------------------------------------------------------------
    //  mapping helpers
    // ------------------------------------------------------------------

    private LineComment toTopComment(String sha, JsonNode disc, int lineAnchor,
                                     String lineText, String author, String body) {
        String did = disc.path("id").asText("");
        if (did.isEmpty()) {
            throw new GitCommentException("GitLab не вернул id созданного обсуждения");
        }
        JsonNode notes = disc.path("notes");
        JsonNode top = notes.isArray() && notes.size() > 0 ? notes.get(0) : null;
        long noteId = top == null ? 0 : top.path("id").asLong();
        long at = top == null ? System.currentTimeMillis()
                : parseIso(top.path("created_at").asText());
        LineComment lc = new LineComment();
        lc.setId(connectionId + ":" + sha + ":d:" + did + ":" + noteId);
        lc.setParentId(null);
        lc.setPageId(0);
        lc.setLineAnchor(lineAnchor);
        lc.setLineText(lineText == null ? "" : lineText);
        lc.setAuthor(author);
        lc.setBody(body);
        lc.setResolved(noteId != 0 && markers.isResolved(connectionId, noteId));
        lc.setCreatedAt(at);
        lc.setUpdatedAt(at);
        return lc;
    }

    // ------------------------------------------------------------------
    //  author prefix
    // ------------------------------------------------------------------

    static String authorPrefix(String author) {
        return "**" + (author == null || author.isBlank() ? "unknown" : author) + ":** ";
    }

    // ------------------------------------------------------------------
    //  TTL cache
    // ------------------------------------------------------------------

    private static Object cacheGet(String key) {
        CacheEntry e = CACHE.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
            CACHE.remove(key, e);
            return null;
        }
        return e.value;
    }

    private static void cachePut(String key, Object value) {
        if (CACHE.size() >= CACHE_MAX_ENTRIES) {
            long now = System.currentTimeMillis();
            CACHE.entrySet().removeIf(en -> now - en.getValue().at > CACHE_TTL_MS);
            if (CACHE.size() >= CACHE_MAX_ENTRIES) {
                CACHE.clear();
            }
        }
        CACHE.put(key, new CacheEntry(value));
    }

    private String cachedRawAtRef() throws IOException {
        String key = "raw|" + connectionId + "|" + path + "|" + ref;
        Object v = cacheGet(key);
        if (v instanceof String) {
            return (String) v;
        }
        String content = client.fetchRawFile(path, ref);
        cachePut(key, content);
        return content;
    }

    /**
     * The file's content at the head of {@code ref}, cached immutably by
     * the head commit's SHA. Unlike the ref-keyed TTL cache above, a
     * per-SHA entry can never be stale: right after a new commit the head
     * SHA changes, so the next read fetches the new content immediately
     * (a ref-keyed cache would re-anchor comments against up to
     * {@link #CACHE_TTL_MS} old content). When the file does not exist on
     * ref (or cannot be read) an empty string is returned — comments then
     * degrade to orphans instead of the whole list failing.
     *
     * @param knownHeadSha the head SHA of {@code path} at {@code ref},
     *                     when the caller already has it (avoids a second
     *                     commits-list call); null to resolve it
     */
    private String currentContent(String knownHeadSha) {
        String sha = knownHeadSha;
        if (sha == null || sha.isEmpty()) {
            try {
                List<JsonNode> commits = client.getFileCommits(path, ref, 1);
                if (commits != null && !commits.isEmpty()) {
                    sha = commits.get(0).path("id").asText("");
                }
            } catch (IOException e) {
                LOG.warn("getFileCommits({}@{}) failed: {}", path, ref, e.getMessage());
            }
        }
        if (sha != null && !sha.isEmpty()) {
            try {
                return cachedRawAtSha(sha);
            } catch (IOException e) {
                LOG.warn("fetchRawFile({}@{}) failed: {}", path, sha, e.getMessage());
            }
        }
        return "";
    }

    private String cachedRawAtSha(String sha) throws IOException {
        String key = "raw|" + connectionId + "|" + path + "|" + sha;
        Object v = cacheGet(key);
        if (v instanceof String) {
            return (String) v;
        }
        String content = client.fetchRawFile(path, sha);
        cachePut(key, content);
        return content;
    }

    private List<BlockSpan> cachedSpansAtRef(String content) {
        String key = "spans|" + connectionId + "|" + path + "|" + ref
                + "|" + Integer.toHexString(content.hashCode());
        Object v = cacheGet(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<BlockSpan> l = (List<BlockSpan>) v;
            return l;
        }
        List<BlockSpan> spans = LineAnchorMapper.blockSpans(content, null);
        cachePut(key, spans);
        return spans;
    }

    private List<BlockSpan> cachedSpansAtSha(String sha) {
        String key = "spans|" + connectionId + "|" + path + "|sha|" + sha;
        Object v = cacheGet(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<BlockSpan> l = (List<BlockSpan>) v;
            return l;
        }
        try {
            List<BlockSpan> spans = LineAnchorMapper.blockSpans(cachedRawAtSha(sha), null);
            cachePut(key, spans);
            return spans;
        } catch (IOException e) {
            LOG.warn("fetchRawFile({}@{}) failed: {}", path, sha, e.getMessage());
            return List.of();
        }
    }

    private List<JsonNode> cachedDiffs(String sha) {
        String key = "diffs|" + connectionId + "|" + sha;
        Object v = cacheGet(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<JsonNode> l = (List<JsonNode>) v;
            return l;
        }
        try {
            List<JsonNode> diffs = client.getCommitDiffs(sha);
            cachePut(key, diffs);
            return diffs;
        } catch (IOException e) {
            LOG.warn("getCommitDiffs({}) failed: {}", sha, e.getMessage());
            return List.of();
        }
    }

    private JsonNode cachedShow(String sha, String discussionId) {
        String key = "disc|" + connectionId + "|" + sha + "|" + discussionId;
        Object v = cacheGet(key);
        if (v instanceof JsonNode) {
            return (JsonNode) v;
        }
        try {
            JsonNode detail = client.showCommitDiscussion(sha, discussionId);
            cachePut(key, detail);
            return detail;
        } catch (IOException e) {
            LOG.warn("showCommitDiscussion({}, {}) failed: {}", sha, discussionId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static long parseIso(String s) {
        if (s == null || s.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
