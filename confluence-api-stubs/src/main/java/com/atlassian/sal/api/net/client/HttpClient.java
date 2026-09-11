package com.atlassian.sal.api.net.client;

import com.atlassian.sal.api.net.Response;
import java.net.URI;
import java.util.function.Consumer;

public interface HttpClient {
    <T> T execute(HttpRequest request, Response.BodyHandler<T> handler) throws java.io.IOException;
}
