package com.ahuamiao.adprotection;

/** A disappearance during a transition is insufficient evidence that the home screen returned. */
final class ClosingEvidence {
    static final long OBSERVATION_MS = 950;
    static final long FIXTURE_OBSERVATION_MS = 2_000;
    static final long INTERVAL_MS = 120;
    record Screen(boolean adVisible, boolean homeTab, boolean filesTab) {
        boolean atHome() { return !adVisible && homeTab && filesTab; }
    }

    private final long deadline;
    private long firstHome = -1;

    ClosingEvidence(long acceptedAt) { this(acceptedAt, OBSERVATION_MS); }

    ClosingEvidence(long acceptedAt, long observationMs) { deadline = acceptedAt + observationMs; }

    boolean expired(long now) { return now > deadline; }

    boolean observe(Screen screen, long now) {
        if (expired(now) || screen == null || !screen.atHome()) {
            firstHome = -1;
            return false;
        }
        if (firstHome < 0) firstHome = now;
        return now - firstHome >= INTERVAL_MS;
    }
}
