package javax.ws.rs.core;

import java.security.Principal;

/**
 * Minimal stub of the JAX-RS {@code SecurityContext} interface. The DMZ REST
 * runtime is JAX-RS 1.1 (jsr311-api-1.1.1): there is NO getUsername()/getName()
 * — user identification goes through Principal.getName().
 */
public interface SecurityContext {

    String BASIC_AUTH = "BASIC";
    String CLIENT_CERT_AUTH = "CLIENT-CERT";
    String DIGEST_AUTH = "DIGEST";
    String FORM_AUTH = "FORM";

    Principal getUserPrincipal();

    boolean isUserInRole(String role);

    boolean isSecure();

    String getAuthenticationScheme();
}
