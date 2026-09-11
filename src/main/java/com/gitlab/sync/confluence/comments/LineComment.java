package com.gitlab.sync.confluence.comments;

import java.io.Serializable;

/**
 * A line-level comment anchored to a rendered block of a Git-Sync page.
 *
 * <p>{@code lineAnchor} is the 0-based index of the block element in document
 * order of the rendered content (block elements being {@code <p>},
 * {@code <li>}, {@code <h1>}–{@code <h6>}, and {@code <td>} as produced by
 * the Git-Sync macro). The frontend uses the same indexing to place icons.
 */
public class LineComment implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    /** Id of the comment this one replies to; {@code null} for a root comment. */
    private String parentId;
    private long pageId;
    private int lineAnchor;
    private String lineText;
    private String author;
    private String body;
    private boolean resolved;
    private long createdAt;
    private long updatedAt;
    /**
     * Set when this legacy local comment was migrated into a native GitLab
     * commit discussion — the value is the composite git comment id. Used
     * for one-shot migration idempotency (a marked comment is never
     * migrated twice).
     */
    private String migratedTo;

    /** Jackson deserialization. */
    public LineComment() {}

    public LineComment(long pageId, int lineAnchor, String lineText, String author, String body) {
        this.id = java.util.UUID.randomUUID().toString();
        this.pageId = pageId;
        this.lineAnchor = lineAnchor;
        this.lineText = lineText;
        this.author = author;
        this.body = body;
        this.resolved = false;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public long getPageId() { return pageId; }
    public void setPageId(long pageId) { this.pageId = pageId; }

    public int getLineAnchor() { return lineAnchor; }
    public void setLineAnchor(int lineAnchor) { this.lineAnchor = lineAnchor; }

    public String getLineText() { return lineText; }
    public void setLineText(String lineText) { this.lineText = lineText; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getMigratedTo() { return migratedTo; }
    public void setMigratedTo(String migratedTo) { this.migratedTo = migratedTo; }
}
