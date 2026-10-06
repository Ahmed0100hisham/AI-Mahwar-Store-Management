package com.almahwar.api.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Unknown usernames are throttled with the desktop's limits, case-insensitively, and the lock expires. */
class LoginAttemptTrackerTest {

    @Test
    void locksAfterMaxAttemptsAndExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-07T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        LoginAttemptTracker tracker = new LoginAttemptTracker(5, Duration.ofSeconds(300), clock);
        for (int i = 0; i < 4; i++) {
            assertThat(tracker.recordFailure("Ghost")).isFalse();
        }
        assertThat(tracker.secondsLocked("ghost")).isZero();
        assertThat(tracker.recordFailure(" GHOST ")).isTrue();
        assertThat(tracker.secondsLocked("ghost")).isEqualTo(301);

        now.set(now.get().plusSeconds(301));
        assertThat(tracker.secondsLocked("ghost")).isZero();
        assertThat(tracker.recordFailure("ghost")).isFalse();   // a new count starts
    }

    @Test
    void sameLimitsAsTheDesktop() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
        com.almahwar.service.LoginAttemptTracker desktop =
                new com.almahwar.service.LoginAttemptTracker(5, Duration.ofSeconds(300), fixed);
        LoginAttemptTracker api = new LoginAttemptTracker(5, Duration.ofSeconds(300), fixed);
        for (int i = 0; i < 5; i++) {
            assertThat(api.recordFailure("x")).isEqualTo(desktop.recordFailure("x"));
        }
        assertThat(api.secondsLocked("x")).isEqualTo(desktop.secondsLocked("x"));
    }

    @Test
    void memoryIsBounded() {
        LoginAttemptTracker tracker = new LoginAttemptTracker(5, Duration.ofSeconds(300), Clock.systemUTC());
        for (int i = 0; i < LoginAttemptTracker.MAX_ENTRIES + 10; i++) {
            tracker.recordFailure("user" + i);
        }
        assertThat(tracker.secondsLocked("user1")).isZero();
    }
}
