package javax.ws.rs.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal stub of the JAX-RS {@code @Context} annotation for compile time;
 * at runtime the real JAX-RS API provided by Confluence's REST stack is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Context {
}
