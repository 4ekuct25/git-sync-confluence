package com.atlassian.sal.api.net.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.function.Consumer;

public class HttpRequest {
    private final String method;
    private final URI uri;
    private Duration readTimeout;
    private Duration connectTimeout;
    private java.util.Map<String, String> headers = new java.util.HashMap<>();

    public HttpRequest(String method, URI uri) {
        this.method = method;
        this.uri = uri;
    }

    /** Convenience constructor used by the plugin: configure the request via a consumer. */
    public HttpRequest(String method, URI uri, Consumer<HttpRequest> configurer) {
        this(method, uri);
        if (configurer != null) {
            configurer.accept(this);
        }
    }

    /**
     * Convenience constructor that takes a URL string and avoids the checked
     * {@link URISyntaxException}.
     */
    public HttpRequest(String method, String url, Consumer<HttpRequest> configurer) {
        this.method = method;
        try {
            this.uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        if (configurer != null) {
            configurer.accept(this);
        }
    }

    public void addHeader(String name, String value) { headers.put(name, value); }
    public void setReadTimeout(Duration d) { this.readTimeout = d; }
    public void setConnectTimeout(Duration d) { this.connectTimeout = d; }
    public String getMethod() { return method; }
    public URI getUri() { return uri; }
    public java.util.Map<String, String> getHeaders() { return headers; }
}
