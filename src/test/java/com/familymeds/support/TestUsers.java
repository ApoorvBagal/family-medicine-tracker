package com.familymeds.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.familymeds.identity.IdentityRepository;

/** Logged-in browsers for tests: the household owner (via the real setup API) and a stranger household. */
public final class TestUsers {

    public static final String OWNER_EMAIL = "owner@example.com";
    public static final String PASSWORD = "correct horse battery";

    private TestUsers() {
    }

    /** Runs first-time setup through the API (this class's schema is fresh), returning the logged-in browser. */
    public static Browser owner(MockMvc mvc) throws Exception {
        Browser b = new Browser(mvc, "198.51.100.1");
        b.post("/api/v1/auth/setup", """
                {"setupToken":"%s","householdName":"Test family","displayName":"Owner",
                 "email":"%s","password":"%s"}""".formatted(TestDatabase.SETUP_TOKEN, OWNER_EMAIL, PASSWORD))
                .andExpect(status().isCreated());
        return b;
    }

    /**
     * A second, unrelated household with its own user, created directly in the database (setup only allows
     * one), then logged in through the real login API. Used to prove households can't see each other's data.
     */
    public static Browser stranger(MockMvc mvc, IdentityRepository identity, PasswordEncoder encoder)
            throws Exception {
        String email = "stranger-" + UUID.randomUUID() + "@example.com";
        UUID household = identity.insertHousehold("Other family", "Asia/Kolkata");
        identity.insertUser(household, email, "Stranger", encoder.encode(PASSWORD));
        Browser b = new Browser(mvc, "198.51.100.99");
        b.post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk());
        return b;
    }
}
