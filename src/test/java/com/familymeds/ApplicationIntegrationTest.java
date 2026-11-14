package com.familymeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.familymeds.ops.BootLogRepository;
import com.familymeds.support.TestDatabase;

/**
 * Full application against a real Postgres: the Neon test branch locally, or a Docker container in CI.
 * See {@link TestDatabase}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("com.familymeds.support.TestDatabase#available")
class ApplicationIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    BootLogRepository bootLog;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** Guards against tests silently using another database (e.g. dev via .env.local). */
    @Test
    void appIsConnectedToThisRunsTestSchema() {
        assertThat(jdbc.sql("select current_schema()").query(String.class).single()).isEqualTo(TestDatabase.schema());
        assertThat(jdbc.sql("select count(*) from installation").query(Long.class).single()).isNotNull();
    }

    @Test
    void flywayMigratesAndStartupIsRecorded() {
        assertThat(bootLog.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void healthIsUpAndPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoReportsBootHistory() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boots.total").isNumber())
                .andExpect(jsonPath("$.app.commit").value("local"));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
    }
}
