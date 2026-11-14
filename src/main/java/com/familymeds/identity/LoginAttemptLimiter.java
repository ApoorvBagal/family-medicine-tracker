package com.familymeds.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Slows down password guessing. In memory, which is fine for a single instance (a restart resets it).
 *
 * <p>Two independent counters per attempt:
 * <ul>
 *   <li><b>per client IP</b> (5 failures / 15 min): stops one source hammering.</li>
 *   <li><b>per email</b> (10 failures / 15 min): stops guessing one account from many IPs. The IP comes
 *       from X-Forwarded-For, which a client can fake, so the IP key alone isn't enough.</li>
 * </ul>
 * After the limit, attempts are refused for 15 minutes (HTTP 429) without even checking the password.
 * A successful login clears both counters.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES_PER_IP = 5;
    static final int MAX_FAILURES_PER_EMAIL = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Attempts(int failures, Instant windowStart, Instant blockedUntil) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** How long the caller must wait, if blocked by either counter. */
    public Optional<Duration> blockedFor(String ip, String email) {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        for (String key : keys(ip, email)) {
            Attempts a = attempts.get(key);
            if (a != null && a.blockedUntil() != null && a.blockedUntil().isAfter(now)) {
                Duration d = Duration.between(now, a.blockedUntil());
                if (d.compareTo(wait) > 0) {
                    wait = d;
                }
            }
        }
        return wait.isZero() ? Optional.empty() : Optional.of(wait);
    }

    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        evictIfLarge(now);
        record("ip:" + ip, MAX_FAILURES_PER_IP, now);
        record("email:" + email, MAX_FAILURES_PER_EMAIL, now);
    }

    public void recordSuccess(String ip, String email) {
        for (String key : keys(ip, email)) {
            attempts.remove(key);
        }
    }

    private void record(String key, int limit, Instant now) {
        attempts.compute(key, (k, a) -> {
            if (a == null || a.windowStart().plus(WINDOW).isBefore(now)) {
                a = new Attempts(0, now, null);
            }
            int failures = a.failures() + 1;
            Instant blockedUntil = failures >= limit ? now.plus(WINDOW) : a.blockedUntil();
            return new Attempts(failures, a.windowStart(), blockedUntil);
        });
    }

    private void evictIfLarge(Instant now) {
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.entrySet().removeIf(e -> e.getValue().windowStart().plus(WINDOW).isBefore(now)
                    && (e.getValue().blockedUntil() == null || e.getValue().blockedUntil().isBefore(now)));
        }
    }

    private static String[] keys(String ip, String email) {
        return new String[] {"ip:" + ip, "email:" + email};
    }
}
