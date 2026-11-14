package com.familymeds.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeonDatabaseUrlTest {

    @Test
    void convertsNeonUrlToJdbcWithVerifiedTls() {
        var db = NeonDatabaseUrl.parse(
                "postgresql://app_user:s3cr%40t@ep-cool-1.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://ep-cool-1.ap-southeast-1.aws.neon.tech/neondb"
                + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require");
        assertThat(db.username()).isEqualTo("app_user");
        assertThat(db.password()).isEqualTo("s3cr@t"); // URL-decoded
    }

    @Test
    void keepsExplicitPortAndUnknownParams() {
        var db = NeonDatabaseUrl.parse("postgres://u:p@host:6543/db?application_name=meds");

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://host:6543/db"
                + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&application_name=meds");
    }

    @Test
    void neverPrintsPassword() {
        var db = NeonDatabaseUrl.parse("postgresql://u:topsecret@host/db");

        assertThat(db.toString()).doesNotContain("topsecret");
    }

    @Test
    void rejectsNonPostgresOrCredentiallessUrls() {
        assertThatThrownBy(() -> NeonDatabaseUrl.parse("mysql://u:p@host/db"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NeonDatabaseUrl.parse("postgresql://host/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsQuotedValueFromDotEnv(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env.local");
        Files.writeString(env, """
                # Neon
                DATABASE_URL="postgresql://u:p@host-pooler/db"
                DATABASE_URL_UNPOOLED="postgresql://u:p@host/db"
                NEON_BRANCH=production
                """);

        assertThat(NeonDatasourceEnvironmentPostProcessor.readDotEnv(env, "DATABASE_URL_UNPOOLED"))
                .isEqualTo("postgresql://u:p@host/db");
        assertThat(NeonDatasourceEnvironmentPostProcessor.readDotEnv(env, "MISSING")).isNull();
        assertThat(NeonDatasourceEnvironmentPostProcessor.readDotEnv(dir.resolve("nope"), "X")).isNull();
    }
}
