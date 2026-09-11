package com.atlassian.renderer.v2.macro;

public class MacroException extends Exception {
    public MacroException() {
    }

    public MacroException(String message) {
        super(message);
    }

    public MacroException(String message, Throwable cause) {
        super(message, cause);
    }

    public MacroException(Throwable cause) {
        super(cause);
    }
}
