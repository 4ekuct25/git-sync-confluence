package com.atlassian.confluence.macro;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import java.util.Map;

/**
 * Stub of Confluence 8.5's {@code Macro} interface.
 *
 * <p>Confluence 8.x nests the body/output type enums inside the interface
 * ({@code Macro$BodyType}, {@code Macro$OutputType}); the old top-level
 * {@code BodyType}/{@code OutputType} classes do not exist at runtime and
 * referencing them breaks macro registration with NoClassDefFoundError.</p>
 */
public interface Macro {
    String execute(Map<String, String> params, String body, ConversionContext context)
            throws MacroExecutionException;

    BodyType getBodyType();

    OutputType getOutputType();

    enum BodyType {
        PLAIN_TEXT,
        RICH_TEXT,
        NONE
    }

    enum OutputType {
        INLINE,
        BLOCK
    }
}
