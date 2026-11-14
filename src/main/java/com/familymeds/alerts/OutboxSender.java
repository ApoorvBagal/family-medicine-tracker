package com.familymeds.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

/**
 * Sends queued emails. Rows are queued in the same transaction as the stock change, so an email goes out only
 * if the change was saved, and a slow email API never rolls a change back. Sending starts right after commit
 * (in the background); failures are retried by the daily job with growing delays, up to 10 attempts.
 * Delivery is at-least-once: if the app dies between "sent" and "marked sent", that email may repeat.
 */
@Component
public class OutboxSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);
    static final int MAX_PER_RUN = 50;

    private final AlertRepository repo;
    private final EmailSender email;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExecutorService background = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    public OutboxSender(AlertRepository repo, EmailSender email, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.email = email;
        this.tx = tx;
        this.clock = clock;
    }

    /** Call inside the transaction that queued emails: they are sent once (and only if) it commits. */
    public void sendAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            background.execute(this::sendPendingQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                background.execute(OutboxSender.this::sendPendingQuietly);
            }
        });
    }

    private void sendPendingQuietly() {
        try {
            sendPending();
        } catch (RuntimeException e) {
            log.warn("Sending queued emails failed; the daily job retries: {}", e.toString());
        }
    }

    /** Sends every due email, one row per transaction. Returns {sent, failed}. */
    public int[] sendPending() {
        int sent = 0;
        int failed = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            Boolean ok = tx.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now(clock);
                var row = repo.claimNext(now).orElse(null);
                if (row == null) {
                    return null;
                }
                try {
                    email.send(new EmailSender.Email(row.toEmail(), row.toName(), row.subject(), row.bodyText(),
                            row.unsubscribeUrl()));
                    repo.markSent(row.id(), now);
                    return true;
                } catch (Exception e) {
                    Duration wait = Duration.ofMinutes(5L << Math.min(row.attempts(), 8)); // 5 min, 10, 20 ... ~21 h
                    String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    repo.markFailed(row.id(), now.plus(wait), error.length() > 500 ? error.substring(0, 500) : error);
                    log.warn("Email {} failed (attempt {}): {}", row.id(), row.attempts() + 1, error);
                    return false;
                }
            });
            if (ok == null) {
                break;
            }
            if (ok) {
                sent++;
            } else {
                failed++;
            }
        }
        return new int[] {sent, failed};
    }

    @PreDestroy
    void stop() {
        background.shutdown();
    }
}
