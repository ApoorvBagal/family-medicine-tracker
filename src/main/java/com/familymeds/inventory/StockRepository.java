package com.familymeds.inventory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Batches and the stock ledger. All queries are household-scoped. */
@Repository
public class StockRepository {

    public record Lot(UUID id, UUID medicineId, LocalDate expiryDate, BigDecimal quantity, String label,
            OffsetDateTime createdAt) {

        public boolean expiredOn(LocalDate today) {
            return expiryDate != null && expiryDate.isBefore(today);
        }
    }

    public record Movement(UUID id, UUID medicineId, String type, String note, UUID performedBy,
            String performedByName, OffsetDateTime occurredAt, OffsetDateTime recordedAt, BigDecimal usableAfter,
            UUID reversesMovementId, UUID reversedByMovementId, String idempotencyKey, BigDecimal totalDelta) {
    }

    public record Line(UUID lotId, BigDecimal delta, BigDecimal lotQuantityAfter) {
    }

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the medicine row until the transaction ends. Every stock change takes this lock first, so
     * changes to one medicine (all its batches) happen one after another, never interleaved.
     */
    public boolean lockMedicine(UUID householdId, UUID medicineId) {
        return jdbc.sql("select id from medicine where household_id = :h and id = :id for update")
                .param("h", householdId)
                .param("id", medicineId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public List<Lot> lots(UUID householdId, UUID medicineId) {
        return jdbc.sql("""
                select id, medicine_id, expiry_date, quantity, label, created_at from stock_lot
                where household_id = :h and medicine_id = :m and quantity > 0
                order by expiry_date asc nulls last, created_at asc""")
                .param("h", householdId)
                .param("m", medicineId)
                .query(StockRepository::mapLot)
                .list();
    }

    public Optional<Lot> lot(UUID householdId, UUID lotId) {
        return jdbc.sql("select id, medicine_id, expiry_date, quantity, label, created_at from stock_lot "
                + "where household_id = :h and id = :id")
                .param("h", householdId)
                .param("id", lotId)
                .query(StockRepository::mapLot)
                .optional();
    }

    public UUID insertLot(UUID householdId, UUID medicineId, LocalDate expiry, String label) {
        return jdbc.sql("""
                insert into stock_lot (household_id, medicine_id, expiry_date, quantity, label)
                values (:h, :m, :expiry, 0, :label) returning id""")
                .param("h", householdId)
                .param("m", medicineId)
                .param("expiry", expiry)
                .param("label", label)
                .query(UUID.class)
                .single();
    }

    /** Applies a change to one batch and returns the new quantity (the CHECK constraint forbids < 0). */
    public BigDecimal changeLot(UUID householdId, UUID lotId, BigDecimal delta) {
        return jdbc.sql("update stock_lot set quantity = quantity + :d where household_id = :h and id = :id "
                + "returning quantity")
                .param("d", delta)
                .param("h", householdId)
                .param("id", lotId)
                .query(BigDecimal.class)
                .single();
    }

    public Optional<UUID> movementIdByKey(UUID householdId, String key) {
        return jdbc.sql("select id from stock_movement where household_id = :h and idempotency_key = :k")
                .param("h", householdId)
                .param("k", key)
                .query(UUID.class)
                .optional();
    }

    public UUID insertMovement(UUID householdId, UUID medicineId, String type, String note, UUID userId,
            OffsetDateTime occurredAt, BigDecimal usableAfter, UUID reverses, String key) {
        return jdbc.sql("""
                insert into stock_movement (household_id, medicine_id, type, note, performed_by, occurred_at,
                    usable_after, reverses_movement_id, idempotency_key)
                values (:h, :m, :type, :note, :user, :occurred, :usable, :reverses, :key) returning id""")
                .param("h", householdId)
                .param("m", medicineId)
                .param("type", type)
                .param("note", note)
                .param("user", userId)
                .param("occurred", occurredAt)
                .param("usable", usableAfter)
                .param("reverses", reverses)
                .param("key", key)
                .query(UUID.class)
                .single();
    }

    public void insertLine(UUID movementId, UUID lotId, BigDecimal delta, BigDecimal lotQuantityAfter) {
        jdbc.sql("insert into stock_movement_line (movement_id, lot_id, delta, lot_quantity_after) "
                + "values (:mv, :lot, :d, :after)")
                .param("mv", movementId)
                .param("lot", lotId)
                .param("d", delta)
                .param("after", lotQuantityAfter)
                .update();
    }

    public List<Line> lines(UUID movementId) {
        return jdbc.sql("select lot_id, delta, lot_quantity_after from stock_movement_line where movement_id = :mv "
                + "order by id")
                .param("mv", movementId)
                .query((rs, n) -> new Line(rs.getObject("lot_id", UUID.class), rs.getBigDecimal("delta"),
                        rs.getBigDecimal("lot_quantity_after")))
                .list();
    }

    private static final String MOVEMENT_SELECT = """
            select m.id, m.medicine_id, m.type, m.note, m.performed_by, u.display_name, m.occurred_at, m.recorded_at,
                   m.usable_after, m.reverses_movement_id, r.id as reversed_by, m.idempotency_key,
                   coalesce((select sum(l.delta) from stock_movement_line l where l.movement_id = m.id), 0) as total_delta
            from stock_movement m
            join app_user u on u.id = m.performed_by
            left join stock_movement r on r.reverses_movement_id = m.id
            """;

    public Optional<Movement> movement(UUID householdId, UUID movementId) {
        return jdbc.sql(MOVEMENT_SELECT + " where m.household_id = :h and m.id = :id")
                .param("h", householdId)
                .param("id", movementId)
                .query(StockRepository::mapMovement)
                .optional();
    }

    public List<Movement> movements(UUID householdId, UUID medicineId, int limit) {
        return jdbc.sql(MOVEMENT_SELECT
                + " where m.household_id = :h and m.medicine_id = :med order by m.recorded_at desc, m.id desc limit :n")
                .param("h", householdId)
                .param("med", medicineId)
                .param("n", limit)
                .query(StockRepository::mapMovement)
                .list();
    }

    /** Usable and expired totals for every active medicine of a household (for the list screen). */
    public Map<UUID, BigDecimal[]> totalsByMedicine(UUID householdId, LocalDate today) {
        Map<UUID, BigDecimal[]> totals = new java.util.HashMap<>();
        jdbc.sql("""
                select medicine_id,
                       coalesce(sum(quantity) filter (where expiry_date is null or expiry_date >= :today), 0) as usable,
                       coalesce(sum(quantity) filter (where expiry_date < :today), 0) as expired
                from stock_lot where household_id = :h and quantity > 0 group by medicine_id""")
                .param("h", householdId)
                .param("today", today)
                .query((ResultSet rs) -> {
                    totals.put(rs.getObject("medicine_id", UUID.class),
                            new BigDecimal[] {rs.getBigDecimal("usable"), rs.getBigDecimal("expired")});
                });
        return totals;
    }

    private static Lot mapLot(ResultSet rs, int n) throws SQLException {
        return new Lot(rs.getObject("id", UUID.class), rs.getObject("medicine_id", UUID.class),
                rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("quantity"), rs.getString("label"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static Movement mapMovement(ResultSet rs, int n) throws SQLException {
        return new Movement(rs.getObject("id", UUID.class), rs.getObject("medicine_id", UUID.class),
                rs.getString("type"), rs.getString("note"), rs.getObject("performed_by", UUID.class),
                rs.getString("display_name"), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class), rs.getBigDecimal("usable_after"),
                rs.getObject("reverses_movement_id", UUID.class), rs.getObject("reversed_by", UUID.class),
                rs.getString("idempotency_key"), rs.getBigDecimal("total_delta"));
    }
}
