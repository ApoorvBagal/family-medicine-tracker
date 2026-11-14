package com.familymeds.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    /** A clock the test moves forward by hand. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void blocksAnIpAfterFiveFailures_forFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.2.3.4", "a@x.com");
        }
        assertThat(limiter.blockedFor("1.2.3.4", "a@x.com")).isEmpty();

        limiter.recordFailure("1.2.3.4", "a@x.com");
        assertThat(limiter.blockedFor("1.2.3.4", "b@x.com")).hasValue(Duration.ofMinutes(15));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(limiter.blockedFor("1.2.3.4", "a@x.com")).isEmpty();
    }

    @Test
    void blocksAnEmailAfterTenFailures_fromAnyIps() {
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("10.0.0." + i, "owner@x.com");
        }
        assertThat(limiter.blockedFor("10.9.9.9", "owner@x.com")).isPresent();
        assertThat(limiter.blockedFor("10.9.9.9", "other@x.com")).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowDontAccumulate() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.2.3.4", "a@x.com");
        }
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure("1.2.3.4", "a@x.com");
        assertThat(limiter.blockedFor("1.2.3.4", "a@x.com")).isEmpty();
    }

    @Test
    void successClearsTheCounters() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.2.3.4", "a@x.com");
        }
        limiter.recordSuccess("1.2.3.4", "a@x.com");
        limiter.recordFailure("1.2.3.4", "a@x.com");
        assertThat(limiter.blockedFor("1.2.3.4", "a@x.com")).isEmpty();
    }

    @Test
    void setupTokenComparisonIsExact() {
        assertThat(SetupService.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(SetupService.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(SetupService.constantTimeEquals("abc", null)).isFalse();
    }
}
