package com.familymeds.alerts;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

/**
 * Sends through Brevo's transactional email HTTP API (Render's free tier blocks SMTP, DESIGN §6). The sender is
 * a Gmail address verified in Brevo (R7: no domain). Startup fails if the key or sender is missing.
 */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "brevo")
class BrevoEmailSender implements EmailSender {

    private final RestClient http;
    private final String fromEmail;
    private final String fromName;

    BrevoEmailSender(@Value("${app.email.brevo-api-key:}") String apiKey, @Value("${app.email.from:}") String fromEmail,
            @Value("${app.email.from-name:Family medicines}") String fromName) {
        if (apiKey.isBlank() || fromEmail.isBlank()) {
            throw new IllegalStateException("app.email.provider=brevo needs BREVO_API_KEY and EMAIL_FROM");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .requestFactory(factory)
                .defaultHeader("api-key", apiKey)
                .build();
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(Email email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("email", fromEmail, "name", fromName));
        Map<String, String> to = new LinkedHashMap<>();
        to.put("email", email.toEmail());
        if (email.toName() != null) {
            to.put("name", email.toName());
        }
        body.put("to", List.of(to));
        body.put("subject", email.subject());
        body.put("textContent", email.bodyText());
        body.put("htmlContent", html(email));
        if (email.unsubscribeUrl() != null) {
            body.put("headers", Map.of("List-Unsubscribe", "<" + email.unsubscribeUrl() + ">",
                    "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        }
        http.post().uri("/smtp/email").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    /** Plain text in a <pre>-like block, everything escaped; the unsubscribe link becomes clickable. */
    static String html(Email email) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:sans-serif;white-space:pre-wrap\">")
                .append(HtmlUtils.htmlEscape(email.bodyText())).append("</div>");
        if (email.unsubscribeUrl() != null) {
            sb.append("<p style=\"font-size:12px;color:#666\"><a href=\"")
                    .append(HtmlUtils.htmlEscape(email.unsubscribeUrl()))
                    .append("\">Stop these emails</a></p>");
        }
        return sb.toString();
    }
}
