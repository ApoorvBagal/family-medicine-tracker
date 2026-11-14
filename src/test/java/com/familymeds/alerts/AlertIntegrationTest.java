package com.familymeds.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.familymeds.identity.IdentityRepository;
import com.familymeds.support.Browser;
import com.familymeds.support.TestDatabase;
import com.familymeds.support.TestUsers;
import com.jayway.jsonpath.JsonPath;

/** Low-stock alerts end to end: stock changes and the daily job queue emails; the outbox sends them. */
@SpringBootTest(properties = {"app.job-token=test-job-token-123", "app.base-url=https://meds.example.test"})
@AutoConfigureMockMvc
@Import(AlertIntegrationTest.Recording.class)
@EnabledIf("com.familymeds.support.TestDatabase#available")
class AlertIntegrationTest {

    /** Captures emails instead of sending them. */
    @TestConfiguration
    static class Recording {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    static class RecordingSender implements EmailSender {
        final List<Email> sent = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public void send(Email email) {
            if (fail) {
                throw new IllegalStateException("provider down");
            }
            sent.add(email);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IdentityRepository identity;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    RecordingSender emails;

    @Autowired
    DailyJob daily;

    @Autowired
    OutboxSender outbox;

    @Autowired
    Clock clock;

    static Browser owner;

    @BeforeEach
    void loginOnce() throws Exception {
        if (owner == null) {
            owner = TestUsers.owner(mvc);
        }
        emails.fail = false;
    }

    // ------------------------------------------------------------ helpers

    /** A regular medicine: 2 tablets a day, warn at 5 days left. Returns {id, name}. */
    private String[] newMedicine(String extra) throws Exception {
        String name = "Med " + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"name":"%s","dosageForm":"TABLET","unit":"TABLET"%s}""".formatted(name, extra);
        String id = JsonPath.read(owner.post("/api/v1/medicines", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        return new String[] {id, name};
    }

    private String[] regular() throws Exception {
        return newMedicine(",\"dailyDose\":2");
    }

    private ResultActions act(String path, String json) throws Exception {
        return owner.post(path, json, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private ResultActions buy(String med, int qty) throws Exception {
        return act("/api/v1/medicines/" + med + "/purchases", "{\"quantity\":" + qty + "}")
                .andExpect(status().isCreated());
    }

    private ResultActions take(String med, int qty) throws Exception {
        return act("/api/v1/medicines/" + med + "/consumptions", "{\"quantity\":" + qty + "}")
                .andExpect(status().isCreated());
    }

    /** Emails to the owner about this medicine, waiting briefly for the after-commit sender. */
    private List<String> subjectsFor(String name, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<String> subjects;
        do {
            subjects = emails.sent.stream()
                    .filter(e -> e.toEmail().equals(TestUsers.OWNER_EMAIL) && e.subject().contains(name))
                    .map(EmailSender.Email::subject).toList();
            if (subjects.size() >= expected) {
                break;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        Thread.sleep(200); // and make sure nothing extra arrives
        return emails.sent.stream()
                .filter(e -> e.toEmail().equals(TestUsers.OWNER_EMAIL) && e.subject().contains(name))
                .map(EmailSender.Email::subject).toList();
    }

    private String alertStatus(String med) throws Exception {
        return JsonPath.read(owner.get("/api/v1/medicines/" + med + "/stock").andReturn().getResponse()
                .getContentAsString(), "$.alertStatus");
    }

    // ------------------------------------------------------------ tests

    @Test
    void fiveDaysLeft_sendsOneLowEmail_thenRestockedEmail() throws Exception {
        String[] m = regular();
        buy(m[0], 20).andExpect(jsonPath("$.stock.daysLeft").value(10)).andExpect(jsonPath("$.stock.alertStatus").value("OK"));
        assertThat(subjectsFor(m[1], 0)).isEmpty();

        take(m[0], 10).andExpect(jsonPath("$.stock.daysLeft").value(5)).andExpect(jsonPath("$.stock.alertStatus").value("LOW"));
        assertThat(subjectsFor(m[1], 1)).containsExactly("Low stock: " + m[1] + " - about 5 days left");

        take(m[0], 2); // 4 days: still LOW, no second email
        assertThat(subjectsFor(m[1], 1)).hasSize(1);

        buy(m[0], 30).andExpect(jsonPath("$.stock.alertStatus").value("OK"));
        assertThat(subjectsFor(m[1], 2)).containsExactly("Low stock: " + m[1] + " - about 5 days left",
                "Restocked: " + m[1]);
    }

    @Test
    void emailContent_hasEstimateAndLinkBackToTheApp_ownerHasNoUnsubscribeLink() throws Exception {
        String[] m = regular();
        buy(m[0], 8); // 4 days -> LOW right away
        subjectsFor(m[1], 1);
        EmailSender.Email e = emails.sent.stream().filter(x -> x.subject().contains(m[1])
                && x.toEmail().equals(TestUsers.OWNER_EMAIL)).findFirst().orElseThrow();
        assertThat(e.bodyText()).contains("about 8 tablets left, enough for about 4 days").contains("Please buy more")
                .contains("https://meds.example.test");
        assertThat(e.unsubscribeUrl()).isNull();
    }

    @Test
    void dailyJob_estimatesDosesNobodyLogged_andAlertsOncePerChange() throws Exception {
        String[] m = regular();
        buy(m[0], 14); // 7 days
        Instant now = clock.instant();

        daily.run(now.plus(Duration.ofDays(1))); // estimate 12 -> 6 days: OK
        assertThat(subjectsFor(m[1], 0)).isEmpty();

        daily.run(now.plus(Duration.ofDays(2))); // 10 -> 5 days: LOW
        assertThat(subjectsFor(m[1], 1)).containsExactly("Low stock: " + m[1] + " - about 5 days left");

        daily.run(now.plus(Duration.ofDays(2))); // run twice (cron retry): nothing new
        daily.run(now.plus(Duration.ofDays(3)));  // 4 days: still LOW
        assertThat(subjectsFor(m[1], 1)).hasSize(1);

        daily.run(now.plus(Duration.ofDays(8)));  // estimated 0: OUT
        assertThat(subjectsFor(m[1], 2)).containsExactly("Low stock: " + m[1] + " - about 5 days left",
                "Out of stock: " + m[1]);

        // The estimate is never written to the ledger: recorded stock is still 14.
        assertThat(jdbc.sql("select count(*) from stock_movement where medicine_id = cast(:m as uuid)")
                .param("m", m[0]).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void stockHoveringAroundTheLine_doesNotRepeatTheSameAlertWithinSixHours() throws Exception {
        String[] m = regular();
        buy(m[0], 12);  // 6 days OK
        take(m[0], 2);  // 5 days LOW -> email
        act("/api/v1/medicines/" + m[0] + "/recounts", "{\"countedUsable\":12,\"expectedUsable\":10}")
                .andExpect(status().isCreated()); // OK -> "restocked" email
        take(m[0], 2);  // LOW again within minutes: no repeat
        assertThat(subjectsFor(m[1], 2)).containsExactly("Low stock: " + m[1] + " - about 5 days left",
                "Restocked: " + m[1]);
        assertThat(alertStatus(m[0])).isEqualTo("LOW");
    }

    @Test
    void medicineWithoutDailyDose_usesMinimumThreshold() throws Exception {
        String[] m = newMedicine(",\"minThreshold\":3");
        buy(m[0], 5).andExpect(jsonPath("$.stock.daysLeft").doesNotExist());
        take(m[0], 2);
        assertThat(subjectsFor(m[1], 1)).containsExactly("Low stock: " + m[1]);
        take(m[0], 3);
        assertThat(subjectsFor(m[1], 2)).containsExactly("Low stock: " + m[1], "Out of stock: " + m[1]);
    }

    @Test
    void noAlerts_beforeTheFirstStockEntry_orForArchivedMedicines() throws Exception {
        String[] fresh = regular();
        daily.run(clock.instant().plus(Duration.ofDays(1)));
        assertThat(subjectsFor(fresh[1], 0)).isEmpty();

        String[] archived = regular();
        buy(archived[0], 20);
        owner.post("/api/v1/medicines/" + archived[0] + "/archive", "{}").andExpect(status().isOk());
        daily.run(clock.instant().plus(Duration.ofDays(30)));
        assertThat(subjectsFor(archived[1], 0)).isEmpty();
    }

    @Test
    void failedSend_isKeptAndRetriedLater() throws Exception {
        String[] m = regular();
        emails.fail = true;
        buy(m[0], 4); // LOW
        Thread.sleep(500);
        assertThat(subjectsFor(m[1], 0)).isEmpty();
        String error = jdbc.sql("select last_error from notification_outbox where subject like :s and to_email = :e")
                .param("s", "%" + m[1] + "%").param("e", TestUsers.OWNER_EMAIL).query(String.class).single();
        assertThat(error).contains("provider down");

        emails.fail = false;
        jdbc.sql("update notification_outbox set next_attempt_at = now() - interval '1 hour' where sent_at is null").update();
        outbox.sendPending();
        assertThat(subjectsFor(m[1], 1)).hasSize(1);
    }

    @Test
    void recipients_getAlertsWithTheirOwnUnsubscribeLink_andCanUnsubscribeWithoutLogin() throws Exception {
        String email = "sister-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String id = JsonPath.read(owner.post("/api/v1/alert-recipients",
                "{\"name\":\"Sister\",\"email\":\"" + email.toUpperCase() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn().getResponse().getContentAsString(), "$.id");
        owner.post("/api/v1/alert-recipients", "{\"name\":\"Again\",\"email\":\"" + email + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPIENT_EXISTS"));
        owner.post("/api/v1/alert-recipients", "{\"name\":\"Me\",\"email\":\"" + TestUsers.OWNER_EMAIL + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPIENT_IS_OWNER"));
        owner.post("/api/v1/alert-recipients", "{\"name\":\"Bad\",\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());

        String[] m = regular();
        buy(m[0], 6); // LOW
        subjectsFor(m[1], 1);
        EmailSender.Email toSister = waitFor(email, m[1]);
        assertThat(toSister.unsubscribeUrl()).startsWith("https://meds.example.test/api/v1/unsubscribe?token=");
        assertThat(toSister.bodyText()).contains("https://meds.example.test/?unsubscribe=");
        String token = toSister.unsubscribeUrl().substring(toSister.unsubscribeUrl().indexOf("token=") + 6);

        // GET (what link scanners do) must not unsubscribe.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/unsubscribe").param("token", token));
        assertThat(active(id)).isTrue();

        // No session and no CSRF token needed: the random token is the proof. Repeating it is fine.
        mvc.perform(post("/api/v1/unsubscribe").param("token", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/unsubscribe").param("token", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/unsubscribe").param("token", "wrong")).andExpect(status().isNotFound());
        assertThat(active(id)).isFalse();

        take(m[0], 6); // OUT: the owner is told, the sister is not
        assertThat(subjectsFor(m[1], 2)).hasSize(2);
        assertThat(emails.sent.stream().filter(e -> e.toEmail().equals(email) && e.subject().contains(m[1])))
                .hasSize(1);

        owner.get("/api/v1/alert-recipients").andExpect(jsonPath("$[?(@.email=='" + email + "')].active").value(false));
        Browser stranger = TestUsers.stranger(mvc, identity, encoder);
        stranger.delete("/api/v1/alert-recipients/" + id).andExpect(status().isNotFound());
        stranger.get("/api/v1/alert-recipients").andExpect(jsonPath("$.length()").value(0));
        owner.delete("/api/v1/alert-recipients/" + id).andExpect(status().isNoContent());
    }

    @Test
    void dailyJobEndpoint_needsTheBearerToken_andRecordsTheRun() throws Exception {
        mvc.perform(post("/internal/jobs/daily")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/daily").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/daily").header("Authorization", "Bearer test-job-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicinesChecked").isNumber())
                .andExpect(jsonPath("$.errors").value(0));
        owner.get("/api/v1/alerts/status")
                .andExpect(jsonPath("$.lastDailyCheck").isNotEmpty())
                .andExpect(jsonPath("$.emailProvider").value("log"));
        mvc.perform(post("/internal/other")).andExpect(status().isForbidden());
    }

    @Test
    void alertEndpoints_needLogin() throws Exception {
        Browser anonymous = new Browser(mvc, "198.51.100.7");
        anonymous.get("/api/v1/alert-recipients").andExpect(status().isUnauthorized());
        anonymous.get("/api/v1/alerts/status").andExpect(status().isUnauthorized());
        anonymous.post("/api/v1/alerts/test-email", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void testEmail_goesToTheOwner_andIsRateLimited() throws Exception {
        int before = (int) emails.sent.stream().filter(e -> e.subject().startsWith("Test:")).count();
        owner.post("/api/v1/alerts/test-email", "{}").andExpect(status().isOk());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (emails.sent.stream().filter(e -> e.subject().startsWith("Test:")).count() == before
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(emails.sent.stream().filter(e -> e.subject().startsWith("Test:")
                && e.toEmail().equals(TestUsers.OWNER_EMAIL))).hasSize(1);
        owner.post("/api/v1/alerts/test-email", "{}").andExpect(status().isTooManyRequests());
    }

    private EmailSender.Email waitFor(String to, String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var e = emails.sent.stream().filter(x -> x.toEmail().equals(to) && x.subject().contains(name)).findFirst();
            if (e.isPresent()) {
                return e.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no email to " + to);
    }

    private boolean active(String id) {
        return jdbc.sql("select active from alert_recipient where id = cast(:id as uuid)").param("id", id)
                .query(Boolean.class).single();
    }
}
