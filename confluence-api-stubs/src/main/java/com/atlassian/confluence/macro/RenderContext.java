package com.atlassian.confluence.macro;

import com.atlassian.confluence.pages.Page;

public interface RenderContext {
    Page getPage();
    String getLocale();
}
