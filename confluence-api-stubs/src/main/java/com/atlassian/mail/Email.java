package com.atlassian.mail;

/**
 * Stub of the atlassian-mail {@code Email} — only the methods the plugin
 * uses are declared. All setters chain (return {@code this}).
 */
public class Email implements java.io.Serializable {

    public Email(String to) {
    }

    public Email(String to, String subject, String body) {
    }

    public Email setFrom(String from) {
        return this;
    }

    public Email setFromName(String name) {
        return this;
    }

    public Email setTo(String to) {
        return this;
    }

    public Email setSubject(String subject) {
        return this;
    }

    public Email setCc(String cc) {
        return this;
    }

    public Email setBcc(String bcc) {
        return this;
    }

    public Email setReplyTo(String replyTo) {
        return this;
    }

    public Email setBody(String body) {
        return this;
    }

    public Email setMimeType(String mimeType) {
        return this;
    }

    public Email setEncoding(String encoding) {
        return this;
    }
}
