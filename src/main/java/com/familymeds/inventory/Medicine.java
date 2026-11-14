package com.familymeds.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A medicine as stored. Stock quantities are not here: they live in batches from M3. */
public record Medicine(
        UUID id,
        UUID householdId,
        String name,
        String brandName,
        String strength,
        DosageForm dosageForm,
        Unit unit,
        BigDecimal minThreshold,
        BigDecimal dailyDose,
        int warnDays,
        String storageLocation,
        String notes,
        OffsetDateTime archivedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum DosageForm { TABLET, CAPSULE, SYRUP, DROPS, INHALER, CREAM, INJECTION, OTHER }

    public enum Unit { TABLET, CAPSULE, ML, BOTTLE, STRIP, TUBE, PIECE, OTHER }

    /** The fields a user edits; used for creates, updates and audit diffs. */
    public record Fields(String name, String brandName, String strength, DosageForm dosageForm, Unit unit,
            BigDecimal minThreshold, BigDecimal dailyDose, int warnDays, String storageLocation, String notes) {

        /** Field-by-field changes {field: {from, to}} for the audit log. */
        public Map<String, Map<String, Object>> diff(Fields after) {
            Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
            compare(changes, "name", name, after.name);
            compare(changes, "brandName", brandName, after.brandName);
            compare(changes, "strength", strength, after.strength);
            compare(changes, "dosageForm", dosageForm, after.dosageForm);
            compare(changes, "unit", unit, after.unit);
            compare(changes, "minThreshold", minThreshold, after.minThreshold);
            compare(changes, "dailyDose", dailyDose, after.dailyDose);
            compare(changes, "warnDays", warnDays, after.warnDays);
            compare(changes, "storageLocation", storageLocation, after.storageLocation);
            compare(changes, "notes", notes, after.notes);
            return changes;
        }

        /** All non-null fields, for the audit entry of a newly created medicine. */
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            Fields empty = new Fields(null, null, null, null, null, null, null, 0, null, null);
            empty.diff(this).forEach((k, v) -> m.put(k, v.get("to")));
            return m;
        }

        private static void compare(Map<String, Map<String, Object>> out, String field, Object before, Object after) {
            boolean same = before instanceof BigDecimal b && after instanceof BigDecimal a
                    ? b.compareTo(a) == 0
                    : Objects.equals(before, after);
            if (!same) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", before);
                change.put("to", after);
                out.put(field, change);
            }
        }
    }

    public Fields fields() {
        return new Fields(name, brandName, strength, dosageForm, unit, minThreshold, dailyDose, warnDays,
                storageLocation, notes);
    }

    public boolean archived() {
        return archivedAt != null;
    }
}
