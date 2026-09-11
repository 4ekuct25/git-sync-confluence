package com.atlassian.confluence.macro;

public class MacroResult {
    private final String body;
    private final String bodyType;
    private final String macroName;

    public MacroResult(String body, String bodyType, String macroName) {
        this.body = body;
        this.bodyType = bodyType;
        this.macroName = macroName;
    }

    /** Convenience constructor: block output with no macro name. */
    public MacroResult(String body) {
        this(body, Macro.OutputType.BLOCK.name(), null);
    }

    public String getBody() { return body; }
    public String getBodyType() { return bodyType; }
    public String getMacroName() { return macroName; }
}
