package com.almahwar.api.auth;

import com.almahwar.api.config.ApiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory attempt counter for usernames that do <b>not</b> exist — the desktop's {@code LoginAttemptTracker}, same
 * limits. Real accounts are locked in the database; unknown usernames have no row, so they are throttled here,
 * otherwise an attacker could tell real usernames apart by which ones ever get locked.
 * <p>
 * Bounded: an attacker cycling through random usernames cannot grow the map without limit (expired entries are
 * dropped, and past {@link #MAX_ENTRIES} the map is cleared). Only unknown usernames are counted here, so a reset
 * never weakens the database lock that protects real accounts.
 */
@Component
public class LoginAttemptTracker {

    static final int MAX_ENTRIES = 50_000;

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final int maxAttempts;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptTracker(ApiProperties properties) {
        this(properties.login().maxAttempts(), Duration.ofSeconds(properties.login().lockSeconds()), Clock.systemUTC());
    }

    LoginAttemptTracker(int maxAttempts, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int lockSeconds() {
        return (int) lockDuration.toSeconds();
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** Seconds left on the lock, or 0 if the username may try to log in. */
    public long secondsLocked(String username) {
        Attempts a = attempts.get(key(username));
        if (a == null || a.lockedUntil == null) {
            return 0;
        }
        long seconds = Duration.between(clock.instant(), a.lockedUntil).toSeconds();
        if (seconds <= 0) {
            attempts.remove(key(username));
            return 0;
        }
        return seconds + 1;
    }

    /** Records a failure; {@code true} if it caused the username to be locked. */
    public boolean recordFailure(String username) {
        if (attempts.size() >= MAX_ENTRIES) {
            attempts.clear();
        }
        Attempts updated = attempts.merge(key(username), new Attempts(1, null),
                (old, one) -> new Attempts(old.failures + 1, null));
        if (updated.failures >= maxAttempts) {
            attempts.put(key(username), new Attempts(updated.failures, clock.instant().plus(lockDuration)));
            return true;
        }
        return false;
    }
}
