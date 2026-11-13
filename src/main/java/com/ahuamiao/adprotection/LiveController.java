package com.ahuamiao.adprotection;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The only gate for starting accessibility queries and submitting actions. */
final class LiveController {
    enum Phase { PERMISSION, PAUSED, STARTING, PAUSING, RUNNING, CLEANING, RECOVERY }
    enum Failure { NONE, DISCONNECTED, RULES, TIMEOUT, STORAGE }
    record Request(long connection, long generation, boolean start) {}
    record Work(long connection, long generation, long window) {}
    record Status(Phase phase, Failure failure, boolean desired) {}

    private boolean desired;
    private boolean permission;
    private boolean rulesReady;
    private boolean running;
    private boolean cleanup;
    private boolean uncertainCleanup;
    private long sequence;
    private long connection;
    private long generation;
    private long window;
    private Request pending;
    private Failure transitionFailure = Failure.NONE;

    LiveController(boolean desired) {
        this.desired = desired;
    }

    synchronized void permission(boolean granted) {
        if (permission == granted) return;
        permission = granted;
        restart();
    }

    synchronized long connect(boolean validRules) {
        connection = ++sequence;
        rulesReady = validRules;
        restart();
        return connection;
    }

    synchronized void disconnect(long from) {
        if (connection != from || from == 0) return;
        connection = 0;
        invalidate();
    }

    synchronized void choose(boolean enabled) {
        if (desired == enabled && transitionFailure == Failure.NONE) return;
        desired = enabled;
        restart();
        if (!enabled && permission && connection != 0 && !cleanup && !uncertainCleanup) {
            pending = new Request(connection, generation, false);
        }
    }

    synchronized boolean beginCleanup() {
        if (cleanup || uncertainCleanup) return false;
        cleanup = true;
        invalidate();
        return true;
    }

    synchronized void finishCleanup(boolean mayStillRun) {
        cleanup = false;
        uncertainCleanup(mayStillRun);
        if (!mayStillRun) restart();
    }

    synchronized void uncertainCleanup(boolean mayStillRun) {
        if (uncertainCleanup == mayStillRun) return;
        uncertainCleanup = mayStillRun;
        if (mayStillRun) invalidate();
        else restart();
    }

    synchronized Request pending() { return pending; }

    synchronized void acknowledge(Request request) {
        if (request == null || !request.equals(pending)) return;
        running = request.start();
        pending = null;
    }

    synchronized void timeout(Request request) {
        if (request == null || !request.equals(pending)) return;
        invalidate();
        transitionFailure = Failure.TIMEOUT;
    }

    synchronized void storageFailed() {
        desired = false;
        invalidate();
        transitionFailure = Failure.STORAGE;
    }

    synchronized void windowChanged(long from) {
        if (from == connection && from != 0) window++;
    }

    static boolean staleStartingWindow(String currentPackage, int currentWindow, long currentTime,
                                       String eventPackage, String eventClass, int eventWindow, long eventTime) {
        return currentPackage.equals(eventPackage) && eventClass.startsWith("android.widget.")
                && eventWindow != currentWindow && currentTime >= 0 && eventTime < currentTime;
    }

    synchronized Work work(long from) {
        return running && from == connection ? new Work(from, generation, window) : null;
    }

    synchronized <T> T query(Work work, Supplier<T> query) {
        return allows(work) ? query.get() : null;
    }

    // Result observation checks the current foreground window itself. A focus event can
    // invalidate a pending click without ending observation of a click already submitted.
    synchronized <T> T observeResult(Work submitted, Supplier<T> readForeground) {
        return sameRun(submitted) ? readForeground.get() : null;
    }

    synchronized boolean submit(Work work, BooleanSupplier action) {
        return allows(work) && action.getAsBoolean();
    }

    synchronized Status status() {
        if (!permission) return new Status(Phase.PERMISSION, Failure.NONE, desired);
        if (cleanup || uncertainCleanup) return new Status(Phase.CLEANING, Failure.NONE, desired);
        if (transitionFailure != Failure.NONE) return new Status(Phase.RECOVERY, transitionFailure, desired);
        if (pending != null) return new Status(pending.start() ? Phase.STARTING : Phase.PAUSING, Failure.NONE, desired);
        if (running) return new Status(Phase.RUNNING, Failure.NONE, desired);
        if (!desired) return new Status(Phase.PAUSED, Failure.NONE, false);
        return new Status(Phase.RECOVERY, connection == 0 ? Failure.DISCONNECTED : Failure.RULES, true);
    }

    private boolean allows(Work work) {
        return sameRun(work) && work.window() == window;
    }

    private boolean sameRun(Work work) {
        return running && work != null && work.connection() == connection && work.generation() == generation;
    }

    private void restart() {
        invalidate();
        if (permission && desired && connection != 0 && rulesReady && !cleanup && !uncertainCleanup) {
            pending = new Request(connection, generation, true);
        }
    }

    private void invalidate() {
        generation++;
        running = false;
        pending = null;
        transitionFailure = Failure.NONE;
    }
}
