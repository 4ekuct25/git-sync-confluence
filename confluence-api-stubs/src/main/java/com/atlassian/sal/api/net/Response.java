package com.atlassian.sal.api.net;

public interface Response {
    interface BodyHandler<T> {
        T handle(com.atlassian.sal.api.net.client.HttpResponse response) throws java.io.IOException;
    }

    class BodyHandlers {
        private BodyHandlers() {}

        public static BodyHandler<String> ofString() {
            return response -> "";
        }

        public static BodyHandler<byte[]> ofByteArray() {
            return response -> new byte[0];
        }
    }
}
