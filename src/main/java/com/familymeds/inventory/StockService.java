package com.familymeds.inventory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.familymeds.alerts.AlertService;
import com.familymeds.alerts.Runway;
import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.identity.IdentityRepository;
import com.familymeds.shared.web.ApiException;

/**
 * Every stock change in one place. The rules:
 * <ul>
 *   <li><b>One at a time per medicine:</b> each change first locks the medicine row (SELECT … FOR UPDATE).
 *       Two phones recording doses at the same moment are applied one after the other; both count.</li>
 *   <li><b>Once only:</b> each request carries an idempotency key. A retry (double tap, flaky network) with
 *       the same key returns the original result instead of applying the change twice.</li>
 *   <li><b>Expired stock is never usable:</b> doses only come from unexpired batches, earliest expiry first.</li>
 *   <li><b>Never negative, never silently overwritten:</b> doses beyond usable stock are refused; a recount
 *       must state the number the user saw, and is refused if stock changed meanwhile.</li>
 *   <li><b>History is append-only:</b> mistakes are undone with a REVERSAL movement, not by deleting.</li>
 * </ul>
 */
@Service
public class StockService {

    public record StockLevel(BigDecimal usable, BigDecimal expired, List<StockRepository.Lot> lots, LocalDate today,
            Runway runway) {
    }

    public record Result(StockRepository.Movement movement, StockLevel stock, boolean replayed) {
    }

    private final StockRepository stock;
    private final MedicineService medicines;
    private final IdentityRepository identity;
    private final Clock clock;
    private final AlertService alerts;

    public StockService(StockRepository stock, MedicineService medicines, IdentityRepository identity, Clock clock,
            AlertService alerts) {
        this.alerts = alerts;
        this.stock = stock;
        this.medicines = medicines;
        this.identity = identity;
        this.clock = clock;
    }

    public LocalDate today(AppUserPrincipal user) {
        String tz = identity.findHousehold(user.householdId()).map(h -> h.timezone()).orElse("Asia/Kolkata");
        return LocalDate.now(clock.withZone(ZoneId.of(tz)));
    }

    public StockLevel level(AppUserPrincipal user, UUID medicineId) {
        medicines.get(user, medicineId); // 404 for unknown / other households
        return level(user, medicineId, today(user));
    }

    private StockLevel level(AppUserPrincipal user, UUID medicineId, LocalDate today) {
        List<StockRepository.Lot> lots = stock.lots(user.householdId(), medicineId);
        BigDecimal usable = BigDecimal.ZERO;
        BigDecimal expired = BigDecimal.ZERO;
        for (StockRepository.Lot lot : lots) {
            if (lot.expiredOn(today)) {
                expired = expired.add(lot.quantity());
            } else {
                usable = usable.add(lot.quantity());
            }
        }
        Runway runway = alerts.runway(user.householdId(), medicines.get(user, medicineId), usable);
        return new StockLevel(usable, expired, lots, today, runway);
    }

    public Map<UUID, BigDecimal[]> totals(AppUserPrincipal user) {
        return stock.totalsByMedicine(user.householdId(), today(user));
    }

    public List<StockRepository.Movement> history(AppUserPrincipal user, UUID medicineId) {
        medicines.get(user, medicineId);
        return stock.movements(user.householdId(), medicineId, 100);
    }

    // ---------------------------------------------------------------- changes

    @Transactional
    public Result purchase(AppUserPrincipal user, UUID medicineId, String key, BigDecimal quantity,
            LocalDate expiryDate, String label, String note, OffsetDateTime occurredAt) {
        Start s = begin(user, medicineId, key, "PURCHASE");
        if (s.replay != null) {
            return s.replay;
        }
        if (expiryDate != null && expiryDate.isBefore(s.today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_EXPIRED",
                    "That expiry date has already passed. Expired stock can't be added as usable.");
        }
        UUID lot = stock.insertLot(user.householdId(), medicineId, expiryDate, label);
        List<Change> changes = List.of(new Change(lot, quantity));
        return finish(user, medicineId, key, "PURCHASE", note, occurredAt, null, changes, s.today);
    }

