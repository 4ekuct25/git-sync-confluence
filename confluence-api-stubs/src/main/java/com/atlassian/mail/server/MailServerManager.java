package com.atlassian.mail.server;

import java.util.List;

/**
 * Stub of atlassian-mail's {@code MailServerManager} interface
 * ({@code com.atlassian.mail_atlassian-mail-5.3.0.jar}) — only the methods
 * the plugin uses are declared.
 *
 * <p>Confluence registers its {@code ConfluenceMailServerManager} bean
 * (a concrete class, a subclass of {@code AbstractMailServerManager})
 * under this interface in OSGi — the concrete class name is not a
 * service objectClass, so the plugin looks the service up by the
 * interface name.</p>
 */
public interface MailServerManager {

    SMTPMailServer getDefaultSMTPMailServer();

    List<SMTPMailServer> getSmtpMailServers();

    boolean isDefaultSMTPMailServerDefined();
}
