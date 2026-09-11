package javax.ws.rs.core;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Compile-time stub of the JAX-RS 1.1 {@code UriBuilder}. The real DMZ API
 * only has the varargs {@code build(Object...)} method (no no-arg build —
 * that is a JAX-RS 2.x addition), so this stub must match.
 */
public abstract class UriBuilder {

    protected UriBuilder() {}

    public static UriBuilder fromPath(String path) {
        try {
            return new SimpleBuilder(new URI(path));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Bad path: " + path, e);
        }
    }

    public abstract UriBuilder path(String segment);

    public abstract URI build(Object... values);

    private static final class SimpleBuilder extends UriBuilder {

        private URI uri;

        private SimpleBuilder(URI uri) {
            this.uri = uri;
        }

        public UriBuilder path(String segment) {
            try {
                uri = new URI(uri.getScheme(), uri.getAuthority(),
                        uri.getPath() + "/" + segment, uri.getQuery(), uri.getFragment());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Bad path segment: " + segment, e);
            }
            return this;
        }

        public URI build(Object... values) {
            return uri;
        }
    }
}
