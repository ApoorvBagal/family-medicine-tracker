package com.familymeds.alerts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Alert recipients, per-medicine alert state, the email outbox and daily-job runs. Household-scoped. */
@Repository
public class AlertRepository {

    public record Recipient(UUID id, String name, String email, boolean active, OffsetDateTime createdAt) {
    }

    public record State(String status, OffsetDateTime changedAt, String lastAlertStatus, OffsetDateTime lastAlertAt,
            boolean restockPending) {
    }

    public record OutboxRow(long id, String toEmail, String toName, String subject, String bodyText,
            String unsubscribeUrl, int attempts) {
    }

    public record JobRun(OffsetDateTime startedAt, OffsetDateTime finishedAt, String summary) {
    }

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ recipients

    public List<Recipient> recipients(UUID householdId) {
        return jdbc.sql("""
                select id, name, email, active, created_at from alert_recipient
                where household_id = :h order by created_at""")
                .param("h", householdId)
                .query(Recipient.class)
                .list();
    }

    public int countRecipients(UUID householdId) {
        return jdbc.sql("select count(*) from alert_recipient where household_id = :h")
                .param("h", householdId).query(Integer.class).single();
    }

    public UUID insertRecipient(UUID householdId, String name, String email, String token) {
        return jdbc.sql("""
                insert into alert_recipient (household_id, name, email, unsubscribe_token)
                values (:h, :name, :email, :hash) returning id""")
                .param("h", householdId)
                .param("name", name)
                .param("email", email)
                .param("hash", token)
                .query(UUID.class)
                .single();
    }

    public boolean deleteRecipient(UUID householdId, UUID id) {
        return jdbc.sql("delete from alert_recipient where household_id = :h and id = :id")
                .param("h", householdId).param("id", id).update() == 1;
    }

    /** Returns the email that was unsubscribed, if the token matched an active recipient. */
    public Optional<String> unsubscribe(String token) {
        return jdbc.sql("""
                update alert_recipient set active = false, unsubscribed_at = now()
                where unsubscribe_token = :hash and active returning email""")
                .param("hash", token)
                .query(String.class)
                .optional();
    }

    public boolean tokenExists(String token) {
        return jdbc.sql("select count(*) from alert_recipient where unsubscribe_token = :hash")
                .param("hash", token).query(Integer.class).single() > 0;
    }

    /** Who gets an alert: the owner (no unsubscribe link) and every active recipient (with their link token). */
    public record Delivery(String name, String email, String unsubscribeToken) {
    }

    public List<Delivery> deliveries(UUID householdId) {
        return jdbc.sql("""
                select display_name as name, email, null::text as unsubscribe_token from app_user where household_id = :h
                union all
                select name, email, unsubscribe_token from alert_recipient where household_id = :h and active""")
                .param("h", householdId)
                .query(Delivery.class)
                .list();
    }

    // ------------------------------------------------------------ alert state

    public Optional<State> state(UUID medicineId) {
        return jdbc.sql("""
                select status, changed_at, last_alert_status, last_alert_at, restock_pending
                from medicine_alert_state where medicine_id = :m""")
                .param("m", medicineId)
                .query(State.class)
                .optional();
    }

    public Map<UUID, String> statuses(UUID householdId) {
        Map<UUID, String> out = new java.util.HashMap<>();
        jdbc.sql("select medicine_id, status from medicine_alert_state where household_id = :h")
                .param("h", householdId)
                .query((java.sql.ResultSet rs) -> {
                    out.put(rs.getObject("medicine_id", UUID.class), rs.getString("status"));
                });
        return out;
    }

    public void saveState(UUID householdId, UUID medicineId, String status, OffsetDateTime changedAt,
            String lastAlertStatus, OffsetDateTime lastAlertAt, boolean restockPending) {
        jdbc.sql("""
                insert into medicine_alert_state (medicine_id, household_id, status, changed_at, last_alert_status,
                                                  last_alert_at, restock_pending)
                values (:m, :h, :s, :c, :las, :laa, :rp)
                on conflict (medicine_id) do update set status = excluded.status, changed_at = excluded.changed_at,
                    last_alert_status = excluded.last_alert_status, last_alert_at = excluded.last_alert_at,
                    restock_pending = excluded.restock_pending""")
                .param("m", medicineId)
                .param("h", householdId)
                .param("s", status)
                .param("c", changedAt)
                .param("las", lastAlertStatus)
                .param("laa", lastAlertAt)
                .param("rp", restockPending)
                .update();
    }

    // ------------------------------------------------------------ outbox

    public void enqueue(UUID householdId, String toEmail, String toName, String subject, String body,
            String unsubscribeUrl) {
        jdbc.sql("""
                insert into notification_outbox (household_id, to_email, to_name, subject, body_text, unsubscribe_url)
                values (:h, :e, :n, :s, :b, :u)""")
                .param("h", householdId)
                .param("e", toEmail)
                .param("n", toName)
                .param("s", subject)
                .param("b", body)
                .param("u", unsubscribeUrl)
                .update();
    }

    /**
     * Claims one due email, locked until the transaction ends. SKIP LOCKED lets two senders (after-commit
     * and the daily job) run at once without picking the same row.
     */
    public Optional<OutboxRow> claimNext(OffsetDateTime now) {
        return jdbc.sql("""
                select id, to_email, to_name, subject, body_text, unsubscribe_url, attempts from notification_outbox
                where sent_at is null and attempts < 10 and next_attempt_at <= :now
                order by id limit 1 for update skip locked""")
                .param("now", now)
                .query(OutboxRow.class)
                .optional();
    }

    public void markSent(long id, OffsetDateTime now) {
        jdbc.sql("update notification_outbox set sent_at = :now, attempts = attempts + 1, last_error = null where id = :id")
                .param("now", now).param("id", id).update();
    }

    public void markFailed(long id, OffsetDateTime retryAt, String error) {
        jdbc.sql("""
                update notification_outbox set attempts = attempts + 1, next_attempt_at = :t, last_error = :e
                where id = :id""")
                .param("t", retryAt).param("e", error).param("id", id).update();
    }

    public int pendingCount() {
        return jdbc.sql("select count(*) from notification_outbox where sent_at is null and attempts < 10")
                .query(Integer.class).single();
    }

    // ------------------------------------------------------------ job runs

    public long startJob(String name, OffsetDateTime at) {
        return jdbc.sql("insert into job_run (name, started_at) values (:n, :t) returning id")
                .param("n", name).param("t", at).query(Long.class).single();
    }

    public void finishJob(long id, OffsetDateTime at, String summaryJson) {
        jdbc.sql("update job_run set finished_at = :t, summary = cast(:s as jsonb) where id = :id")
                .param("t", at).param("s", summaryJson).param("id", id).update();
    }

    public Optional<JobRun> lastFinishedJob(String name) {
        return jdbc.sql("""
                select started_at, finished_at, summary::text as summary from job_run
                where name = :n and finished_at is not null order by started_at desc limit 1""")
                .param("n", name)
                .query(JobRun.class)
                .optional();
    }
}
