package javax.ws.rs.core;

import java.net.URI;

/**
 * Compile-time stub mirroring the JAX-RS 1.1 (JSR-311) {@code Response} API
 * as shipped in Confluence 8.5's DMZ (javax.ws.rs_jsr311-api-1.1.1.jar).
 *
 * <p>Signatures MUST match the real API exactly — the plugin links against
 * the DMZ copy at runtime, and any descriptor mismatch surfaces as
 * NoSuchMethodError on the first request. In particular
 * {@code ResponseBuilder} is an abstract class (not an interface) in the
 * real API, and 1.1 has no no-arg variants of methods that 2.x added.</p>
 */
public abstract class Response {

    protected Response() {}

    public abstract Object getEntity();

    public abstract int getStatus();

    public static ResponseBuilder fromResponse(Response response) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder status(StatusType statusType) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder status(Status status) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder status(int status) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder ok() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder ok(Object entity) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder serverError() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder created(URI location) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder noContent() {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static ResponseBuilder seeOther(URI location) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    /**
     * Fluent builder; mirrors {@code Response.ResponseBuilder} of the real
     * API (an abstract class). Only the methods the plugin uses are
     * declared.
     */
    public abstract static class ResponseBuilder {

        protected ResponseBuilder() {}

        public abstract Response build();

        public abstract ResponseBuilder status(int status);

        public ResponseBuilder status(StatusType statusType) {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public ResponseBuilder status(Status status) {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public abstract ResponseBuilder entity(Object entity);

        public abstract ResponseBuilder type(MediaType type);

        public abstract ResponseBuilder type(String type);

        public abstract ResponseBuilder header(String name, Object value);
    }

    public interface StatusType {

        int getStatusCode();

        Family getFamily();

        String getReasonPhrase();
    }

    public enum Family {
        FAMILY_1xx_INFORMATIONAL,
        FAMILY_2xx_SUCCESSFUL,
        FAMILY_3xx_REDIRECTION,
        FAMILY_4xx_CLIENT_ERROR,
        FAMILY_5xx_SERVER_ERROR;

        public int getMinStatusCode() {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public int getMaxStatusCode() {
            throw new UnsupportedOperationException("compile-time stub");
        }

        public String getReasonPhrase() {
            throw new UnsupportedOperationException("compile-time stub");
        }
    }

    /**
     * The JAX-RS 1.1 status enum. Note: unlike JAX-RS 2.x it has no
     * PAYLOAD_TOO_LARGE / LENGTH_REQUIRED / TIMEOUT values — use
     * {@link #status(int)} for non-standard codes.
     */
    public enum Status implements StatusType {
        OK(200),
        CREATED(201),
        ACCEPTED(202),
        NO_CONTENT(204),
        MOVED_PERMANENTLY(301),
        SEE_OTHER(303),
        NOT_MODIFIED(304),
        TEMPORARY_REDIRECT(307),
        BAD_REQUEST(400),
        UNAUTHORIZED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        NOT_ACCEPTABLE(406),
        CONFLICT(409),
        GONE(410),
        PRECONDITION_FAILED(412),
        UNSUPPORTED_MEDIA_TYPE(415),
        INTERNAL_SERVER_ERROR(500),
        SERVICE_UNAVAILABLE(503);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int getStatusCode() {
            return code;
        }

        public Family getFamily() {
            return null;
        }

        public String getReasonPhrase() {
            return "";
        }
    }
}
