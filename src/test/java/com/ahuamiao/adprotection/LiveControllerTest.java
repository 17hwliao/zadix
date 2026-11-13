package com.ahuamiao.adprotection;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LiveControllerTest {
    private final LiveController gate = new LiveController(false);
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger clicks = new AtomicInteger();

    private long start() {
        gate.permission(true);
        long connection = gate.connect(true);
        assertEquals(LiveController.Phase.PAUSED, gate.status().phase());
        gate.choose(true);
        assertEquals(LiveController.Phase.STARTING, gate.status().phase());
        gate.acknowledge(gate.pending());
        assertEquals(LiveController.Phase.RUNNING, gate.status().phase());
        return connection;
    }

    private void tryWork(LiveController.Work work) {
        gate.query(work, queries::incrementAndGet);
        gate.submit(work, () -> clicks.incrementAndGet() > 0);
    }

    @Test public void pauseTimeoutClosesBothEntrypointsAndRejectsLateAcknowledgement() {
        long connection = start();
        var old = gate.work(connection);
        tryWork(old);
        gate.choose(false);
        var pause = gate.pending();
        assertEquals(LiveController.Phase.PAUSING, gate.status().phase());
        tryWork(old);
        gate.timeout(pause);
        gate.acknowledge(pause);
        tryWork(old);
        tryWork(gate.work(connection));
        assertEquals(1, queries.get());
        assertEquals(1, clicks.get());
        assertEquals(LiveController.Failure.TIMEOUT, gate.status().failure());
        gate.choose(true);
        gate.acknowledge(gate.pending());
        tryWork(gate.work(connection));
        assertEquals(2, clicks.get());
    }

    @Test public void staleStartCannotLightTileAfterPauseOrReconnect() {
        gate.permission(true);
        long original = gate.connect(true);
        gate.choose(true);
        var start = gate.pending();
        gate.choose(false);
        gate.acknowledge(start);
        assertNull(gate.work(original));
        gate.disconnect(original);
        long newConnection = gate.connect(true);
        gate.choose(true);
        gate.acknowledge(start);
        tryWork(gate.work(newConnection));
        assertEquals(0, clicks.get());
        gate.acknowledge(gate.pending());
        assertEquals(LiveController.Phase.RUNNING, gate.status().phase());
    }

    @Test public void switchingWindowInvalidatesAlreadyQueuedQueryAndClick() {
        long connection = start();
        var oldWindow = gate.work(connection);
        gate.windowChanged(connection);
        tryWork(oldWindow);
        assertEquals(0, queries.get());
        assertEquals(0, clicks.get());
        tryWork(gate.work(connection));
        assertEquals(1, clicks.get());
    }

    @Test public void submittedResultCanBeReadAfterFocusRefreshButNotAcrossRuns() {
        long connection = start();
        var submitted = gate.work(connection);
        assertTrue(gate.submit(submitted, () -> true));
        gate.windowChanged(connection);
        assertNull(gate.query(submitted, queries::incrementAndGet));
        assertFalse(gate.submit(submitted, () -> clicks.incrementAndGet() > 0));
        assertEquals(Integer.valueOf(1), gate.observeResult(submitted, queries::incrementAndGet));

        gate.choose(false);
        assertNull(gate.observeResult(submitted, queries::incrementAndGet));
        gate.choose(true);
        gate.acknowledge(gate.pending());
        assertNull(gate.observeResult(submitted, queries::incrementAndGet));
        assertEquals(1, queries.get());
        assertEquals(0, clicks.get());
    }

    @Test public void olderLaunchPlaceholderCannotDiscardFirstWindowAfterResume() {
        var app = "com.ahuamiao.adprotection.fixture";
        assertTrue(LiveController.staleStartingWindow(app, 14949, 894789786, app,
                "android.widget.FrameLayout", 14948, 894789679));
        assertFalse(LiveController.staleStartingWindow(app, 14949, 894789786, app,
                "android.widget.FrameLayout", 14950, 894789900));
    }

    @Test public void cleanupReleasesOnlyWhenSafeAndHonorsLatestPause() {
        long connection = start();
        var preCleanup = gate.work(connection);
        assertTrue(gate.beginCleanup());
        assertFalse(gate.beginCleanup());
        tryWork(preCleanup);
        gate.choose(false);
        gate.finishCleanup(true);
        assertEquals(LiveController.Phase.CLEANING, gate.status().phase());
        tryWork(gate.work(connection));
        gate.uncertainCleanup(false);
        assertEquals(LiveController.Phase.PAUSED, gate.status().phase());
        assertNull(gate.pending());
        assertEquals(0, queries.get());
        assertEquals(0, clicks.get());
    }

    @Test public void connectionAndPermissionLossDoNotReusePreviousRuntimeState() {
        long connection = start();
        var work = gate.work(connection);
        gate.disconnect(connection);
        assertEquals(LiveController.Failure.DISCONNECTED, gate.status().failure());
        tryWork(work);
        gate.permission(false);
        assertEquals(LiveController.Phase.PERMISSION, gate.status().phase());
        assertTrue(gate.status().desired());
        gate.permission(true);
        gate.connect(false);
        assertEquals(LiveController.Failure.RULES, gate.status().failure());
        tryWork(work);
        assertEquals(0, queries.get());
        assertEquals(0, clicks.get());
    }

    @Test public void enablingBeforeGrantStartsAutomaticallyWhenTheServiceIsBound() {
        gate.choose(true);
        assertEquals(LiveController.Phase.PERMISSION, gate.status().phase());
        assertNull(gate.pending());
        long connection = gate.connect(true);
        assertNull(gate.work(connection));
        gate.permission(true);
        assertEquals(LiveController.Phase.STARTING, gate.status().phase());
        gate.acknowledge(gate.pending());
        tryWork(gate.work(connection));
        assertEquals(1, clicks.get());
    }

    @Test public void reconnectRestoresRunningAndAnExplicitPauseSurvivesRegrant() {
        long original = start();
        var oldWork = gate.work(original);
        gate.disconnect(original);
        long rebound = gate.connect(true);
        gate.acknowledge(gate.pending());
        tryWork(oldWork);
        tryWork(gate.work(rebound));
        assertEquals(1, clicks.get());

        gate.choose(false);
        gate.acknowledge(gate.pending());
        gate.permission(false);
        gate.permission(true);
        gate.disconnect(rebound);
        long next = gate.connect(true);
        assertEquals(LiveController.Phase.PAUSED, gate.status().phase());
        assertNull(gate.pending());
        tryWork(gate.work(next));
        assertEquals(1, clicks.get());
    }
}
