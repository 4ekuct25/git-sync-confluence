package com.atlassian.confluence.macro;

/**
 * Stub of Confluence's {@code MacroExecutionException}.
 */
public class MacroExecutionException extends Exception {
    private static final long serialVersionUID = 1L;

    public MacroExecutionException(String message) {
        super(message);
    }

    public MacroExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public MacroExecutionException(Throwable cause) {
        super(cause);
    }
}
