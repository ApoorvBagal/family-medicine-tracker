package com.familymeds.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.shared.web.ApiException;

/**
 * Stock changes. Every POST needs an {@code Idempotency-Key} header (a fresh random id per user action);
 * sending the same request again with the same key returns the first result instead of repeating it.
 */
@RestController
public class StockController {

    static final String KEY_HEADER = "Idempotency-Key";

    public record PurchaseRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999") @Digits(integer = 5, fraction = 2)
            BigDecimal quantity,
            LocalDate expiryDate,
            @Size(max = 100) String label,
            @Size(max = 500) String note,
            OffsetDateTime occurredAt) {
    }

    public record ConsumptionRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999") @Digits(integer = 4, fraction = 2)
            BigDecimal quantity,
            @Size(max = 500) String note,
            OffsetDateTime occurredAt) {
    }

    public record RecountRequest(
            @NotNull @DecimalMin("0") @DecimalMax("99999") @Digits(integer = 5, fraction = 2) BigDecimal countedUsable,
            @NotNull @DecimalMin("0") BigDecimal expectedUsable,
            @Size(max = 500) String note) {
    }

    public record DisposalRequest(@NotNull UUID lotId, @Size(max = 500) String note) {
    }

    public record ReversalRequest(@Size(max = 500) String note) {
    }

    public record LotView(UUID id, LocalDate expiryDate, BigDecimal quantity, String label, boolean expired) {
    }

    public record StockView(BigDecimal usableQuantity, BigDecimal expiredQuantity, LocalDate nextExpiry,
            List<LotView> lots) {

        static StockView of(StockService.StockLevel level) {
            List<LotView> lots = level.lots().stream()
                    .map(l -> new LotView(l.id(), l.expiryDate(), l.quantity(), l.label(), l.expiredOn(level.today())))
                    .toList();
            LocalDate next = lots.stream().filter(l -> !l.expired() && l.expiryDate() != null)
                    .map(LotView::expiryDate).findFirst().orElse(null);
            return new StockView(level.usable(), level.expired(), next, lots);
        }
    }

    public record MovementView(UUID id, String type, BigDecimal change, BigDecimal usableAfter, String note,
            String performedBy, OffsetDateTime occurredAt, OffsetDateTime recordedAt, UUID reversesMovementId,
            boolean undone) {

        static MovementView of(StockRepository.Movement m) {
            return new MovementView(m.id(), m.type(), m.totalDelta(), m.usableAfter(), m.note(), m.performedByName(),
                    m.occurredAt(), m.recordedAt(), m.reversesMovementId(), m.reversedByMovementId() != null);
        }
    }

    public record ChangeResponse(MovementView movement, StockView stock) {
    }

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/medicines/{id}/stock")
    public StockView stock(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        return StockView.of(service.level(user, id));
    }

    @GetMapping("/api/v1/medicines/{id}/movements")
    public List<MovementView> movements(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable UUID id) {
        return service.history(user, id).stream().map(MovementView::of).toList();
    }

    @PostMapping("/api/v1/medicines/{id}/purchases")
    public ResponseEntity<ChangeResponse> purchase(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id, @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody PurchaseRequest body) {
        return respond(service.purchase(user, id, requireKey(key), body.quantity(), body.expiryDate(),
                clean(body.label()), clean(body.note()), body.occurredAt()));
    }

    @PostMapping("/api/v1/medicines/{id}/consumptions")
    public ResponseEntity<ChangeResponse> consume(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id, @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody ConsumptionRequest body) {
        return respond(service.consume(user, id, requireKey(key), body.quantity(), clean(body.note()),
                body.occurredAt()));
    }

    @PostMapping("/api/v1/medicines/{id}/recounts")
    public ResponseEntity<ChangeResponse> recount(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id, @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody RecountRequest body) {
        return respond(service.recount(user, id, requireKey(key), body.countedUsable(), body.expectedUsable(),
                clean(body.note())));
    }

    @PostMapping("/api/v1/medicines/{id}/disposals")
    public ResponseEntity<ChangeResponse> dispose(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id, @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody DisposalRequest body) {
        return respond(service.dispose(user, id, requireKey(key), body.lotId(), clean(body.note())));
    }

    @PostMapping("/api/v1/stock-movements/{movementId}/reversal")
    public ResponseEntity<ChangeResponse> reverse(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID movementId, @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody(required = false) ReversalRequest body) {
        return respond(service.reverse(user, movementId, requireKey(key), body == null ? null : clean(body.note())));
    }

    /** 201 for a new change; 200 when the same request was already applied (replay). */
    private static ResponseEntity<ChangeResponse> respond(StockService.Result r) {
        ChangeResponse body = new ChangeResponse(MovementView.of(r.movement()), StockView.of(r.stock()));
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
                .body(body);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (a new random id for each action).");
        }
        String k = key.trim();
        if (k.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key is too long.");
        }
        return k;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
