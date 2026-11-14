package com.familymeds.alerts;

/** Sends one email. Implementations: Brevo's HTTP API (production) and log-only (local and tests). */
public interface EmailSender {

    record Email(String toEmail, String toName, String subject, String bodyText, String unsubscribeUrl) {
    }

    /** Throws if the email was not accepted; the outbox then retries later. */
    void send(Email email) throws Exception;
}
