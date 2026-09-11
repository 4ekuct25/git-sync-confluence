package com.atlassian.confluence.core;

/**
 * Stub of Confluence's {@code ContentEntityObject}. Must be an ABSTRACT CLASS
 * (not an interface): 8.5 declares
 * {@code public abstract class ContentEntityObject extends AbstractLabelableEntityObject}.
 * A class/interface shape mismatch in a stub breaks the bytecode at runtime
 * (IncompatibleClassChangeError) when OSGi imports the real package.
 */
public abstract class ContentEntityObject {

    public String getIdAsString() {
        return null;
    }

    public String getBodyAsString() {
        return null;
    }
}
