package com.familymeds.alerts;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.familymeds.identity.IdentityRepository;
import com.familymeds.inventory.Medicine;
import com.familymeds.inventory.MedicineRepository;
import com.familymeds.inventory.StockRepository;

/**
 * Low-stock alert rules. {@link #evaluate} must run inside a transaction that holds the medicine lock (every
 * stock change does; the daily job takes it), so two evaluations of one medicine never race.
 * <ul>
 *   <li>Emails only when the status changes: OK to LOW, to OUT, and "restocked" back to OK after an alert.</li>
 *   <li>LOW to OUT is a new email; OUT to LOW (partly restocked) is not.</li>
 *   <li>Flapping guard: the same alert again within 6 hours (LOW, OK, LOW) is not repeated.</li>
 *   <li>No alerts for archived medicines or before the first stock entry.</li>
 * </ul>
 */
@Service
public class AlertService {

    static final Duration REPEAT_GUARD = Duration.ofHours(6);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    public record Evaluation(Runway runway, String previous, boolean emailed) {
    }

    private final AlertRepository alerts;
    private final MedicineRepository medicines;
    private final StockRepository stock;
    private final IdentityRepository identity;
    private final OutboxSender outbox;
    private final Clock clock;
    private final String baseUrl;

    public AlertService(AlertRepository alerts, MedicineRepository medicines, StockRepository stock,
            IdentityRepository identity, OutboxSender outbox, Clock clock,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.alerts = alerts;
        this.medicines = medicines;
        this.stock = stock;
        this.identity = identity;
        this.outbox = outbox;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public ZoneId zone(UUID householdId) {
        return ZoneId.of(identity.findHousehold(householdId).map(h -> h.timezone()).orElse("Asia/Kolkata"));
    }

    /** The runway now, for display (no locking, no state change). */
    public Runway runway(UUID householdId, Medicine m, BigDecimal usable, Instant now) {
        ZoneId zone = zone(householdId);
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<StockRepository.Movement> last = stock.movements(householdId, m.id(), 1);
        LocalDate lastEntry = last.isEmpty() ? null : LocalDate.ofInstant(last.get(0).recordedAt().toInstant(), zone);
        return Runway.of(usable, m.dailyDose(), m.warnDays(), m.minThreshold(), lastEntry, today);
    }

    public Runway runway(UUID householdId, Medicine m, BigDecimal usable) {
        return runway(householdId, m, usable, clock.instant());
    }

    public Evaluation evaluate(UUID householdId, UUID medicineId) {
        return evaluate(householdId, medicineId, clock.instant());
    }

    /** Re-checks one medicine and queues emails if its status changed. Caller holds the medicine lock. */
    public Evaluation evaluate(UUID householdId, UUID medicineId, Instant now) {
        Medicine m = medicines.find(householdId, medicineId).orElse(null);
        if (m == null || m.archived() || stock.movements(householdId, medicineId, 1).isEmpty()) {
            return new Evaluation(null, null, false);
        }
        ZoneId zone = zone(householdId);
        LocalDate today = LocalDate.ofInstant(now, zone);
        BigDecimal usable = stock.lots(householdId, medicineId).stream()
                .filter(l -> !l.expiredOn(today))
                .map(StockRepository.Lot::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Runway runway = runway(householdId, m, usable, now);
        String next = runway.status().name();

        var state = alerts.state(medicineId).orElse(null);
        String previous = state == null ? "OK" : state.status();
        OffsetDateTime at = OffsetDateTime.ofInstant(now, zone);
        String lastAlert = state == null ? null : state.lastAlertStatus();
        OffsetDateTime lastAlertAt = state == null ? null : state.lastAlertAt();
        boolean restockPending = state != null && state.restockPending();
        if (next.equals(previous)) {
            if (state == null) {
                alerts.saveState(householdId, medicineId, next, at, null, null, false);
            }
            return new Evaluation(runway, previous, false);
        }

        boolean email;
        if ("OK".equals(next)) {
            email = restockPending; // "restocked" only after a LOW/OUT email, and only once
            restockPending = false;
        } else {
            // OUT is always news; LOW only when coming from OK (OUT -> LOW = partly restocked, still short).
            boolean news = "OUT".equals(next) || "OK".equals(previous);
            boolean repeat = next.equals(lastAlert) && lastAlertAt != null
                    && lastAlertAt.toInstant().plus(REPEAT_GUARD).isAfter(now); // hovering around the line
            email = news && !repeat;
            if (email) {
                lastAlert = next;
                lastAlertAt = at;
                restockPending = true;
            }
        }
        if (email) {
            queue(householdId, m, runway);
        }
        alerts.saveState(householdId, medicineId, next, at, lastAlert, lastAlertAt, restockPending);
        return new Evaluation(runway, previous, email);
    }

    private void queue(UUID householdId, Medicine m, Runway r) {
        String name = m.name() + (m.strength() == null ? "" : " " + m.strength());
        String units = units(m.unit());
        String subject;
        String text;
        switch (r.status()) {
            case OUT -> {
                subject = "Out of stock: " + name;
                text = (r.recorded().signum() > 0
                        ? name + " has probably run out (estimated from the daily dose since the last entry).\n"
                        : name + " has run out.\n") + "Please buy more.";
            }
            case LOW -> {
                if (r.daysLeft() != null) {
                    String days = r.daysLeft() + " day" + (r.daysLeft() == 1 ? "" : "s");
                    subject = "Low stock: " + name + " - about " + days + " left";
                    text = name + ": about " + qty(r.estimated()) + " " + units + " left, enough for about " + days
                            + " (runs out around " + DAY.format(r.runOutDate()) + ").\nPlease buy more.";
                } else {
                    subject = "Low stock: " + name;
                    text = name + ": only " + qty(r.estimated()) + " " + units + " left.\nPlease buy more.";
                }
            }
            default -> {
                subject = "Restocked: " + name;
                text = name + " is stocked again: " + qty(r.recorded()) + " " + units
                        + (r.daysLeft() != null ? ", about " + r.daysLeft() + " days." : ".");
            }
        }
        for (AlertRepository.Delivery d : alerts.deliveries(householdId)) {
            String unsubscribe = d.unsubscribeToken() == null ? null
                    : baseUrl + "/api/v1/unsubscribe?token=" + d.unsubscribeToken();
            String body = text + "\n\n- Family medicine tracker, " + baseUrl
                    + (unsubscribe == null ? "" : "\nTo stop these emails, open: " + baseUrl + "/?unsubscribe="
                            + d.unsubscribeToken());
            alerts.enqueue(householdId, d.email(), d.name(), subject, body, unsubscribe);
        }
        outbox.sendAfterCommit();
    }

    static String units(Medicine.Unit unit) {
        return switch (unit) {
            case ML -> "ml";
            case OTHER -> "units";
            default -> unit.name().toLowerCase(Locale.ROOT) + "s";
        };
    }

    static String qty(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
