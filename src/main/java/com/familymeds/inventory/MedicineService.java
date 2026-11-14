package com.familymeds.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.familymeds.audit.AuditLog;
import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.shared.web.ApiException;

@Service
public class MedicineService {

    static final String ENTITY = "MEDICINE";

    private final MedicineRepository repository;
    private final AuditLog audit;

    public MedicineService(MedicineRepository repository, AuditLog audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<Medicine> list(AppUserPrincipal user, String search, boolean archived) {
        return repository.list(user.householdId(), search, archived);
    }

    public Medicine get(AppUserPrincipal user, UUID id) {
        return repository.find(user.householdId(), id).orElseThrow(MedicineService::notFound);
    }

    public List<AuditLog.AuditEntry> history(AppUserPrincipal user, UUID id) {
        get(user, id); // 404 for other households' ids
        return audit.history(user.householdId(), ENTITY, id, 50);
    }

    @Transactional
    public Medicine create(AppUserPrincipal user, Medicine.Fields fields) {
        rejectDuplicate(user.householdId(), fields, null);
        Medicine created;
        try {
            created = repository.insert(user.householdId(), user.userId(), fields);
        } catch (DuplicateKeyException e) {
            throw duplicate(null); // lost a race with a concurrent identical create
        }
        audit.record(user.householdId(), user.userId(), ENTITY, created.id(), "CREATED", fields.snapshot());
        return created;
    }

    /**
     * Optimistic locking: the client sends the version it last saw (If-Match). If someone else saved in
     * between, the update is refused with 412 instead of silently overwriting their change.
     */
    @Transactional
    public Medicine update(AppUserPrincipal user, UUID id, long expectedVersion, Medicine.Fields fields) {
        Medicine current = get(user, id);
        if (current.archived()) {
            throw new ApiException(HttpStatus.CONFLICT, "MEDICINE_ARCHIVED", "Restore this medicine before editing it.");
        }
        if (current.version() != expectedVersion) {
            throw stale(current.version());
        }
        Map<String, Map<String, Object>> changes = current.fields().diff(fields);
        if (changes.isEmpty()) {
            return current; // nothing changed: no new version, no audit noise
        }
        rejectDuplicate(user.householdId(), fields, id);
        Medicine updated;
        try {
            updated = repository.update(user.householdId(), id, expectedVersion, fields)
                    .orElseThrow(() -> stale(get(user, id).version()));
        } catch (DuplicateKeyException e) {
            throw duplicate(null);
        }
        audit.record(user.householdId(), user.userId(), ENTITY, id, "UPDATED", changes);
        return updated;
    }

    @Transactional
    public Medicine setArchived(AppUserPrincipal user, UUID id, boolean archive) {
        Medicine current = get(user, id);
        if (current.archived() == archive) {
            return current; // already in that state
        }
        if (!archive) {
            rejectDuplicate(user.householdId(), current.fields(), id);
        }
        Medicine changed;
        try {
            changed = repository.setArchived(user.householdId(), id, archive).orElseThrow(MedicineService::notFound);
        } catch (DuplicateKeyException e) {
            throw duplicate(null);
        }
        audit.record(user.householdId(), user.userId(), ENTITY, id, archive ? "ARCHIVED" : "RESTORED", Map.of());
        return changed;
    }

    private void rejectDuplicate(UUID householdId, Medicine.Fields fields, UUID excludeId) {
        repository.findActiveDuplicate(householdId, fields, excludeId).ifPresent(existing -> {
            throw duplicate(existing);
        });
    }

    private static ApiException duplicate(UUID existingId) {
        Map<String, Object> props = new HashMap<>();
        if (existingId != null) {
            props.put("existingId", existingId);
        }
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_MEDICINE",
                "This medicine (same name, strength and form) already exists.", null, props);
    }

    private static ApiException stale(long currentVersion) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE_VERSION",
                "This medicine was changed by someone else. Reload it and apply your change again.", null,
                Map.of("currentVersion", currentVersion));
    }

    static ApiException notFound() {
        // Also returned for other households' ids, so ids can't be probed.
        return new ApiException(HttpStatus.NOT_FOUND, "MEDICINE_NOT_FOUND", "Medicine not found.");
    }
}
