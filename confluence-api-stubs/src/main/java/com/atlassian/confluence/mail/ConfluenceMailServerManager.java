package com.atlassian.confluence.mail;

import com.atlassian.mail.server.SMTPMailServer;

/**
 * Stub of Confluence's {@code ConfluenceMailServerManager} (8.5:
 * {@code public class ... extends AbstractMailServerManager}) — only the
 * methods the plugin uses are declared.
 */
public class ConfluenceMailServerManager {

    public SMTPMailServer getDefaultSMTPMailServer() {
        return null;
    }

    public java.util.List<SMTPMailServer> getSmtpMailServers() {
        return java.util.Collections.emptyList();
    }

    public boolean isDefaultSMTPMailServerDefined() {
        return false;
    }
}
