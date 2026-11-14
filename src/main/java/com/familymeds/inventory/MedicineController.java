package com.familymeds.inventory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.familymeds.audit.AuditLog;
import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.shared.web.ApiException;

/**
 * Medicines of the logged-in user's household. Single-medicine responses carry an {@code ETag}
 * ("v&lt;version&gt;"); updates must send it back in {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {

    public record MedicineRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String brandName,
            @Size(max = 50) String strength,
            @NotNull Medicine.DosageForm dosageForm,
            @NotNull Medicine.Unit unit,
            @DecimalMin("0") @DecimalMax("99999") @Digits(integer = 5, fraction = 2) BigDecimal minThreshold,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999") @Digits(integer = 4, fraction = 2)
            BigDecimal dailyDose,
            @Min(0) @Max(60) Integer warnDays,
            @Size(max = 100) String storageLocation,
            @Size(max = 1000) String notes) {

        Medicine.Fields toFields() {
            return new Medicine.Fields(clean(name), clean(brandName), clean(strength), dosageForm, unit,
                    minThreshold == null ? BigDecimal.ZERO : minThreshold, dailyDose,
                    warnDays == null ? DEFAULT_WARN_DAYS : warnDays, clean(storageLocation), clean(notes));
        }

        /** Trim, collapse inner whitespace, and turn blanks into "not set". */
        private static String clean(String s) {
            if (s == null) {
                return null;
            }
            String t = s.trim().replaceAll("\\s+", " ");
            return t.isEmpty() ? null : t;
        }
    }

    public record MedicineView(UUID id, String name, String brandName, String strength,
            Medicine.DosageForm dosageForm, Medicine.Unit unit, BigDecimal minThreshold, BigDecimal dailyDose,
            int warnDays, String storageLocation, String notes, boolean archived, OffsetDateTime archivedAt,
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            BigDecimal usableQuantity, BigDecimal expiredQuantity, BigDecimal estimatedQuantity, Integer daysLeft,
            String alertStatus) {

        static MedicineView of(Medicine m) {
            return of(m, null, null);
        }

        /** {@code totals} = {usable, expired}; filled in on the list so it can show stock at a glance. */
        static MedicineView of(Medicine m, BigDecimal[] totals, com.familymeds.alerts.Runway runway) {
            BigDecimal usable = totals == null ? BigDecimal.ZERO : totals[0];
            BigDecimal expired = totals == null ? BigDecimal.ZERO : totals[1];
            return new MedicineView(m.id(), m.name(), m.brandName(), m.strength(), m.dosageForm(), m.unit(),
                    m.minThreshold(), m.dailyDose(), m.warnDays(), m.storageLocation(), m.notes(), m.archived(),
                    m.archivedAt(), m.version(), m.createdAt(), m.updatedAt(), usable, expired,
                    runway == null ? null : runway.estimated(), runway == null ? null : runway.daysLeft(),
                    runway == null ? null : runway.status().name());
        }
    }

    public record HistoryEntry(String action, String actorName, java.util.Map<String, Object> changes,
            OffsetDateTime occurredAt) {
        static HistoryEntry of(AuditLog.AuditEntry e) {
            return new HistoryEntry(e.action(), e.actorName(), e.changes(), e.occurredAt());
        }
    }

    /** D11 default; per-household defaults can feed this later. */
    static final int DEFAULT_WARN_DAYS = 5;

    private final MedicineService service;
    private final StockService stock;
    private final com.familymeds.alerts.AlertService alerts;

    public MedicineController(MedicineService service, StockService stock,
            com.familymeds.alerts.AlertService alerts) {
        this.service = service;
        this.stock = stock;
        this.alerts = alerts;
    }

    @GetMapping
    public List<MedicineView> list(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "false") boolean archived) {
        var totals = stock.totals(user);
        return service.list(user, q, archived).stream().map(m -> {
            BigDecimal[] t = totals.get(m.id());
            var runway = alerts.runway(user.householdId(), m, t == null ? BigDecimal.ZERO : t[0]);
            return MedicineView.of(m, t, runway);
        }).toList();
    }

    @PostMapping
    public ResponseEntity<MedicineView> create(@AuthenticationPrincipal AppUserPrincipal user,
            @Valid @RequestBody MedicineRequest body) {
        Medicine created = service.create(user, body.toFields());
        return ResponseEntity.created(URI.create("/api/v1/medicines/" + created.id()))
                .eTag(etag(created)).body(MedicineView.of(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MedicineView> get(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        Medicine m = service.get(user, id);
        return ResponseEntity.ok().eTag(etag(m)).body(MedicineView.of(m));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MedicineView> update(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MedicineRequest body) {
        Medicine updated = service.update(user, id, parseVersion(ifMatch), body.toFields());
        return ResponseEntity.ok().eTag(etag(updated)).body(MedicineView.of(updated));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<MedicineView> archive(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        Medicine m = service.setArchived(user, id, true);
        return ResponseEntity.ok().eTag(etag(m)).body(MedicineView.of(m));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<MedicineView> restore(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        Medicine m = service.setArchived(user, id, false);
        return ResponseEntity.ok().eTag(etag(m)).body(MedicineView.of(m));
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        return service.history(user, id).stream().map(HistoryEntry::of).toList();
    }

    static String etag(Medicine m) {
        return "\"v" + m.version() + "\"";
    }

    static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                    "Send the If-Match header with the ETag you received, so concurrent edits aren't lost.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        if (!v.matches("v\\d{1,18}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IF_MATCH_INVALID", "Malformed If-Match header.");
        }
        return Long.parseLong(v.substring(1));
    }
}
