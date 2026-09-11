package com.atlassian.confluence.macro;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import java.util.Map;

/**
 * Stub of Confluence's {@code AbstractMacro}.
 */
public abstract class AbstractMacro implements Macro {
    @Override
    public abstract String execute(Map<String, String> params, String body, ConversionContext context)
            throws MacroExecutionException;
}
