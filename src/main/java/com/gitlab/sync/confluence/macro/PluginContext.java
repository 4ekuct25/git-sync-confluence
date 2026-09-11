package com.gitlab.sync.confluence.macro;

import com.gitlab.sync.confluence.GitsyncPaths;
import com.gitlab.sync.confluence.gitlab.ConnectionStore;
import com.gitlab.sync.confluence.gitlab.GitLabClient;

/**
 * Helper for the macro to reach shared plugin state at render time.
 * Confluence instantiates macros through the platform's macro registry and a
 * macro instance cannot look components up itself, so the macro reaches the
 * {@link ConnectionStore} through this holder.
 *
 * <p>The store is read from disk on every access (the same strategy as the
 * REST resources): a cached singleton would go stale the moment the admin
 * panel creates or edits a connection, because the macro may be rendered
 * (and thus initialise the cache) before any connection exists.</p>
 */
public final class PluginContext {

    private PluginContext() {
        // static holder — no instances
    }

    /**
     * @return a fresh {@link ConnectionStore} view of the secure-dir state
     */
    public static ConnectionStore getStore() {
        return new ConnectionStore(GitsyncPaths.secureDir());
    }

    /**
     * Convenience factory that builds a {@link GitLabClient} bound to the
     * given connection.
     */
    public static GitLabClient newClient(com.gitlab.sync.confluence.gitlab.GitLabConnection connection) {
        return new GitLabClient(connection);
    }
}
