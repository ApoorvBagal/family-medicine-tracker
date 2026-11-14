package com.familymeds.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {

    public record UserRow(UUID id, UUID householdId, String email, String displayName, String passwordHash) {
        @Override
        public String toString() {
            return "UserRow[id=" + id + ", householdId=" + householdId + "]"; // never print the hash
        }
    }

    public record HouseholdRow(UUID id, String name, String timezone, int defaultWarnDays) {
    }

    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isInstalled() {
        return jdbc.sql("select exists (select 1 from installation)").query(Boolean.class).single();
    }

    public UUID insertHousehold(String name, String timezone) {
        return jdbc.sql("insert into household (name, timezone) values (:name, :tz) returning id")
                .param("name", name)
                .param("tz", timezone)
                .query(UUID.class)
                .single();
    }

    public UUID insertUser(UUID householdId, String email, String displayName, String passwordHash) {
        return jdbc.sql("""
                insert into app_user (household_id, email, display_name, password_hash)
                values (:household, :email, :name, :hash) returning id""")
                .param("household", householdId)
                .param("email", email)
                .param("name", displayName)
                .param("hash", passwordHash)
                .query(UUID.class)
                .single();
    }

    /** Fails with DuplicateKeyException if setup already completed (primary key id = 1). */
    public void markInstalled(UUID ownerId) {
        jdbc.sql("insert into installation (id, owner_id) values (1, :owner)").param("owner", ownerId).update();
    }

    public Optional<UserRow> findUserByEmail(String email) {
        return jdbc.sql("select id, household_id, email, display_name, password_hash from app_user where email = :email")
                .param("email", email)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getObject("household_id", UUID.class),
                        rs.getString("email"), rs.getString("display_name"), rs.getString("password_hash")))
                .optional();
    }

    public Optional<UserRow> findUserById(UUID id) {
        return jdbc.sql("select id, household_id, email, display_name, password_hash from app_user where id = :id")
                .param("id", id)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getObject("household_id", UUID.class),
                        rs.getString("email"), rs.getString("display_name"), rs.getString("password_hash")))
                .optional();
    }

    public Optional<HouseholdRow> findHousehold(UUID id) {
        return jdbc.sql("select id, name, timezone, default_warn_days from household where id = :id")
                .param("id", id)
                .query((rs, n) -> new HouseholdRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("timezone"), rs.getInt("default_warn_days")))
                .optional();
    }

    public void recordLogin(UUID userId) {
        jdbc.sql("update app_user set last_login_at = now() where id = :id").param("id", userId).update();
    }
}
