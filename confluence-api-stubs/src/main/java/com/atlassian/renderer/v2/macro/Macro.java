package com.atlassian.renderer.v2.macro;

import com.atlassian.renderer.RenderContext;
import com.atlassian.renderer.TokenType;
import com.atlassian.renderer.v2.RenderMode;
import java.util.Map;

/**
 * Stub of the atlassian-renderer v2 macro SPI.
 *
 * <p>Confluence 8.x macro modules must provide a class implementing this
 * interface (or the legacy radeox one); the old
 * {@code com.atlassian.confluence.macro.Macro} SPI alone is not enough for
 * module registration.</p>
 */
public interface Macro {
    String RAW_PARAMS_KEY = "RAW_PARAMS";

    TokenType getTokenType(Map<String, String> params, String body, RenderContext context);

    boolean isInline();

    boolean hasBody();

    RenderMode getBodyRenderMode();

    String execute(Map<String, String> params, String body, RenderContext context) throws MacroException;

    boolean suppressSurroundingTagDuringWysiwygRendering();

    boolean suppressMacroRenderingDuringWysiwyg();

    WysiwygBodyType getWysiwygBodyType();
}
