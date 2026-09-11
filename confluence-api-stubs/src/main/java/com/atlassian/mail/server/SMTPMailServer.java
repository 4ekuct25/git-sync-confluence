package com.atlassian.mail.server;

import com.atlassian.mail.Email;
import com.atlassian.mail.MailException;

/**
 * Stub of the atlassian-mail {@code SMTPMailServer} interface — only the
 * methods the plugin uses are declared.
 */
public interface SMTPMailServer {

    String getDefaultFrom();

    void send(Email email) throws MailException;

    void quietSend(Email email) throws MailException;
}
