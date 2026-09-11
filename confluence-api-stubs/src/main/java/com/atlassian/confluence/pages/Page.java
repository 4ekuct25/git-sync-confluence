package com.atlassian.confluence.pages;

/**
 * Stub of Confluence's {@code Page}. Must be a CLASS (not an interface): 8.5
 * declares {@code public class Page extends AbstractPage}. With an interface
 * stub, javac emits {@code invokeinterface Page.getClass()} which dies at
 * runtime with IncompatibleClassChangeError against the real class.
 */
public class Page extends com.atlassian.confluence.core.ContentEntityObject {

    public long getId() {
        return 0L;
    }

    public String getTitle() {
        return null;
    }

    public String getAncestorTitle(int index) {
        return null;
    }

    /**
     * Declared in {@code ConfluenceEntityObject} (superclass of Page) and
     * inherited — the page creator's username.
     */
    public String getCreatorName() {
        return null;
    }
}
