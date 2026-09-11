package com.atlassian.confluence.user;

import com.atlassian.user.User;

/**
 * Stub of Confluence's {@code UserAccessor} — only the methods the plugin
 * uses are declared.
 */
public interface UserAccessor {
    User getUser(String username);
}
