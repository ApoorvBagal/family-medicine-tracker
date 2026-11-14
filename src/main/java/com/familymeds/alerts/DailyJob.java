package com.familymeds.alerts;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.familymeds.inventory.StockRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * The daily check (R6), started by GitHub Actions cron because the free server sleeps. The estimate shrinks
 * every day without any writes, so each active medicine is re-evaluated (under its lock, one transaction each),
 * then due emails are sent or retried. Safe to run twice: statuses that didn't change send nothing.
 */
@Service
public class DailyJob {

    static final String NAME = "daily";
    private static final Logger log = LoggerFactory.getLogger(DailyJob.class);

    private final JdbcClient jdbc;
    private final StockRepository stock;
    private final AlertService alerts;
    private final AlertRepository repo;
    private final OutboxSender outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json;

    public DailyJob(JdbcClient jdbc, StockRepository stock, AlertService alerts, AlertRepository repo,
            OutboxSender outbox, TransactionTemplate tx, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.alerts = alerts;
        this.repo = repo;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.json = json;
    }

    public Map<String, Object> run() {
        return run(clock.instant());
    }

    /** {@code now} is a parameter so tests can run "tomorrow's" check. */
    public Map<String, Object> run(Instant now) {
        long id = repo.startJob(NAME, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        record Med(UUID householdId, UUID id) {
        }
        List<Med> meds = jdbc.sql("select household_id, id from medicine where archived_at is null order by created_at")
                .query(Med.class).list();
        int checked = 0;
        int changed = 0;
        int alerted = 0;
        int errors = 0;
        for (Med m : meds) {
            try {
                AlertService.Evaluation e = tx.execute(status -> {
                    if (!stock.lockMedicine(m.householdId(), m.id())) {
                        return null;
                    }
                    return alerts.evaluate(m.householdId(), m.id(), now);
                });
                checked++;
                if (e != null && e.runway() != null && !e.runway().status().name().equals(e.previous())) {
                    changed++;
                }
                if (e != null && e.emailed()) {
                    alerted++;
                }
            } catch (RuntimeException ex) {
                errors++; // one bad medicine doesn't stop the others
                log.error("Daily check failed for medicine {}", m.id(), ex);
            }
        }
        int[] sent = outbox.sendPending();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("medicinesChecked", checked);
        summary.put("statusChanges", changed);
        summary.put("alerts", alerted);
        summary.put("errors", errors);
        summary.put("emailsSent", sent[0]);
        summary.put("emailsFailed", sent[1]);
        summary.put("emailsPending", repo.pendingCount());
        repo.finishJob(id, OffsetDateTime.now(clock), json.writeValueAsString(summary));
        log.info("Daily check done: {}", summary);
        return summary;
    }
}
