package com.almahwar.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptTrackerTest {

    /** Clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptTracker tracker = new LoginAttemptTracker(3, Duration.ofSeconds(60), clock);

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterDuration() {
        assertFalse(tracker.recordFailure("ahmed"));
        assertFalse(tracker.recordFailure("ahmed"));
        assertEquals(1, tracker.remainingAttempts("ahmed"));
        assertTrue(tracker.recordFailure("ahmed"));
        assertTrue(tracker.secondsLocked("ahmed") > 0);

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, tracker.secondsLocked("ahmed"));
        assertEquals(3, tracker.remainingAttempts("ahmed"));
    }

    @Test
    void usernameIsCaseInsensitiveAndTrimmed() {
        tracker.recordFailure("Ahmed");
        tracker.recordFailure(" AHMED ");
        assertEquals(1, tracker.remainingAttempts("ahmed"));
    }

    @Test
    void successResetsCounter() {
        tracker.recordFailure("sara");
        tracker.recordFailure("sara");
        tracker.reset("sara");
        assertEquals(3, tracker.remainingAttempts("sara"));
    }

    @Test
    void usersAreTrackedSeparately() {
        tracker.recordFailure("a");
        tracker.recordFailure("a");
        tracker.recordFailure("a");
        assertTrue(tracker.secondsLocked("a") > 0);
        assertEquals(0, tracker.secondsLocked("b"));
    }
}
