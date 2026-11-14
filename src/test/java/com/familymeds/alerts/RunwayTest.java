package com.familymeds.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RunwayTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    static Runway daily(String usable, String dose, LocalDate lastEntry) {
        return Runway.of(new BigDecimal(usable), new BigDecimal(dose), 5, BigDecimal.ZERO, lastEntry, TODAY);
    }

    @Test
    void sameDayEntry_estimateEqualsRecorded() {
        Runway r = daily("20", "2", TODAY);
        assertThat(r.estimated()).isEqualByComparingTo("20");
        assertThat(r.daysLeft()).isEqualTo(10);
        assertThat(r.runOutDate()).isEqualTo(TODAY.plusDays(10));
        assertThat(r.status()).isEqualTo(Runway.Status.OK);
    }

    @Test
    void estimateShrinksByTheDailyDoseForEachDaySinceTheLastEntry() {
        Runway r = daily("20", "2", TODAY.minusDays(4)); // 20 - 8 = 12 -> 6 days
        assertThat(r.estimated()).isEqualByComparingTo("12");
        assertThat(r.daysLeft()).isEqualTo(6);
        assertThat(r.status()).isEqualTo(Runway.Status.OK);
    }

    @Test
    void fiveDaysLeft_isLow_andTheBoundaryIsInclusive() {
        assertThat(daily("12", "2", TODAY).status()).isEqualTo(Runway.Status.OK);   // 6 days
        assertThat(daily("11", "2", TODAY).status()).isEqualTo(Runway.Status.LOW);  // 5.5 -> 5 days
        assertThat(daily("10", "2", TODAY).status()).isEqualTo(Runway.Status.LOW);  // 5 days
        assertThat(daily("1", "2", TODAY).daysLeft()).isZero();                    // half a day: still LOW, not OUT
        assertThat(daily("1", "2", TODAY).status()).isEqualTo(Runway.Status.LOW);
    }

    @Test
    void neverBelowZero_andOutWhenNothingLeft() {
        Runway r = daily("6", "2", TODAY.minusDays(30));
        assertThat(r.estimated()).isEqualByComparingTo("0");
        assertThat(r.daysLeft()).isZero();
        assertThat(r.status()).isEqualTo(Runway.Status.OUT);
        assertThat(daily("0", "2", TODAY).status()).isEqualTo(Runway.Status.OUT);
    }

    @Test
    void halfTabletDoses() {
        Runway r = daily("3", "0.5", TODAY); // 6 days
        assertThat(r.daysLeft()).isEqualTo(6);
        assertThat(r.status()).isEqualTo(Runway.Status.OK);
    }

    @Test
    void withoutDailyDose_usesTheMinimumThreshold() {
        Runway r = Runway.of(new BigDecimal("3"), null, 5, new BigDecimal("3"), TODAY.minusDays(100), TODAY);
        assertThat(r.estimated()).isEqualByComparingTo("3"); // no daily dose: no estimate, time doesn't matter
        assertThat(r.daysLeft()).isNull();
        assertThat(r.status()).isEqualTo(Runway.Status.LOW);
        assertThat(Runway.of(new BigDecimal("4"), null, 5, new BigDecimal("3"), null, TODAY).status())
                .isEqualTo(Runway.Status.OK);
        assertThat(Runway.of(new BigDecimal("4"), null, 5, BigDecimal.ZERO, null, TODAY).status())
                .isEqualTo(Runway.Status.OK); // threshold 0 = no low warning
        assertThat(Runway.of(BigDecimal.ZERO, null, 5, BigDecimal.ZERO, null, TODAY).status())
                .isEqualTo(Runway.Status.OUT);
    }
}
