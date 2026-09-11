package com.atlassian.sal.api.net.client;

/**
 * Minimal stub of the SAL exception thrown when an HTTP call fails
 * (timeout, connection reset, etc.).
 */
public class HttpResponseException extends Exception {
    private static final long serialVersionUID = 1L;

    public HttpResponseException(String message) {
        super(message);
    }

    public HttpResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
