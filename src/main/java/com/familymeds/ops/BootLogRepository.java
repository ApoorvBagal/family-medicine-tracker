package com.familymeds.ops;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BootLogRepository {

    public record BootEntry(long id, OffsetDateTime bootedAt, String appVersion, String host) {
    }

    private final JdbcClient jdbc;

    public BootLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String appVersion, String host) {
        jdbc.sql("insert into app_boot_log (app_version, host) values (:version, :host)")
                .param("version", appVersion)
                .param("host", host)
                .update();
    }

    public long count() {
        return jdbc.sql("select count(*) from app_boot_log").query(Long.class).single();
    }

    public List<BootEntry> recent(int limit) {
        return jdbc.sql("select id, booted_at, app_version, host from app_boot_log order by id desc limit :limit")
                .param("limit", limit)
                .query((rs, n) -> new BootEntry(
                        rs.getLong("id"),
                        rs.getObject("booted_at", OffsetDateTime.class),
                        rs.getString("app_version"),
                        rs.getString("host")))
                .list();
    }
}
