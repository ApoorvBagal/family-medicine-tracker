package com.familymeds.shared.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Lets the app use the connection details Neon provides, without hand-converting them.
 *
 * <p>Precedence:
 * <ol>
 *   <li>{@code DB_URL} set explicitly: used as-is; this class does nothing.</li>
 *   <li>{@code DATABASE_URL_UNPOOLED} in the environment (e.g. on Render).</li>
 *   <li>{@code DATABASE_URL_UNPOOLED} in {@code ./.env.local} (written by {@code neon link}, git-ignored).</li>
 * </ol>
 * The direct (unpooled) URL is used on purpose: Flyway migrations need session-level features that
 * Neon's transaction-mode pooler doesn't support, and this long-running server keeps only a small pool.
 */
public class NeonDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String NEON_VAR = "DATABASE_URL_UNPOOLED";
    static final Path ENV_FILE = Path.of(".env.local");

    private final Log log;

    public NeonDatasourceEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(NeonDatasourceEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty("DB_URL"))) {
            return;
        }
        // Tests turn this off (src/test/resources/application.properties): otherwise .env.local, which points
        // at the dev branch, would override the test database and tests would silently run against dev.
        if (!environment.getProperty("app.neon-env.enabled", Boolean.class, true)) {
            return;
        }
        String source = "environment variable " + NEON_VAR;
        String url = environment.getProperty(NEON_VAR);
        if (!StringUtils.hasText(url)) {
            url = readDotEnv(ENV_FILE, NEON_VAR);
            String branch = readDotEnv(ENV_FILE, "NEON_BRANCH");
            source = ENV_FILE + " (" + NEON_VAR + ", Neon branch '" + (branch != null ? branch : "?") + "')";
            if ("production".equals(branch) && !environment.matchesProfiles("prod")) {
                log.warn("Local run is using the Neon PRODUCTION branch. Use the dev branch instead: neon link --branch dev");
            }
        }
        if (!StringUtils.hasText(url)) {
            return;
        }
        NeonDatabaseUrl db = NeonDatabaseUrl.parse(url);
        environment.getPropertySources().addFirst(new MapPropertySource("neonDatasource", Map.of(
                "spring.datasource.url", db.jdbcUrl(),
                "spring.datasource.username", db.username(),
                "spring.datasource.password", db.password())));
        log.info("Using Neon database from " + source + " (user " + db.username() + ")");
    }

    /** Minimal .env reader: KEY=value or KEY="value", ignores comments and blank lines. */
    static String readDotEnv(Path file, String key) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).strip();
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).strip().equals(key)) {
                    String value = trimmed.substring(eq + 1).strip();
                    if (value.length() >= 2
                            && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    @Override
    public int getOrder() {
        // After Spring's own config loading, so DB_URL from any config source is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
