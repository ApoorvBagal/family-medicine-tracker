package com.familymeds.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.familymeds.shared.config.NeonDatabaseUrl;

/**
 * Chooses the real Postgres that database tests run against:
 * <ol>
 *   <li>{@code TEST_DATABASE_URL} set: that database, normally the Neon {@code test} branch
 *       (use {@code scripts/test-with-neon.ps1}, which fills it from the Neon CLI).</li>
 *   <li>Docker available (CI): a throwaway Postgres 18 container, shared by all tests in the run.</li>
 *   <li>Neither: tests are skipped locally. <b>In CI they are never skipped</b>, so a broken Docker
 *       setup fails the build instead of silently passing.</li>
 * </ol>
 * Each test run (JVM) gets its <b>own empty schema</b>, {@code test_<epochSeconds>_<random>}, which
 * Flyway migrates from scratch and which is dropped at the end. Schemas left behind by crashed runs
 * are dropped after a day. Only names matching that exact pattern are ever dropped.
 */
public final class TestDatabase {

    static final String ENV = "TEST_DATABASE_URL";
    static final String POSTGRES_IMAGE = "postgres:18-alpine";
    public static final String SETUP_TOKEN = "test-setup-token-0123456789";
    private static final Pattern RUN_SCHEMA = Pattern.compile("test_(\\d{10})_[0-9a-f]{8}");

    private static PostgreSQLContainer container;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private static String schema;

    private TestDatabase() {
    }

    /** This run's schema; tests assert the app is really connected to it. */
    public static synchronized String schema() {
        return schema;
    }

    /** Used by {@code @EnabledIf}. */
    public static boolean available() {
        return hasTestUrl() || "true".equalsIgnoreCase(System.getenv("CI"))
                || DockerClientFactory.instance().isDockerAvailable();
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        if (schema == null) {
            initialize();
        }
        registry.add("spring.datasource.url", () -> jdbcUrl);
        // Set per connection with SQL: Neon's proxy ignores the JDBC "currentSchema" startup option.
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + schema);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("app.setup-token", () -> SETUP_TOKEN);
    }

    private static void initialize() {
        if (hasTestUrl()) {
            NeonDatabaseUrl db = NeonDatabaseUrl.parse(System.getenv(ENV));
            jdbcUrl = db.jdbcUrl();
            username = db.username();
            password = db.password();
        } else {
            container = new PostgreSQLContainer(POSTGRES_IMAGE);
            container.start(); // stopped automatically by Testcontainers when the JVM exits
            jdbcUrl = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
        schema = "test_" + Instant.now().getEpochSecond() + "_"
                + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        dropStaleSchemas();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> dropSchema(schema), "drop-test-schema"));
    }

    private static void dropStaleSchemas() {
        long cutoff = Instant.now().minus(Duration.ofDays(1)).getEpochSecond();
        List<String> stale = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select nspname from pg_namespace where nspname like 'test\\_%'")) {
            while (rs.next()) {
                Matcher m = RUN_SCHEMA.matcher(rs.getString(1));
                if (m.matches() && Long.parseLong(m.group(1)) < cutoff) {
                    stale.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reach the test database", e);
        }
        stale.forEach(TestDatabase::dropSchema);
    }

    private static void dropSchema(String name) {
        if (!RUN_SCHEMA.matcher(name).matches()) {
            throw new IllegalArgumentException("Refusing to drop schema " + name);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("set statement_timeout = '10s'");
            s.execute("drop schema if exists " + name + " cascade");
        } catch (SQLException e) {
            System.err.println("Could not drop test schema " + name + ": " + e.getMessage());
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static boolean hasTestUrl() {
        String url = System.getenv(ENV);
        return url != null && !url.isBlank();
    }
}
