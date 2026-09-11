package com.atlassian.confluence.core;

import com.atlassian.user.User;

/**
 * Stub of Confluence's {@code ContentPermissionManager} — only the methods
 * the plugin uses are declared.
 */
public interface ContentPermissionManager {
    boolean hasContentLevelPermission(User user, String permissionType,
                                      ContentEntityObject content);
}
