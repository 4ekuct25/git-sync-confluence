package com.atlassian.sal.api.component;

import com.atlassian.sal.api.net.client.HttpRequestFactory;
import java.io.File;

public interface ComponentAccessor {
    File getHomeDirectory();
    File getSecureDir();
    File getConfluenceBaseDir();
    HttpRequestFactory getHttpRequestFactory();
    UserManager getUserManager();
}
