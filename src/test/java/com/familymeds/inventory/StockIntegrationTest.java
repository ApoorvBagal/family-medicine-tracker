package com.familymeds.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** Stock API rules against a real Postgres (own schema). Every test works on its own new medicine. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("com.familymeds.support.TestDatabase#available")
class StockIntegrationTest {

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

    static Browser owner;

    @BeforeEach
    void loginOnce() throws Exception {
        if (owner == null) {
            owner = TestUsers.owner(mvc);
        }
    }

    // ------------------------------------------------------------ helpers

    private String newMedicine() throws Exception {
        String body = """
                {"name":"Med %s","strength":"10 mg","dosageForm":"TABLET","unit":"TABLET","dailyDose":2}"""
                .formatted(UUID.randomUUID());
        return JsonPath.read(owner.post("/api/v1/medicines", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions act(Browser who, String path, String json, String key) throws Exception {
        return who.post(path, json, Map.of("Idempotency-Key", key));
    }

    private ResultActions act(String path, String json) throws Exception {
        return act(owner, path, json, UUID.randomUUID().toString());
    }

    private ResultActions buy(String med, String qty, LocalDate expiry) throws Exception {
        String exp = expiry == null ? "null" : "\"" + expiry + "\"";
        return act("/api/v1/medicines/" + med + "/purchases", "{\"quantity\":" + qty + ",\"expiryDate\":" + exp + "}")
                .andExpect(status().isCreated());
    }

    private ResultActions take(String med, String qty) throws Exception {
        return act("/api/v1/medicines/" + med + "/consumptions", "{\"quantity\":" + qty + "}");
    }

    private static String json(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(v);
    }

    private BigDecimal usable(String med) throws Exception {
        return new BigDecimal(json(owner.get("/api/v1/medicines/" + med + "/stock"), "$.usableQuantity"));
    }

    /** The ledger and the batches must always agree. */
    private void assertLedgerMatchesBatches(String med) {
        BigDecimal ledger = jdbc.sql("""
                select coalesce(sum(l.delta), 0) from stock_movement_line l
                join stock_movement m on m.id = l.movement_id where m.medicine_id = cast(:m as uuid)""")
                .param("m", med).query(BigDecimal.class).single();
        BigDecimal batches = jdbc.sql("select coalesce(sum(quantity), 0) from stock_lot where medicine_id = cast(:m as uuid)")
                .param("m", med).query(BigDecimal.class).single();
        assertThat(ledger).isEqualByComparingTo(batches);
    }

    // ------------------------------------------------------------ tests

    @Test
    void restock_thenDoses_takeFromTheBatchExpiringFirst_andSplitAcrossBatches() throws Exception {
        String med = newMedicine();
        LocalDate today = LocalDate.now();
        buy(med, "10", today.plusDays(60)).andExpect(jsonPath("$.stock.usableQuantity").value(10));
        buy(med, "5", today.plusDays(30)).andExpect(jsonPath("$.stock.usableQuantity").value(15));

        take(med, "7")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("CONSUMPTION"))
                .andExpect(jsonPath("$.movement.change").value(-7))
                .andExpect(jsonPath("$.movement.usableAfter").value(8))
                .andExpect(jsonPath("$.stock.usableQuantity").value(8))
                // the 30-day batch (5) is used up first, the other 2 come from the 60-day batch
                .andExpect(jsonPath("$.stock.lots.length()").value(1))
                .andExpect(jsonPath("$.stock.lots[0].expiryDate").value(today.plusDays(60).toString()))
                .andExpect(jsonPath("$.stock.lots[0].quantity").value(8))
                .andExpect(jsonPath("$.stock.nextExpiry").value(today.plusDays(60).toString()));

        assertThat(jdbc.sql("select count(*) from stock_movement_line l join stock_movement m on m.id = l.movement_id "
                + "where m.type = 'CONSUMPTION' and m.medicine_id = cast(:m as uuid)").param("m", med)
                .query(Long.class).single()).isEqualTo(2); // one dose, two batches
        assertLedgerMatchesBatches(med);
    }

    @Test
    void halfTablets_andBatchesWithoutExpiry_work() throws Exception {
        String med = newMedicine();
        buy(med, "10", null);
        take(med, "0.5").andExpect(status().isCreated()).andExpect(jsonPath("$.stock.usableQuantity").value(9.5));
    }

    @Test
    void expiredStock_isNeverUsable_andCanBeDisposed() throws Exception {
        String med = newMedicine();
        buy(med, "4", LocalDate.now().plusDays(10));
        buy(med, "20", LocalDate.now().plusDays(400));
        // The 20 expire "yesterday" (adding stock that's already expired is refused, so age it directly).
        jdbc.sql("update stock_lot set expiry_date = current_date - 1 where medicine_id = cast(:m as uuid) and quantity = 20")
                .param("m", med).update();

        owner.get("/api/v1/medicines/" + med + "/stock")
                .andExpect(jsonPath("$.usableQuantity").value(4))
                .andExpect(jsonPath("$.expiredQuantity").value(20));

        take(med, "5") // only 4 usable, even though 24 are physically there
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.usable").value(4))
                .andExpect(jsonPath("$.expired").value(20));

        String expiredLot = json(owner.get("/api/v1/medicines/" + med + "/stock"), "$.lots[?(@.expired == true)].id")
                .replaceAll("[\\[\\]\"]", "");
        act("/api/v1/medicines/" + med + "/disposals", "{\"lotId\":\"" + expiredLot + "\",\"note\":\"expired\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("DISPOSAL"))
                .andExpect(jsonPath("$.movement.change").value(-20))
                .andExpect(jsonPath("$.stock.expiredQuantity").value(0))
                .andExpect(jsonPath("$.stock.usableQuantity").value(4));
        assertLedgerMatchesBatches(med);
    }

    @Test
    void addingAlreadyExpiredStock_isRefused() throws Exception {
        String med = newMedicine();
        act("/api/v1/medicines/" + med + "/purchases",
                "{\"quantity\":10,\"expiryDate\":\"" + LocalDate.now().minusDays(1) + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ALREADY_EXPIRED"));
    }

    @Test
    void sameRequestSentTwice_isAppliedOnce() throws Exception {
        String med = newMedicine();
        buy(med, "30", null);
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/medicines/" + med + "/consumptions";

        String first = json(act(owner, path, "{\"quantity\":2}", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false")), "$.movement.id");
        // A retry after a network glitch: same key, same body.
        String second = json(act(owner, path, "{\"quantity\":2}", key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.stock.usableQuantity").value(28)), "$.movement.id");

        assertThat(second).isEqualTo(first);
        assertThat(usable(med)).isEqualByComparingTo("28");

        // Reusing a key for a different action is an error, not a silent replay.
        act(owner, "/api/v1/medicines/" + med + "/purchases", "{\"quantity\":5}", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKey_isRejected() throws Exception {
        String med = newMedicine();
        owner.post("/api/v1/medicines/" + med + "/consumptions", "{\"quantity\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void recount_setsTheNumber_butRefusesIfStockChangedWhileCounting() throws Exception {
        String med = newMedicine();
        buy(med, "30", LocalDate.now().plusDays(90));
        String recount = "/api/v1/medicines/" + med + "/recounts";

        // Phone A starts counting while the app shows 30... meanwhile phone B logs a dose.
        take(med, "2").andExpect(status().isCreated());
        act(recount, "{\"countedUsable\":25,\"expectedUsable\":30}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_CONFLICT"))
                .andExpect(jsonPath("$.currentUsable").value(28));
        assertThat(usable(med)).isEqualByComparingTo("28"); // B's dose was not overwritten

        act(recount, "{\"countedUsable\":25,\"expectedUsable\":28}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("CORRECTION"))
                .andExpect(jsonPath("$.movement.change").value(-3))
                .andExpect(jsonPath("$.stock.usableQuantity").value(25));

        act(recount, "{\"countedUsable\":31,\"expectedUsable\":25}") // found more
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.change").value(6))
                .andExpect(jsonPath("$.stock.usableQuantity").value(31));

        act(recount, "{\"countedUsable\":31,\"expectedUsable\":31}") // confirmed, unchanged: still recorded
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.change").value(0));
        assertLedgerMatchesBatches(med);
    }

    @Test
    void recount_ofAMedicineWithNoStock_createsABatch() throws Exception {
        String med = newMedicine();
        act("/api/v1/medicines/" + med + "/recounts", "{\"countedUsable\":12,\"expectedUsable\":0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock.usableQuantity").value(12));
    }

    @Test
    void undo_restoresStock_onlyOnce_andIsKeptInHistory() throws Exception {
        String med = newMedicine();
        buy(med, "10", null);
        String dose = json(take(med, "3").andExpect(status().isCreated()), "$.movement.id");
        String undo = "/api/v1/stock-movements/" + dose + "/reversal";

        act(undo, "{\"note\":\"tapped twice\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movement.type").value("REVERSAL"))
                .andExpect(jsonPath("$.movement.reversesMovementId").value(dose))
                .andExpect(jsonPath("$.movement.change").value(3))
                .andExpect(jsonPath("$.stock.usableQuantity").value(10));

        act(undo, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_UNDONE"));

        owner.get("/api/v1/medicines/" + med + "/movements")
                .andExpect(jsonPath("$.length()").value(3)) // purchase, dose, undo: nothing deleted
                .andExpect(jsonPath("$[0].type").value("REVERSAL"))
                .andExpect(jsonPath("$[1].undone").value(true));

        String reversalId = json(owner.get("/api/v1/medicines/" + med + "/movements"), "$[0].id");
        act("/api/v1/stock-movements/" + reversalId + "/reversal", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_UNDO_UNDO"));
        assertLedgerMatchesBatches(med);
    }

    @Test
    void undoingAPurchase_whoseStockWasAlreadyUsed_isRefused() throws Exception {
        String med = newMedicine();
        String purchase = json(buy(med, "5", null), "$.movement.id");
        take(med, "4").andExpect(status().isCreated());
        act("/api/v1/stock-movements/" + purchase + "/reversal", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_UNDO"));
        assertThat(usable(med)).isEqualByComparingTo("1");
    }

    @Test
    void archivedMedicine_acceptsNoStockChanges() throws Exception {
        String med = newMedicine();
        owner.post("/api/v1/medicines/" + med + "/archive", "{}").andExpect(status().isOk());
        take(med, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEDICINE_ARCHIVED"));
    }

    @Test
    void anotherHousehold_cannotSeeOrChangeThisStock() throws Exception {
        String med = newMedicine();
        String purchase = json(buy(med, "10", null), "$.movement.id");
        Browser stranger = TestUsers.stranger(mvc, identity, encoder);
        String p = "/api/v1/medicines/" + med;

        stranger.get(p + "/stock").andExpect(status().isNotFound());
        stranger.get(p + "/movements").andExpect(status().isNotFound());
        act(stranger, p + "/consumptions", "{\"quantity\":1}", UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
        act(stranger, p + "/recounts", "{\"countedUsable\":0,\"expectedUsable\":10}", UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
        act(stranger, "/api/v1/stock-movements/" + purchase + "/reversal", "{}", UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
        assertThat(usable(med)).isEqualByComparingTo("10");
    }

    @Test
    void medicineList_showsUsableAndExpiredTotals() throws Exception {
        String med = newMedicine();
        buy(med, "6", null);
        owner.get("/api/v1/medicines")
                .andExpect(jsonPath("$[?(@.id == '" + med + "')].usableQuantity").value(org.hamcrest.Matchers.hasItem(6.0)));
    }

    @Test
    void invalidAmounts_areRejected() throws Exception {
        String med = newMedicine();
        take(med, "0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        take(med, "-1").andExpect(status().isBadRequest());
        take(med, "1.234").andExpect(status().isBadRequest()); // max 2 decimals
    }
}
