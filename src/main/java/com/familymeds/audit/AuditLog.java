package com.familymeds.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records who changed what (non-stock changes). Called inside the same transaction as the change,
 * so an audit row exists if and only if the change committed.
 */
@Component
public class AuditLog {

    /** {@code changes} is {field: {from, to}} for updates, {field: value} for creates. */
    public record AuditEntry(long id, UUID actorUserId, String actorName, String action, Map<String, Object> changes,
            OffsetDateTime occurredAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID householdId, UUID actorUserId, String entityType, UUID entityId, String action,
            Map<String, ?> changes) {
        jdbc.sql("""
                insert into audit_event (household_id, actor_user_id, entity_type, entity_id, action, changes)
                values (:household, :actor, :type, :entity, :action, cast(:changes as jsonb))""")
                .param("household", householdId)
                .param("actor", actorUserId)
                .param("type", entityType)
                .param("entity", entityId)
                .param("action", action)
                .param("changes", json.writeValueAsString(changes))
                .update();
    }

    public List<AuditEntry> history(UUID householdId, String entityType, UUID entityId, int limit) {
        return jdbc.sql("""
                select e.id, e.actor_user_id, u.display_name, e.action, e.changes::text as changes, e.occurred_at
                from audit_event e join app_user u on u.id = e.actor_user_id
                where e.household_id = :household and e.entity_type = :type and e.entity_id = :entity
                order by e.occurred_at desc, e.id desc limit :limit""")
                .param("household", householdId)
                .param("type", entityType)
                .param("entity", entityId)
                .param("limit", limit)
                .query((rs, n) -> new AuditEntry(rs.getLong("id"), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("display_name"), rs.getString("action"), parse(rs.getString("changes")),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String changesJson) {
        return json.readValue(changesJson, Map.class);
    }
}
