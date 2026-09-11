package com.gitlab.sync.confluence.gitlab;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Small helper for percent-encoding URI path segments. */
final class UriComponentEncoder {
    private UriComponentEncoder() {}

    static String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
