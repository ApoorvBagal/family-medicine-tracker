package com.familymeds.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** Medicines API against a real Postgres (own schema for this class). Each test uses its own names. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("com.familymeds.support.TestDatabase#available")
class MedicineIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    IdentityRepository identity;

    @Autowired
    PasswordEncoder encoder;

    static Browser owner;

    @BeforeEach
    void loginOnce() throws Exception {
        if (owner == null) {
            owner = TestUsers.owner(mvc);
        }
    }

    private static String medicine(String name, String strength, String form) {
        return """
                {"name":"%s","brandName":"Brandy","strength":%s,"dosageForm":"%s","unit":"TABLET",
                 "minThreshold":10,"dailyDose":2,"storageLocation":"Kitchen shelf","notes":"after food"}"""
                .formatted(name, strength == null ? "null" : "\"" + strength + "\"", form);
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String create(String name, String strength, String form) throws Exception {
        return id(owner.post("/api/v1/medicines", medicine(name, strength, form)).andExpect(status().isCreated()));
    }

    @Test
    void create_returnsTheMedicine_withLocationAndEtag_andGetReturnsTheSame() throws Exception {
        ResultActions created = owner.post("/api/v1/medicines", medicine("  Metformin   XR ", "500 mg", "TABLET"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"v0\""))
                .andExpect(jsonPath("$.name").value("Metformin XR")) // trimmed, inner spaces collapsed
                .andExpect(jsonPath("$.strength").value("500 mg"))
                .andExpect(jsonPath("$.dailyDose").value(2))
                .andExpect(jsonPath("$.warnDays").value(5)) // D11 default
                .andExpect(jsonPath("$.archived").value(false));
        String id = id(created);
        assertThat(created.andReturn().getResponse().getHeader("Location")).isEqualTo("/api/v1/medicines/" + id);

        owner.get("/api/v1/medicines/" + id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v0\""))
                .andExpect(jsonPath("$.storageLocation").value("Kitchen shelf"));
    }

    @Test
    void duplicates_areRejected_ignoringCaseAndSpaces_butOtherFormsAreFine() throws Exception {
        String first = create("Paracetamol", "500 mg", "TABLET");

        owner.post("/api/v1/medicines", medicine("paracetamol", "500MG", "TABLET"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEDICINE"))
                .andExpect(jsonPath("$.existingId").value(first));

        create("Paracetamol", "500 mg", "SYRUP"); // different form: a different product
        create("Paracetamol", "650 mg", "TABLET"); // different strength
    }

    @Test
    void invalidInput_returnsFieldErrors() throws Exception {
        owner.post("/api/v1/medicines", "{\"name\":\"  \",\"unit\":\"TABLET\",\"minThreshold\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("dosageForm")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("minThreshold")));

        owner.post("/api/v1/medicines", medicine("Bad form", null, "POWDER")) // not an allowed form
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        owner.get("/api/v1/medicines/not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void update_needsTheCurrentVersion_soConcurrentEditsArentLost() throws Exception {
        String id = create("Atorvastatin", "10 mg", "TABLET");
        String path = "/api/v1/medicines/" + id;

        // "Phone A" saves first...
        owner.put(path, medicine("Atorvastatin", "20 mg", "TABLET"), "\"v0\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v1\""))
                .andExpect(jsonPath("$.strength").value("20 mg"));

        // ...then "phone B", still holding v0, is refused instead of overwriting A's change.
        owner.put(path, medicine("Atorvastatin", "40 mg", "TABLET"), "\"v0\"")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.currentVersion").value(1));

        owner.put(path, medicine("Atorvastatin", "40 mg", "TABLET"), null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));

        owner.get(path).andExpect(jsonPath("$.strength").value("20 mg"));
    }

    @Test
    void unchangedUpdate_keepsTheVersion_andWritesNoHistory() throws Exception {
        String id = create("Amlodipine", "5 mg", "TABLET");
        owner.put("/api/v1/medicines/" + id, medicine("Amlodipine", "5 mg", "TABLET"), "\"v0\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v0\""));
        owner.get("/api/v1/medicines/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("CREATED"));
    }

    @Test
    void update_toAnExistingMedicine_isADuplicate() throws Exception {
        String a = create("Losartan", "25 mg", "TABLET");
        String b = create("Losartan", "50 mg", "TABLET");
        owner.put("/api/v1/medicines/" + b, medicine("losartan", "25mg", "TABLET"), "\"v0\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(a));
    }

    @Test
    void archive_hidesTheMedicine_freesTheName_andRestoreChecksDuplicates() throws Exception {
        String old = create("Glimepiride", "1 mg", "TABLET");
        owner.post("/api/v1/medicines/" + old + "/archive", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        owner.get("/api/v1/medicines").andExpect(jsonPath("$[*].id", not(hasItem(old))));
        owner.get("/api/v1/medicines?archived=true").andExpect(jsonPath("$[*].id", hasItem(old)));

        // Editing an archived medicine is refused.
        owner.put("/api/v1/medicines/" + old, medicine("Glimepiride", "2 mg", "TABLET"), "\"v1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDICINE_ARCHIVED"));

        // The same medicine can be added again once the old entry is archived...
        String replacement = create("Glimepiride", "1 mg", "TABLET");
        // ...and then the old one can't be restored alongside it.
        owner.post("/api/v1/medicines/" + old + "/restore", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEDICINE"))
                .andExpect(jsonPath("$.existingId").value(replacement));

        owner.post("/api/v1/medicines/" + replacement + "/archive", "{}").andExpect(status().isOk());
        owner.post("/api/v1/medicines/" + old + "/restore", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void search_matchesNameOrBrand_caseInsensitively_andTreatsWildcardsLiterally() throws Exception {
        String id = create("Pantoprazole", "40 mg", "TABLET");
        owner.get("/api/v1/medicines?q=PANTO").andExpect(jsonPath("$[*].id", hasItem(id)));
        owner.get("/api/v1/medicines?q=brandy").andExpect(jsonPath("$[*].id", hasItem(id)));
        owner.get("/api/v1/medicines?q=%25").andExpect(jsonPath("$.length()").value(0)); // "%" isn't "anything"
    }

    @Test
    void history_recordsWhoChangedWhat() throws Exception {
        String id = create("Telmisartan", "40 mg", "TABLET");
        owner.put("/api/v1/medicines/" + id, medicine("Telmisartan", "80 mg", "TABLET"), "\"v0\"")
                .andExpect(status().isOk());
        owner.get("/api/v1/medicines/" + id + "/history")
                .andExpect(jsonPath("$[0].action").value("UPDATED"))
                .andExpect(jsonPath("$[0].actorName").value("Owner"))
                .andExpect(jsonPath("$[0].changes.strength.from").value("40 mg"))
                .andExpect(jsonPath("$[0].changes.strength.to").value("80 mg"))
                .andExpect(jsonPath("$[0].changes.name").doesNotExist()) // only changed fields
                .andExpect(jsonPath("$[1].changes.name").value("Telmisartan"))
                .andExpect(jsonPath("$[1].action").value("CREATED"));
    }

    @Test
    void anotherHousehold_cannotSeeOrTouchTheseMedicines() throws Exception {
        String id = create("Insulin glargine", "100 IU/ml", "INJECTION");
        Browser stranger = TestUsers.stranger(mvc, identity, encoder);
        String path = "/api/v1/medicines/" + id;

        stranger.get("/api/v1/medicines").andExpect(jsonPath("$[*].id", not(hasItem(id))));
        stranger.get(path).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEDICINE_NOT_FOUND"));
        stranger.put(path, medicine("Hacked", null, "TABLET"), "\"v0\"").andExpect(status().isNotFound());
        stranger.post(path + "/archive", "{}").andExpect(status().isNotFound());
        stranger.get(path + "/history").andExpect(status().isNotFound());

        // The stranger can add the very same medicine to their own household (duplicates are per household).
        stranger.post("/api/v1/medicines", medicine("Insulin glargine", "100 IU/ml", "INJECTION"))
                .andExpect(status().isCreated());
        owner.get(path).andExpect(jsonPath("$.name").value("Insulin glargine"));
    }

    @Test
    void notLoggedIn_getsA401() throws Exception {
        new Browser(mvc, "198.51.100.50").get("/api/v1/medicines")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void openApiDescription_isAvailableToTheOwner_only() throws Exception {
        new Browser(mvc, "198.51.100.51").get("/v3/api-docs").andExpect(status().isUnauthorized());
        owner.get("/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/medicines']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/medicines/{id}'].put").exists());
    }

    @SuppressWarnings("unused")
    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }
}
