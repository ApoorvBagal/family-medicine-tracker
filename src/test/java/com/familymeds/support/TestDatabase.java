package com.familymeds.support;

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
 * Tests must not depend on the database being empty: the Neon test branch keeps data between runs.
 */
public final class TestDatabase {

    static final String ENV = "TEST_DATABASE_URL";
    static final String POSTGRES_IMAGE = "postgres:18-alpine";

    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    /** Used by {@code @EnabledIf}. */
    public static boolean available() {
        return hasTestUrl() || "true".equalsIgnoreCase(System.getenv("CI"))
                || DockerClientFactory.instance().isDockerAvailable();
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        if (hasTestUrl()) {
            NeonDatabaseUrl db = NeonDatabaseUrl.parse(System.getenv(ENV));
            registry.add("spring.datasource.url", db::jdbcUrl);
            registry.add("spring.datasource.username", db::username);
            registry.add("spring.datasource.password", db::password);
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer(POSTGRES_IMAGE);
            container.start(); // stopped automatically by Testcontainers when the JVM exits
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static boolean hasTestUrl() {
        String url = System.getenv(ENV);
        return url != null && !url.isBlank();
    }
}
