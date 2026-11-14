package com.familymeds.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every query is scoped by household id. Callers take the household from the logged-in session,
 * never from the request, so one household can't read or change another's medicines (IDOR).
 */
@Repository
public class MedicineRepository {

    private static final String COLUMNS = """
            id, household_id, name, brand_name, strength, dosage_form, unit, min_threshold, daily_dose, warn_days,
            storage_location, notes, archived_at, version, created_at, updated_at""";

    /** Same normalisation as the unique index medicine_active_dedup_uq. */
    private static final String DEDUP_MATCH = """
            household_id = :household and archived_at is null and dosage_form = :form
            and lower(regexp_replace(name, '\\s+', '', 'g')) = lower(regexp_replace(:name, '\\s+', '', 'g'))
            and lower(regexp_replace(coalesce(strength, ''), '\\s+', '', 'g'))
                = lower(regexp_replace(coalesce(cast(:strength as text), ''), '\\s+', '', 'g'))""";

    private final JdbcClient jdbc;

    public MedicineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Medicine insert(UUID householdId, UUID userId, Medicine.Fields f) {
        return jdbc.sql("""
                insert into medicine (household_id, name, brand_name, strength, dosage_form, unit, min_threshold,
                    daily_dose, warn_days, storage_location, notes, created_by)
                values (:household, :name, :brand, :strength, :form, :unit, :min, :dose, :warn, :location, :notes, :user)
                returning\s""" + COLUMNS)
                .paramSource(params(f).addValue("household", householdId).addValue("user", userId))
                .query(MedicineRepository::map)
                .single();
    }

    public Optional<Medicine> find(UUID householdId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from medicine where household_id = :household and id = :id")
                .param("household", householdId)
                .param("id", id)
                .query(MedicineRepository::map)
                .optional();
    }

    public List<Medicine> list(UUID householdId, String search, boolean archived) {
        String pattern = search == null || search.isBlank() ? null : "%" + escapeLike(search.trim()) + "%";
        return jdbc.sql("select " + COLUMNS + """
                 from medicine
                where household_id = :household
                  and (archived_at is not null) = :archived
                  and (cast(:pattern as text) is null or name ilike :pattern or brand_name ilike :pattern)
                order by lower(name), lower(coalesce(strength, ''))""")
                .param("household", householdId)
                .param("archived", archived)
                .param("pattern", pattern)
                .query(MedicineRepository::map)
                .list();
    }

    /** Updates only if the stored version still equals {@code expectedVersion}; empty if it doesn't. */
    public Optional<Medicine> update(UUID householdId, UUID id, long expectedVersion, Medicine.Fields f) {
        return jdbc.sql("""
                update medicine set name = :name, brand_name = :brand, strength = :strength, dosage_form = :form,
                    unit = :unit, min_threshold = :min, daily_dose = :dose, warn_days = :warn,
                    storage_location = :location, notes = :notes, version = version + 1, updated_at = now()
                where household_id = :household and id = :id and version = :version
                returning\s""" + COLUMNS)
                .paramSource(params(f).addValue("household", householdId).addValue("id", id)
                        .addValue("version", expectedVersion))
                .query(MedicineRepository::map)
                .optional();
    }

    public Optional<Medicine> setArchived(UUID householdId, UUID id, boolean archived) {
        return jdbc.sql("""
                update medicine set archived_at = case when :archived then coalesce(archived_at, now()) end,
                    version = version + 1, updated_at = now()
                where household_id = :household and id = :id
                returning\s""" + COLUMNS)
                .param("household", householdId)
                .param("id", id)
                .param("archived", archived)
                .query(MedicineRepository::map)
                .optional();
    }

    /** The active medicine that a create/update/unarchive would duplicate, if any. */
    public Optional<UUID> findActiveDuplicate(UUID householdId, Medicine.Fields f, UUID excludeId) {
        return jdbc.sql("select id from medicine where " + DEDUP_MATCH
                + " and (cast(:exclude as uuid) is null or id <> :exclude) limit 1")
                .param("household", householdId)
                .param("form", f.dosageForm().name())
                .param("name", f.name())
                .param("strength", f.strength())
                .param("exclude", excludeId)
                .query(UUID.class)
                .optional();
    }

    private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource params(Medicine.Fields f) {
        return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("name", f.name())
                .addValue("brand", f.brandName())
                .addValue("strength", f.strength())
                .addValue("form", f.dosageForm().name())
                .addValue("unit", f.unit().name())
                .addValue("min", f.minThreshold())
                .addValue("dose", f.dailyDose())
                .addValue("warn", f.warnDays())
                .addValue("location", f.storageLocation())
                .addValue("notes", f.notes());
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Medicine map(ResultSet rs, int rowNum) throws SQLException {
        return new Medicine(
                rs.getObject("id", UUID.class),
                rs.getObject("household_id", UUID.class),
                rs.getString("name"),
                rs.getString("brand_name"),
                rs.getString("strength"),
                Medicine.DosageForm.valueOf(rs.getString("dosage_form")),
                Medicine.Unit.valueOf(rs.getString("unit")),
                rs.getBigDecimal("min_threshold"),
                rs.getBigDecimal("daily_dose"),
                rs.getInt("warn_days"),
                rs.getString("storage_location"),
                rs.getString("notes"),
                rs.getObject("archived_at", OffsetDateTime.class),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
