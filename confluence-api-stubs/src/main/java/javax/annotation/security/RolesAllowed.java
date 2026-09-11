package javax.annotation.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal stub of the Javax 8 (JACC) {@code @RolesAllowed} annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(RolesAllowed.List.class)
public @interface RolesAllowed {
    String[] value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        RolesAllowed[] value();
    }
}
