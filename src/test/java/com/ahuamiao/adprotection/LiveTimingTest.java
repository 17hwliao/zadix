package com.ahuamiao.adprotection;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LiveTimingTest {
    @Test public void waitResumeRetainsTheSampleAndDistinguishesActionReceipt() {
        var now = new AtomicLong(120);
        var rows = new ArrayList<String>();
        var timing = new LiveTiming(now::get, rows::add);
        var first = timing.schedule(LiveTiming.Target.QQ, 2, 17, 110, 118, 65);
        now.set(188);
        first.mark(LiveTiming.Step.SCAN);
        now.set(193);
        first.mark(LiveTiming.Step.QUERY_START);
        now.set(220);
        first.mark(LiveTiming.Step.QUERY_END);
        first.mark(LiveTiming.Step.MATCH);
        var resumed = first.waiting(850);
        first.finish();

        now.set(1075);
        resumed.mark(LiveTiming.Step.SCAN);
        resumed.mark(LiveTiming.Step.RECHECK_START);
        now.set(1086);
        resumed.mark(LiveTiming.Step.RECHECK_END);
        resumed.submitting(LiveTiming.Action.GESTURE);
        now.set(1092);
        resumed.receipt(false);
        resumed.finish();

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).contains("window=17 sample=1 pass=0 event=110 arrival=118 due=185"));
        assertTrue(rows.get(0).contains("MATCH=220 WAIT_PLANNED=220"));
        assertFalse(rows.get(0).contains("SUBMIT"));
        assertTrue(rows.get(1).contains("window=17 sample=1 pass=1 event=110 arrival=118 due=1070 SCAN=1075"));
        assertTrue(rows.get(1).contains("RECHECK_START=1075 RECHECK_END=1086 SUBMIT=1086 RECEIPT=1092 action=GESTURE accepted=false"));
        assertTrue(rows.get(1).endsWith("outcome=SUBMIT_REJECTED"));
    }

    @Test public void preSubmitDecisionsDoNotImplyAnActionAndKeepTheWindowAndPass() {
        var now = new AtomicLong(100);
        var rows = new ArrayList<String>();
        var timing = new LiveTiming(now::get, rows::add);
        var reasons = LiveTiming.Outcome.values();
        for (var reason : reasons) {
            if (reason == LiveTiming.Outcome.SUBMIT_ACCEPTED || reason == LiveTiming.Outcome.SUBMIT_REJECTED) continue;
            var first = timing.schedule(LiveTiming.Target.QQ, 3, 29, 99, 100, 65);
            var resumed = first.waiting(850);
            resumed.outcome(reason);
            resumed.finish();
            String row = rows.get(rows.size() - 1);
            assertTrue(row.contains("connection=3 window=29"));
            assertTrue(row.contains(" pass=1 event=99 arrival=100 due=950"));
            assertTrue(row.endsWith(" outcome=" + reason));
            assertFalse(row.contains(" SUBMIT="));
            assertFalse(row.contains(" accepted="));
        }

        var submitted = timing.schedule(LiveTiming.Target.QQ, 3, 29, 99, 100, 65);
        submitted.submitting(LiveTiming.Action.GESTURE);
        submitted.receipt(true);
        submitted.finish();
        assertTrue(rows.get(rows.size() - 1).endsWith("action=GESTURE accepted=true outcome=SUBMIT_ACCEPTED"));
    }

    @Test public void localRowsAreCappedEvenWhenAWaitIsAlreadyQueued() {
        var rows = new ArrayList<String>();
        var timing = new LiveTiming(() -> 12, rows::add);
        for (int i = 0; i < LiveTiming.MAX_ROWS - 1; i++) {
            timing.schedule(LiveTiming.Target.NETDISK, 1, 7, 10, 11, 65).finish();
        }
        var last = timing.schedule(LiveTiming.Target.QQ, 1, 8, 10, 11, 65);
        var queued = last.waiting(850);
        last.finish();
        queued.finish();
        assertEquals(LiveTiming.MAX_ROWS, rows.size());
        assertNull(timing.schedule(LiveTiming.Target.NETDISK, 1, 9, 10, 11, 65));
    }
}
