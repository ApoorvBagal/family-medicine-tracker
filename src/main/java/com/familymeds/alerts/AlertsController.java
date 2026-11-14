package com.familymeds.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.shared.web.ApiException;

/**
 * Alert recipients (family members who get emails, D12), the public unsubscribe link, a test email, the alert
 * status shown in the app, and the daily-job trigger for GitHub Actions.
 */
@RestController
public class AlertsController {

    public record RecipientRequest(@NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email) {
    }

    public record RecipientView(UUID id, String name, String email, boolean active, OffsetDateTime createdAt) {
        static RecipientView of(AlertRepository.Recipient r) {
            return new RecipientView(r.id(), r.name(), r.email(), r.active(), r.createdAt());
        }
    }

    static final int MAX_RECIPIENTS = 10;
    static final Duration TEST_EMAIL_GAP = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AlertRepository repo;
    private final OutboxSender outbox;
    private final DailyJob daily;
    private final Clock clock;
    private final String jobToken;
    private final String provider;
    private final String baseUrl;
    private final Map<UUID, Instant> lastTestEmail = new ConcurrentHashMap<>();

    public AlertsController(AlertRepository repo, OutboxSender outbox, DailyJob daily, Clock clock,
            @Value("${app.job-token:}") String jobToken, @Value("${app.email.provider:log}") String provider,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.repo = repo;
        this.outbox = outbox;
        this.daily = daily;
        this.clock = clock;
        this.jobToken = jobToken;
        this.provider = provider;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------ recipients (owner)

    @GetMapping("/api/v1/alert-recipients")
    public List<RecipientView> recipients(@AuthenticationPrincipal AppUserPrincipal user) {
        return repo.recipients(user.householdId()).stream().map(RecipientView::of).toList();
    }

    @PostMapping("/api/v1/alert-recipients")
    @Transactional
    public ResponseEntity<RecipientView> add(@AuthenticationPrincipal AppUserPrincipal user,
            @Valid @RequestBody RecipientRequest body) {
        if (repo.countRecipients(user.householdId()) >= MAX_RECIPIENTS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_RECIPIENTS",
                    "Up to " + MAX_RECIPIENTS + " people can get alerts.");
        }
        String email = body.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (email.equals(user.getUsername())) {
            throw new ApiException(HttpStatus.CONFLICT, "RECIPIENT_IS_OWNER", "You already get alerts as the owner.");
        }
        try {
            UUID id = repo.insertRecipient(user.householdId(), body.name().trim(), email, newToken());
            var created = repo.recipients(user.householdId()).stream().filter(r -> r.id().equals(id)).findFirst()
                    .orElseThrow();
            return ResponseEntity.status(HttpStatus.CREATED).body(RecipientView.of(created));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "RECIPIENT_EXISTS",
                    "That email is already on the list. Remove it first to add it again.");
        }
    }

    @DeleteMapping("/api/v1/alert-recipients/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        if (!repo.deleteRecipient(user.householdId(), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND", "Not on the list.");
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ test email and status (owner)

    @PostMapping("/api/v1/alerts/test-email")
    @Transactional
    public Map<String, Object> testEmail(@AuthenticationPrincipal AppUserPrincipal user) {
        Instant now = clock.instant();
        Instant last = lastTestEmail.get(user.householdId());
        if (last != null && last.plus(TEST_EMAIL_GAP).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TEST_EMAIL_TOO_SOON",
                    "A test email was just sent. Wait a couple of minutes.", Duration.between(now,
                            last.plus(TEST_EMAIL_GAP)));
        }
        lastTestEmail.put(user.householdId(), now);
        var deliveries = repo.deliveries(user.householdId());
        for (var d : deliveries) {
            String unsubscribe = d.unsubscribeToken() == null ? null
                    : baseUrl + "/api/v1/unsubscribe?token=" + d.unsubscribeToken();
            repo.enqueue(user.householdId(), d.email(), d.name(), "Test: medicine alerts work",
                    "This is a test from the family medicine tracker. Low-stock alerts will arrive like this.\n"
                            + "If it landed in spam, mark it \"Not spam\" so real alerts arrive in the inbox.\n\n- "
                            + baseUrl
                            + (unsubscribe == null ? "" : "\nTo stop these emails, open: " + baseUrl
                                    + "/?unsubscribe=" + d.unsubscribeToken()),
                    unsubscribe);
        }
        outbox.sendAfterCommit();
        return Map.of("queued", deliveries.size());
    }

    @GetMapping("/api/v1/alerts/status")
    public Map<String, Object> status(@AuthenticationPrincipal AppUserPrincipal user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailProvider", provider);
        var job = repo.lastFinishedJob(DailyJob.NAME).orElse(null);
        out.put("lastDailyCheck", job == null ? null : job.finishedAt());
        out.put("pendingEmails", repo.pendingCount());
        return out;
    }

    // ------------------------------------------------------------ public

    /**
     * The link in each email (and RFC 8058 one-click from mail apps). POST only: link scanners in mail
     * services open GET links, which must not unsubscribe anyone. The app's page posts here.
     */
    @PostMapping("/api/v1/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestParam @Size(max = 100) String token) {
        if (repo.unsubscribe(token).isEmpty() && !repo.tokenExists(token)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UNSUBSCRIBE_LINK_INVALID",
                    "This link is not valid. You may have been removed from the list already.");
        }
        return Map.of("unsubscribed", true);
    }

    /** Called by GitHub Actions cron with {@code Authorization: Bearer <JOB_TOKEN>}. Disabled if unset. */
    @PostMapping("/internal/jobs/daily")
    public Map<String, Object> runDaily(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (jobToken.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        }
        byte[] expected = ("Bearer " + jobToken).getBytes(StandardCharsets.UTF_8);
        byte[] given = auth == null ? new byte[0] : auth.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) { // constant-time compare
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Wrong job token.");
        }
        return daily.run();
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
