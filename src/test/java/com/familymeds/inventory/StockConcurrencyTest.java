package com.familymeds.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.familymeds.identity.AppUserDetailsService;
import com.familymeds.identity.AppUserPrincipal;
import com.familymeds.shared.web.ApiException;
import com.familymeds.support.TestDatabase;
import com.familymeds.support.TestUsers;

/**
 * Several "phones" changing the same medicine at exactly the same moment, on real threads and real
 * database connections. Without the row lock these would lose updates (read 50, write 49 twice).
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
@AutoConfigureMockMvc
@EnabledIf("com.familymeds.support.TestDatabase#available")
class StockConcurrencyTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    static final int THREADS = 8;
    static final int DOSES_PER_THREAD = 5;

    @Autowired
    MockMvc mvc;

    @Autowired
    MedicineService medicines;

    @Autowired
    StockService stock;

    @Autowired
    AppUserDetailsService users;

    @Autowired
    JdbcClient jdbc;

    private AppUserPrincipal owner() throws Exception {
        if (jdbc.sql("select count(*) from app_user").query(Long.class).single() == 0) {
            TestUsers.owner(mvc);
        }
        return (AppUserPrincipal) users.loadUserByUsername(TestUsers.OWNER_EMAIL);
    }

    private UUID medicineWith(AppUserPrincipal user, int quantity) {
        UUID id = medicines.create(user, new Medicine.Fields("Concurrent " + UUID.randomUUID(), null, "1 mg",
                Medicine.DosageForm.TABLET, Medicine.Unit.TABLET, BigDecimal.ZERO, BigDecimal.ONE, 5, null, null)).id();
        stock.purchase(user, id, UUID.randomUUID().toString(), BigDecimal.valueOf(quantity), null, null, null, null);
        return id;
    }

    /** Runs all tasks at the same instant (a start gate) and waits for them. */
    private static <T> List<Future<T>> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                return task.call();
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        return futures;
    }

    @Test
    void simultaneousDoses_areAllCounted_noLostUpdates() throws Exception {
        AppUserPrincipal user = owner();
        UUID med = medicineWith(user, 100);

        List<Callable<Integer>> phones = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            phones.add(() -> {
                for (int i = 0; i < DOSES_PER_THREAD; i++) {
                    stock.consume(user, med, UUID.randomUUID().toString(), BigDecimal.ONE, null, null);
                }
                return DOSES_PER_THREAD;
            });
        }
        for (Future<Integer> f : together(phones)) {
            f.get(); // rethrows any failure
        }

        int doses = THREADS * DOSES_PER_THREAD;
        assertThat(stock.level(user, med).usable()).isEqualByComparingTo(BigDecimal.valueOf(100 - doses));
        assertThat(jdbc.sql("select count(*) from stock_movement where medicine_id = :m and type = 'CONSUMPTION'")
                .param("m", med).query(Long.class).single()).isEqualTo(doses);
    }

    @Test
    void simultaneousDoses_neverTakeStockBelowZero() throws Exception {
        AppUserPrincipal user = owner();
        UUID med = medicineWith(user, 5); // 8 phones each try to take 1 of only 5

        List<Callable<Boolean>> phones = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            phones.add(() -> {
                try {
                    stock.consume(user, med, UUID.randomUUID().toString(), BigDecimal.ONE, null, null);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK");
                    return false;
                }
            });
        }
        long succeeded = 0;
        for (Future<Boolean> f : together(phones)) {
            if (f.get()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(5);
        assertThat(stock.level(user, med).usable()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theSameRequestArrivingTwiceAtOnce_isAppliedOnce() throws Exception {
        AppUserPrincipal user = owner();
        UUID med = medicineWith(user, 50);
        String key = UUID.randomUUID().toString();

        List<Callable<UUID>> retries = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            retries.add(() -> stock.consume(user, med, key, BigDecimal.valueOf(2), null, null).movement().id());
        }
        List<UUID> ids = new ArrayList<>();
        for (Future<UUID> f : together(retries)) {
            ids.add(f.get());
        }
        assertThat(ids).allMatch(id -> id.equals(ids.get(0))); // everyone got the same movement back
        assertThat(stock.level(user, med).usable()).isEqualByComparingTo("48");
    }
}
