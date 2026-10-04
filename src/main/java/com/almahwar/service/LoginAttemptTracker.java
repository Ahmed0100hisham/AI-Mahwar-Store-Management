package com.almahwar.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory attempt counter for usernames that do <b>not</b> exist.
 * <p>
 * Real accounts are locked in the database ({@code Users.failed_login_attempts},
 * {@code locked_until}). Unknown usernames have no row, so they are throttled here
 * with the same limits; otherwise an attacker could tell real usernames apart by
 * which ones ever get locked. Also holds the configured limits used for both.
 */
public class LoginAttemptTracker {

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final int maxAttempts;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptTracker(int maxAttempts, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getLockDuration() {
        return lockDuration;
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
            attempts.remove(key(username));   // lock expired: start counting again
            return 0;
        }
        return seconds + 1;   // round up for display
    }

    /**
     * Records a wrong password.
     *
     * @return {@code true} if this failure caused the username to be locked
     */
    public boolean recordFailure(String username) {
        Attempts updated = attempts.merge(key(username), new Attempts(1, null),
                (old, one) -> new Attempts(old.failures + 1, null));
        if (updated.failures >= maxAttempts) {
            attempts.put(key(username), new Attempts(updated.failures, clock.instant().plus(lockDuration)));
            return true;
        }
        return false;
    }

    public void reset(String username) {
        attempts.remove(key(username));
    }

    public int remainingAttempts(String username) {
        Attempts a = attempts.get(key(username));
        return a == null ? maxAttempts : Math.max(0, maxAttempts - a.failures);
    }
}
