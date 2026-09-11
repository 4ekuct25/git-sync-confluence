package com.atlassian.mail;

/**
 * Stub of the atlassian-mail {@code MailException} — a checked exception
 * thrown by {@code SMTPMailServer.send(Email)} and {@code quietSend(Email)}.
 */
public class MailException extends java.lang.Exception {

    public MailException() {
        super();
    }

    public MailException(String message) {
        super(message);
    }

    public MailException(Throwable cause) {
        super(cause);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
