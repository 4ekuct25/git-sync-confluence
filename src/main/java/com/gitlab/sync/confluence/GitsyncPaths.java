package com.gitlab.sync.confluence;

import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the on-disk locations the plugin needs without touching SAL's
 * {@code ComponentAccessor}: the package {@code com.atlassian.sal.api.component}
 * is not exported to plugin bundles by any bundle in Confluence DC 8.5, so a
 * reference to it fails with NoClassDefFoundError as soon as the class is
 * loaded.
 *
 * <p>Confluence starts the JVM with {@code -Dconfluence.home=<data dir>};
 * the conventional secure location is {@code {confluence.home}/secure} —
 * exactly where {@code ComponentAccessor.getSecureDir()} points.</p>
 */
public final class GitsyncPaths {

    private static final Logger LOG = LoggerFactory.getLogger(GitsyncPaths.class);

    private GitsyncPaths() {}

    /**
     * @return Confluence's secure directory ({@code $confluence.home/secure}),
     *         created if missing; falls back to the base directory if it
     *         cannot be created
     */
    public static File secureDir() {
        String home = System.getProperty("confluence.home");
        File base = (home != null && !home.isBlank())
                ? new File(home)
                : new File(System.getProperty("user.dir", "."));
        File secure = new File(base, "secure");
        if (!secure.isDirectory() && !secure.mkdirs()) {
            LOG.warn("Could not create secure dir {}, falling back to {}", secure, base);
            return base;
        }
        return secure;
    }
}
