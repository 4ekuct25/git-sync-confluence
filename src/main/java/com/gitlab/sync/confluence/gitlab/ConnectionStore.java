package com.gitlab.sync.confluence.gitlab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Persists GitLab connections as a JSON file in Confluence's
 * plugin data directory:
 *   {confluence.home}/secure/gitsync/connections.json
 *
 * Thread-safe via CopyOnWriteArrayList (connections are few and rarely mutated).
 */
public class ConnectionStore {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dataFile;
    private final List<GitLabConnection> connections = new CopyOnWriteArrayList<>();

    public ConnectionStore(File secureDir) {
        Path dir = secureDir.toPath().resolve("gitsync");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create gitsync data dir", e);
        }
        this.dataFile = dir.resolve("connections.json");
        load();
    }

    private void load() {
        try {
            if (Files.exists(dataFile)) {
                List<GitLabConnection> loaded = MAPPER.readValue(
                        Files.readString(dataFile),
                        new TypeReference<List<GitLabConnection>>() {});
                connections.addAll(loaded);
                LOG.info("Loaded {} GitLab connection(s)", loaded.size());
            }
        } catch (IOException e) {
            LOG.warn("Failed to load connections.json: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(dataFile.toFile(), new ArrayList<>(connections));
        } catch (IOException e) {
            LOG.error("Failed to persist connections", e);
        }
    }

    public List<GitLabConnection> getAll() {
        return List.copyOf(connections);
    }

    public Optional<GitLabConnection> getById(String id) {
        return connections.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    public GitLabConnection add(GitLabConnection conn) {
        connections.add(conn);
        persist();
        return conn;
    }

    public Optional<GitLabConnection> update(String id, String name, String baseUrl, int projectId, String encryptedPat) {
        Optional<GitLabConnection> existing = getById(id);
        if (existing.isPresent()) {
            GitLabConnection c = existing.get();
            c.setName(name);
            c.setBaseUrl(baseUrl);
            c.setProjectId(projectId);
            c.setEncryptedPat(encryptedPat);
            c.setUpdatedAt(System.currentTimeMillis());
            persist();
            return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * Update only the resolved project path (kept out of {@link #update}
     * so a plain name/URL/PAT edit never clobbers it).
     */
    public void setProjectPath(String id, String projectPath) {
        Optional<GitLabConnection> existing = getById(id);
        if (existing.isPresent()) {
            existing.get().setProjectPath(projectPath);
            existing.get().setUpdatedAt(System.currentTimeMillis());
            persist();
        }
    }

    public boolean delete(String id) {
        boolean removed = connections.removeIf(c -> c.getId().equals(id));
        if (removed) persist();
        return removed;
    }
}
