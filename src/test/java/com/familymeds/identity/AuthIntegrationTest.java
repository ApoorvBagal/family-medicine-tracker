package com.familymeds.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.familymeds.support.Browser;
import com.familymeds.support.TestDatabase;

/**
 * The whole owner lifecycle, in order, against a real Postgres (own empty schema per run):
 * setup → session → logout → login failures → rate limit → login. Steps share state on purpose:
 * setup can only ever happen once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("com.familymeds.support.TestDatabase#available")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    static final String EMAIL = "owner@example.com";
    static final String PASSWORD = "correct horse battery";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    static Browser owner;

    @BeforeAll
    static void reset() {
        owner = null;
    }

    private Browser owner() {
        if (owner == null) {
            owner = new Browser(mvc, "198.51.100.10");
        }
        return owner;
    }

    private static String setupJson(String token, String password) {
        return """
                {"setupToken":"%s","householdName":"Bagal family","displayName":"Owner",
                 "email":"Owner@Example.com","password":"%s"}""".formatted(token, password);
    }

    @Test
    @Order(1)
    void beforeSetup_statusSaysRequired_andApiNeedsLogin() throws Exception {
        owner().get("/api/v1/auth/setup-status").andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(true));
        owner().get("/api/v1/auth/me")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @Order(2)
    void setup_withoutCsrfHeader_isRejected() throws Exception {
        owner().postWithoutCsrf("/api/v1/auth/setup", setupJson(TestDatabase.SETUP_TOKEN, PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    @Order(3)
    void setup_withWrongToken_isRejected() throws Exception {
        owner().post("/api/v1/auth/setup", setupJson("wrong-token", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_TOKEN_INVALID"));
    }

    @Test
    @Order(4)
    void setup_withShortPassword_returnsFieldErrors_withoutEchoingIt() throws Exception {
        owner().post("/api/v1/auth/setup", setupJson(TestDatabase.SETUP_TOKEN, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(content().string(not(containsString("short\""))));
    }

    @Test
    @Order(5)
    void setup_succeeds_andLogsTheOwnerIn() throws Exception {
        String csrfBefore = owner().cookie("XSRF-TOKEN");
        owner().post("/api/v1/auth/setup", setupJson(TestDatabase.SETUP_TOKEN, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(EMAIL)) // normalised to lower case
                .andExpect(jsonPath("$.household.name").value("Bagal family"))
                .andExpect(jsonPath("$.household.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.household.defaultWarnDays").value(5));
        assertThat(owner().cookie("SESSION")).isNotBlank();
        assertThat(owner().cookie("XSRF-TOKEN")).isNotEqualTo(csrfBefore); // rotated on login

        owner().get("/api/v1/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(EMAIL));

        String hash = jdbc.sql("select password_hash from app_user where email = :e").param("e", EMAIL)
                .query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
    }

    @Test
    @Order(6)
    void session_isStoredInPostgres_forThirtyDays_withoutThePasswordHash() {
        Map<String, Object> row = jdbc.sql(
                "select principal_name, max_inactive_interval from spring_session where principal_name = :e")
                .param("e", EMAIL).query().singleRow();
        assertThat(row.get("max_inactive_interval")).isEqualTo(30 * 24 * 3600);
        // Survives app restarts because it's in the database, not in memory.
        byte[] attrs = jdbc.sql("""
                select a.attribute_bytes from spring_session_attributes a
                join spring_session s on s.primary_id = a.session_primary_id
                where s.principal_name = :e and a.attribute_name = 'SPRING_SECURITY_CONTEXT'""")
                .param("e", EMAIL).query(byte[].class).single();
        assertThat(new String(attrs, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("{bcrypt}");
    }

    @Test
    @Order(7)
    void secondSetup_isRefused_evenWithTheRightToken() throws Exception {
        new Browser(mvc, "198.51.100.20")
                .post("/api/v1/auth/setup", setupJson(TestDatabase.SETUP_TOKEN, "another password 123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
        owner().get("/api/v1/auth/setup-status").andExpect(jsonPath("$.setupRequired").value(false));
        assertThat(jdbc.sql("select count(*) from app_user").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void logout_endsTheSession() throws Exception {
        String oldSession = owner().cookie("SESSION");
        assertThat(oldSession).isNotBlank();
        owner().post("/api/v1/auth/logout", "{}").andExpect(status().isNoContent());
        assertThat(owner().cookie("SESSION")).isNull();
        owner().get("/api/v1/auth/me").andExpect(status().isUnauthorized());

        // A copy of the old cookie (e.g. stolen) is useless: the session was deleted server-side.
        new Browser(mvc, "198.51.100.30").withCookie("SESSION", oldSession)
                .get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("select count(*) from spring_session where principal_name = :e").param("e", EMAIL)
                .query(Long.class).single()).isZero();
    }

    @Test
    @Order(9)
    void login_withWrongPassword_givesAGenericError() throws Exception {
        owner().post("/api/v1/auth/login", "{\"email\":\"owner@example.com\",\"password\":\"wrong password!\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        owner().post("/api/v1/auth/login", "{\"email\":\"nobody@example.com\",\"password\":\"wrong password!\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")); // same answer: no account probing
    }

    @Test
    @Order(10)
    void repeatedFailures_fromOneIp_areBlocked_withoutLockingOutTheOwnerElsewhere() throws Exception {
        Browser attacker = new Browser(mvc, "203.0.113.66");
        String body = "{\"email\":\"someone@example.com\",\"password\":\"guess guess guess\"}";
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_IP; i++) {
            attacker.post("/api/v1/auth/login", body).andExpect(status().isUnauthorized());
        }
        attacker.post("/api/v1/auth/login", body)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // Even the right password is refused from the blocked IP...
        attacker.post("/api/v1/auth/login", "{\"email\":\"owner@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @Order(11)
    void login_succeeds_fromTheOwnersDevice() throws Exception {
        owner().post("/api/v1/auth/login", "{\"email\":\"OWNER@example.com \",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Owner"));
        owner().get("/api/v1/auth/me").andExpect(status().isOk());
        assertThat(jdbc.sql("select last_login_at is not null from app_user").query(Boolean.class).single()).isTrue();
    }

    @Test
    @Order(12)
    void loginResponse_neverContainsThePasswordHash() throws Exception {
        owner().get("/api/v1/auth/me").andExpect(content().string(not(containsString("bcrypt"))))
                .andExpect(content().string(not(containsString("password"))));
    }
}
