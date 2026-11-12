package com.ahuamiao.adprotection;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Local diagnostics. A row is emitted after the scan so logging stays outside the action gate. */
final class LiveTiming {
    static final String TAG = "AdKillerTiming";
    static final String ENABLE_FILE = "live-timing.enabled";
    static final int MAX_ROWS = 384;

    enum Target { QQ, NETDISK, FIXTURE }
    enum ReadPhase { QUERY, RECHECK }
    enum Step {
        SCAN, QUERY_START, QUERY_END, MATCH, WAIT_PLANNED, RECHECK_START, RECHECK_END, RECHECK_MATCH,
        CLAIM_START, CLAIM_END, TARGET_REFRESH_START, TARGET_REFRESH_END, SUBMIT, RECEIPT
    }
    enum Action { NODE, GESTURE }
    enum Outcome {
        WINDOW_CHANGED, GATE_REJECTED, ROOT_UNAVAILABLE, TREE_INCOMPLETE, TREE_BUDGET,
        OUTSIDE_OPENING, NO_AD_MATCH, TARGET_UNAVAILABLE, TARGET_CHANGED,
        ALREADY_CLAIMED, CLAIM_CAPACITY, CLAIM_STORAGE_FAILED, SUBMIT_REJECTED, SUBMIT_ACCEPTED
    }

    private record Sample(Target target, long connection, int window, long id, long event, long arrival) {}
    private final LongSupplier uptime;
    private final LongSupplier treeClock;
    private final Consumer<String> output;
    private long nextSample;
    private int rows;

    LiveTiming(LongSupplier uptime, Consumer<String> output) {
        this(uptime, null, output);
    }

    LiveTiming(LongSupplier uptime, LongSupplier treeClock, Consumer<String> output) {
        this.uptime = uptime;
        this.treeClock = treeClock;
        this.output = output;
    }

    Pass schedule(Target target, long connection, int window, long event, long arrival, long delay) {
        if (rows >= MAX_ROWS) return null;
        return new Pass(new Sample(target, connection, window, ++nextSample, event, arrival),
                0, uptime.getAsLong() + delay);
    }

    final class Pass {
        private final Sample sample;
        private final int index;
        private final long due;
        private final long[] times = new long[Step.values().length];
        private Action action;
        private boolean accepted;
        private Outcome outcome;
        private TreeRead query;
        private TreeRead recheck;

        private Pass(Sample sample, int index, long due) {
            this.sample = sample;
            this.index = index;
            this.due = due;
            Arrays.fill(times, -1);
        }

        void mark(Step step) { times[step.ordinal()] = uptime.getAsLong(); }

        void outcome(Outcome value) { outcome = value; }

        TreeRead treeRead(ReadPhase phase) {
            if (treeClock == null) return null;
            var read = new TreeRead(treeClock);
            if (phase == ReadPhase.QUERY) query = read;
            else recheck = read;
            return read;
        }

        Pass waiting(long delay) {
            mark(Step.WAIT_PLANNED);
            return rows < MAX_ROWS ? new Pass(sample, index + 1, times[Step.WAIT_PLANNED.ordinal()] + delay) : null;
        }

        void submitting(Action action) {
            this.action = action;
            mark(Step.SUBMIT);
        }

        void receipt(boolean accepted) {
            this.accepted = accepted;
            outcome(accepted ? Outcome.SUBMIT_ACCEPTED : Outcome.SUBMIT_REJECTED);
            mark(Step.RECEIPT);
        }

        void finish() {
            if (rows >= MAX_ROWS) return;
            rows++;
            StringBuilder line = new StringBuilder("target=").append(sample.target())
                    .append(" connection=").append(sample.connection()).append(" window=").append(sample.window())
                    .append(" sample=").append(sample.id()).append(" pass=").append(index)
                    .append(" event=").append(sample.event()).append(" arrival=").append(sample.arrival())
                    .append(" due=").append(due);
            for (Step step : Step.values()) {
                long time = times[step.ordinal()];
                if (time >= 0) line.append(' ').append(step).append('=').append(time);
            }
            if (action != null) line.append(" action=").append(action);
            if (times[Step.RECEIPT.ordinal()] >= 0) line.append(" accepted=").append(accepted);
            if (outcome != null) line.append(" outcome=").append(outcome);
            if (query != null) query.append(line, "query");
            if (recheck != null) recheck.append(line, "recheck");
            output.accept(line.toString());
        }
    }

    static final class TreeRead {
        private final LongSupplier clock;
        private long root = -1;
        private long container = -1;
        private long walkStart = -1;
        private long walk = -1;
        private long rule = -1;
        private int nodes;
        private int childCalls;
        private int boundsCalls;
        private long childTotal;
        private long childMax;

        TreeRead(LongSupplier clock) { this.clock = clock; }

        long now() { return clock.getAsLong(); }
        void rootRead(long start) { root = now() - start; }
        void containerRead(long start) { container = now() - start; }
        void startWalk() { walkStart = now(); }
        void visit() { nodes++; }
        void boundsRead() { boundsCalls++; }

        void childRead(long start) {
            long elapsed = now() - start;
            childCalls++;
            childTotal += elapsed;
            childMax = Math.max(childMax, elapsed);
        }

        void finishWalk() {
            if (walkStart >= 0 && walk < 0) walk = now() - walkStart;
        }

        void matched(long start) { rule = now() - start; }

        void append(StringBuilder line, String prefix) {
            appendMicros(line, prefix + "_root_us", root);
            appendMicros(line, prefix + "_container_us", container);
            appendMicros(line, prefix + "_walk_us", walk);
            appendMicros(line, prefix + "_rule_us", rule);
            line.append(' ').append(prefix).append("_nodes=").append(nodes)
                    .append(' ').append(prefix).append("_children=").append(childCalls)
                    .append(' ').append(prefix).append("_bounds=").append(boundsCalls);
            appendMicros(line, prefix + "_child_total_us", childTotal);
            appendMicros(line, prefix + "_child_max_us", childMax);
        }

        private static void appendMicros(StringBuilder line, String field, long nanos) {
            if (nanos >= 0) line.append(' ').append(field).append('=').append(nanos / 1_000);
        }
    }
}
