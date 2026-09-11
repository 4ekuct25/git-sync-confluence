package com.gitlab.sync.confluence.gitlab;

import java.io.Serializable;

/**
 * A stored GitLab connection. The PAT is never stored in plaintext —
 * it is AES-256-GCM encrypted by {@link com.gitlab.sync.confluence.security.PatEncryptor}.
 */
public class GitLabConnection implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String baseUrl;
    private int projectId;
    /**
     * Project {@code path_with_namespace} (e.g. {@code root/test-docs}),
     * resolved from the GitLab API when the connection is created/verified.
     * Used to build canonical web URLs ({@code /{projectPath}/-/blob/...});
     * the numeric id is not routable in GitLab's web UI.
     */
    private String projectPath;
    private String encryptedPat;
    private long createdAt;
    private long updatedAt;

    public GitLabConnection() {}

    public GitLabConnection(String id, String name, String baseUrl, int projectId, String encryptedPat) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.projectId = projectId;
        this.encryptedPat = encryptedPat;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getProjectId() { return projectId; }
    public void setProjectId(int projectId) { this.projectId = projectId; }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public String getEncryptedPat() { return encryptedPat; }
    public void setEncryptedPat(String encryptedPat) { this.encryptedPat = encryptedPat; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
