package com.atlassian.sal.api.net.client;

import java.io.InputStream;

/**
 * Minimal stub of the SAL HTTP response, just enough for the Git-Sync plugin
 * to compile against it.
 */
public interface HttpResponse {
    int getStatusCode();

    InputStream getBodyAsStream() throws java.io.IOException;

    String getHeader(String name);

    java.net.URI getRequestUri();
}
