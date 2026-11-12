package com.ahuamiao.adprotection;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static com.ahuamiao.adprotection.QqMiniAppSplash.Label.*;
import static org.junit.Assert.*;

public final class QqMiniAppSplashTest {
    private static final String FRAME = "android.widget.FrameLayout";
    private static final String TEXT = "android.widget.TextView";
    private static final String BUTTON = "android.widget.ImageButton";
    private static final NetdiskSplash.Box SCREEN = new NetdiskSplash.Box(0, 0, 1080, 2376);

    @Test public void acceptsExactQqHostedWindowEvenWithAnotherActivityNamespace() {
        var host = QqMiniAppSplash.PACKAGE;
        var activity = QqMiniAppSplash.ACTIVITY;
        assertTrue(QqMiniAppSplash.inOpening(host, activity, 16750, 20, 900));
        assertFalse(QqMiniAppSplash.inOpening(host, activity, 16751, 20, 900));
        assertFalse(QqMiniAppSplash.inOpening(host, activity, 16750, 20, 10_021));
        assertFalse(QqMiniAppSplash.ownsWindow(host, activity.replace("Activity0", "Activity1")));
        assertFalse(QqMiniAppSplash.ownsWindow("com.tencent.mm", activity));
    }

    @Test public void selectsOnlyTheSkipTextInTheObservedCapsule() {
        assertEquals(new LiveRules.Match(QqMiniAppSplash.RULE, 7), QqMiniAppSplash.match(observed(), SCREEN));
        assertEquals(AD, QqMiniAppSplash.label("广告", null));
        assertEquals(SKIP, QqMiniAppSplash.label("跳过", null));
        assertEquals(NONE, QqMiniAppSplash.label("跳过登录", null));
        assertEquals(BLOCKED, QqMiniAppSplash.label("跳过", "确认支付"));
    }

    @Test public void aScheduledCheckRequiresAnEarlierMatchAndTheFullReadyDelay() {
        assertFalse(QqMiniAppSplash.readyForScheduledCheck(-1, 1000));
        assertFalse(QqMiniAppSplash.readyForScheduledCheck(200, 199));
        assertFalse(QqMiniAppSplash.readyForScheduledCheck(200, 1049));
        assertTrue(QqMiniAppSplash.readyForScheduledCheck(200, 1050));
    }

    @Test public void ignoresOnlyOlderPlaceholderEventsDeliveredAfterTheActivity() {
        var host = QqMiniAppSplash.PACKAGE;
        assertTrue(LiveController.staleStartingWindow(host, 63, 307854, host, FRAME, 62, 307641));
        assertFalse(LiveController.staleStartingWindow(host, 63, 307854, host, FRAME, 62, 308108));
        assertFalse(LiveController.staleStartingWindow(host, 63, 307854, host, FRAME, 63, 307641));
        assertFalse(LiveController.staleStartingWindow(host, 63, 307854, "com.android.systemui", FRAME, 62, 307641));
    }

    @Test public void rejectsDocumentTextAndAmbiguousOrMissingAdControls() {
        var nodes = observed();
        nodes.set(5, node(null, TEXT, NONE, 4, 72, 162, 156, 222));
        assertNull(QqMiniAppSplash.match(nodes, SCREEN));
        nodes = observed();
        nodes.set(6, node(null, FRAME, NONE, 3, 397, 164, 398, 219));
        assertNull(QqMiniAppSplash.match(nodes, SCREEN));
        nodes = observed();
        nodes.add(nodes.get(7));
        assertNull(QqMiniAppSplash.match(nodes, SCREEN));
        nodes = observed();
        nodes.add(node(null, "android.widget.EditText", NONE, 0, 30, 300, 300, 400));
        assertNull(QqMiniAppSplash.match(nodes, SCREEN));
        nodes = observed();
        nodes.remove(9);
        assertNull(QqMiniAppSplash.match(nodes, SCREEN));
    }

    private static List<QqMiniAppSplash.Node> observed() {
        return new ArrayList<>(List.of(
                node(null, FRAME, NONE, -1, 0, 0, 1080, 2376),
                node(null, FRAME, NONE, 0, 36, 144, 379, 240),
                node(null, FRAME, NONE, 1, 36, 144, 379, 240),
                node(null, FRAME, NONE, 2, 251, 162, 344, 222),
                node(null, FRAME, NONE, 1, 72, 144, 196, 240),
                node(null, TEXT, AD, 4, 72, 162, 156, 222),
                node(null, FRAME, NONE, 2, 222, 164, 223, 219),
                node(null, TEXT, SKIP, 3, 251, 162, 344, 222),
                node("com.tencent.mobileqq:id/57", BUTTON, NONE, 0, 763, 144, 907, 240),
                node("com.tencent.mobileqq:id/56", BUTTON, NONE, 0, 909, 144, 1053, 240)
        ));
    }

    private static QqMiniAppSplash.Node node(String id, String kind, QqMiniAppSplash.Label label,
                                             int parent, int x, int y, int right, int bottom) {
        return new QqMiniAppSplash.Node(id, kind, label, true, true, parent,
                new NetdiskSplash.Box(x, y, right, bottom));
    }
}
