package com.atlassian.sal.api.component;

public interface UserManager {
    String getCurrentUserName();
    Object getUserByUserName(String name);
}