    /** A dose: taken from unexpired batches, the one expiring first first (splits across batches if needed). */
    @Transactional
    public Result consume(AppUserPrincipal user, UUID medicineId, String key, BigDecimal quantity, String note,
            OffsetDateTime occurredAt) {
        Start s = begin(user, medicineId, key, "CONSUMPTION");
        if (s.replay != null) {
            return s.replay;
        }
        StockLevel level = level(user, medicineId, s.today);
        if (level.usable().compareTo(quantity) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_STOCK",
                    "Not enough usable stock for that amount.", null,
                    Map.of("usable", level.usable(), "expired", level.expired()));
        }
        List<Change> changes = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (StockRepository.Lot lot : level.lots()) { // already ordered: earliest expiry first, no-expiry last
            if (remaining.signum() == 0) {
                break;
            }
            if (lot.expiredOn(s.today)) {
                continue;
            }
            BigDecimal take = lot.quantity().min(remaining);
            changes.add(new Change(lot.id(), take.negate()));
            remaining = remaining.subtract(take);
        }
        return finish(user, medicineId, key, "CONSUMPTION", note, occurredAt, null, changes, s.today);
    }

    /**
     * "I counted, there are N usable." {@code expectedUsable} is the number the app showed when the user
     * started counting; if stock changed since (someone logged a dose), the recount is refused so the user
     * can decide, instead of silently overwriting the other change.
     */
    @Transactional
    public Result recount(AppUserPrincipal user, UUID medicineId, String key, BigDecimal countedUsable,
            BigDecimal expectedUsable, String note) {
        Start s = begin(user, medicineId, key, "CORRECTION");
        if (s.replay != null) {
            return s.replay;
        }
        StockLevel level = level(user, medicineId, s.today);
        if (level.usable().compareTo(expectedUsable) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STOCK_CONFLICT",
                    "Stock changed while you were counting. Check the current number and save your count again.",
                    null, Map.of("currentUsable", level.usable()));
        }
        BigDecimal diff = countedUsable.subtract(level.usable());
        List<Change> changes = new ArrayList<>();
        if (diff.signum() < 0) {
            BigDecimal remaining = diff.negate(); // fewer than recorded: remove from earliest-expiring batches
            for (StockRepository.Lot lot : level.lots()) {
                if (remaining.signum() == 0) {
                    break;
                }
                if (lot.expiredOn(s.today)) {
                    continue;
                }
                BigDecimal take = lot.quantity().min(remaining);
                changes.add(new Change(lot.id(), take.negate()));
                remaining = remaining.subtract(take);
            }
        } else if (diff.signum() > 0) {
            // More than recorded: add to the usable batch that expires last, or a new batch without expiry.
            UUID target = level.lots().stream()
                    .filter(l -> !l.expiredOn(s.today))
                    .max(Comparator.comparing(StockRepository.Lot::expiryDate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(StockRepository.Lot::id)
                    .orElseGet(() -> stock.insertLot(user.householdId(), medicineId, null, "Found in recount"));
            changes.add(new Change(target, diff));
        }
        // Even an unchanged count is recorded: it confirms the number (M5's estimate restarts from it).
        return finish(user, medicineId, key, "CORRECTION", note, null, null, changes, s.today);
    }

    /** Throws away a whole batch (normally an expired one). */
    @Transactional
    public Result dispose(AppUserPrincipal user, UUID medicineId, String key, UUID lotId, String note) {
        Start s = begin(user, medicineId, key, "DISPOSAL");
        if (s.replay != null) {
            return s.replay;
        }
        StockRepository.Lot lot = stock.lot(user.householdId(), lotId)
                .filter(l -> l.medicineId().equals(medicineId) && l.quantity().signum() > 0)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Batch not found."));
        return finish(user, medicineId, key, "DISPOSAL", note, null, null,
                List.of(new Change(lot.id(), lot.quantity().negate())), s.today);
    }

    /** Undo: applies the opposite of an earlier movement. The original stays in the history. */
    @Transactional
    public Result reverse(AppUserPrincipal user, UUID movementId, String key, String note) {
        StockRepository.Movement original = stock.movement(user.householdId(), movementId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MOVEMENT_NOT_FOUND", "Entry not found."));
        Start s = begin(user, original.medicineId(), key, "REVERSAL");
        if (s.replay != null) {
            return s.replay;
        }
        // Re-read under the lock: someone may have undone it a moment ago.
        original = stock.movement(user.householdId(), movementId).orElseThrow();
        if ("REVERSAL".equals(original.type())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_UNDO_UNDO", "An undo can't itself be undone.");
        }
        if (original.reversedByMovementId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_UNDONE", "This entry was already undone.");
        }
        List<Change> changes = new ArrayList<>();
        for (StockRepository.Line line : stock.lines(movementId)) {
            BigDecimal current = stock.lot(user.householdId(), line.lotId()).orElseThrow().quantity();
            if (current.add(line.delta().negate()).signum() < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CANNOT_UNDO",
                        "Can't undo: some of that stock has already been used or removed. Use a recount instead.");
            }
            changes.add(new Change(line.lotId(), line.delta().negate()));
        }
        return finish(user, original.medicineId(), key, "REVERSAL", note, null, movementId, changes, s.today);
    }

    // ---------------------------------------------------------------- internals

    private record Change(UUID lotId, BigDecimal delta) {
    }

    private record Start(LocalDate today, Result replay) {
    }

    /**
     * Takes the medicine lock, then checks the idempotency key. Checking after the lock matters: two
     * identical requests arriving together queue on the lock, and the second then sees the first's row.
     */
    private Start begin(AppUserPrincipal user, UUID medicineId, String key, String type) {
        if (!stock.lockMedicine(user.householdId(), medicineId)) {
            throw MedicineService.notFound();
        }
        LocalDate today = today(user);
        var existing = stock.movementIdByKey(user.householdId(), key);
        if (existing.isPresent()) {
            StockRepository.Movement m = stock.movement(user.householdId(), existing.get()).orElseThrow();
            if (!m.medicineId().equals(medicineId) || !m.type().equals(type)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This request id was already used for a different action.");
            }
            return new Start(today, new Result(m, level(user, medicineId, today), true));
        }
        if (medicines.get(user, medicineId).archived()) {
            throw new ApiException(HttpStatus.CONFLICT, "MEDICINE_ARCHIVED", "Restore this medicine first.");
        }
        return new Start(today, null);
    }

    private Result finish(AppUserPrincipal user, UUID medicineId, String key, String type, String note,
            OffsetDateTime occurredAt, UUID reverses, List<Change> changes, LocalDate today) {
        List<BigDecimal> after = new ArrayList<>();
        for (Change c : changes) {
            after.add(stock.changeLot(user.householdId(), c.lotId(), c.delta()));
        }
        BigDecimal usableAfter = level(user, medicineId, today).usable();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime when = occurredAt == null || occurredAt.isAfter(now) ? now : occurredAt;
        UUID movementId = stock.insertMovement(user.householdId(), medicineId, type, note, user.userId(), when,
                usableAfter, reverses, key);
        for (int i = 0; i < changes.size(); i++) {
            stock.insertLine(movementId, changes.get(i).lotId(), changes.get(i).delta(), after.get(i));
        }
        // Same transaction, medicine still locked: a status change queues its email, sent after commit (M5).
        alerts.evaluate(user.householdId(), medicineId);
        StockLevel level = level(user, medicineId, today);
        return new Result(stock.movement(user.householdId(), movementId).orElseThrow(), level, false);
    }
}
