package com.ahuamiao.adprotection;

/** A disappearance during a transition is insufficient evidence that the home screen returned. */
final class ClosingEvidence {
    static final long OBSERVATION_MS = 950;
    static final long INTERVAL_MS = 120;
    record Screen(boolean adVisible, boolean homeTab, boolean filesTab) {
        boolean atHome() { return !adVisible && homeTab && filesTab; }
    }

    private final long deadline;
    private long firstHome = -1;

    ClosingEvidence(long acceptedAt) { deadline = acceptedAt + OBSERVATION_MS; }

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
