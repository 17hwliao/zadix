package com.ahuamiao.adprotection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ahuamiao.adprotection.NetdiskSplash.Label.*;

public final class NetdiskSplashTest {
    private static final NetdiskSplash.Box DISPLAY = new NetdiskSplash.Box(0, 0, 1080, 2376);
    private static final NetdiskSplash.Node CONTAINER = new NetdiskSplash.Node(
            NetdiskSplash.AD_CONTAINER, NONE, true, true, -1, DISPLAY);
    private static final NetdiskSplash.Node BADGE = node(BRAND_AD, false, 0, 474, 72, 774, 138);
    private static final NetdiskSplash.Node LABEL = node(SKIP, false, 2, 870, 76, 1005, 134);
    private static final NetdiskSplash.Node BUTTON = node(NONE, true, 0, 819, 60, 1035, 150);

    @Test public void requiresAnExactOpeningWindowAndVersion() {
        assertTrue(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE, NetdiskSplash.MAIN, 4262, 100, 600));
        assertFalse(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE, NetdiskSplash.MAIN, 4263, 100, 600));
        assertFalse(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE, NetdiskSplash.MAIN, 4262, -1, 600));
        assertTrue(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE, NetdiskSplash.MAIN, 4262, 100, 6600));
        assertFalse(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE, NetdiskSplash.MAIN, 4262, 100, 10101));
        assertFalse(NetdiskSplash.inOpening(NetdiskSplash.PACKAGE,
                NetdiskSplash.PACKAGE + ".ui.DefaultMainActivity", 4262, 100, 600));
    }

    @Test public void closesTheSmallActionableParentNotTheAdvertisingSurface() {
        assertEquals(SKIP, NetdiskSplash.label("跳过 02"));
        assertEquals(SKIP, NetdiskSplash.label("跳过"));
        assertEquals(NONE, NetdiskSplash.label("跳过登录"));
        var expected = new LiveRules.Match(NetdiskSplash.RULE, 2);
        assertEquals(expected, NetdiskSplash.match(List.of(CONTAINER, BADGE, BUTTON, LABEL), DISPLAY).match());
        var directSkip = node(SKIP, true, 0, 819, 60, 1035, 150);
        assertEquals(expected, NetdiskSplash.match(List.of(CONTAINER, BADGE, directSkip), DISPLAY).match());

        var fullscreen = new NetdiskSplash.Node(null, NONE, true, true, -1, DISPLAY);
        assertNull(NetdiskSplash.match(List.of(fullscreen, BADGE, directSkip), DISPLAY).match());
        assertNull(NetdiskSplash.match(List.of(CONTAINER, BADGE, fullscreen, LABEL), DISPLAY).match());
        assertNull(NetdiskSplash.match(List.of(CONTAINER, BUTTON, LABEL), DISPLAY).match());
        assertNull(NetdiskSplash.match(List.of(CONTAINER, BADGE, BUTTON, LABEL, LABEL), DISPLAY).match());
        var dialog = new NetdiskSplash.Node(NetdiskSplash.PACKAGE + ":id/dialog_cancel",
                NONE, true, true, -1, DISPLAY);
        assertNull(NetdiskSplash.match(List.of(CONTAINER, BADGE, BUTTON, LABEL, dialog), DISPLAY).match());
    }

    @Test public void bundledResourceRulesRequireBothSplashNodes() throws Exception {
        LiveRules rules = LiveRules.parse(Files.readString(Path.of("src/main/assets/live-rules.json")));
        String pkg = NetdiskSplash.PACKAGE;
        String activity = NetdiskSplash.MAIN;
        for (String[] pair : List.of(new String[]{"full_mark", "countdown"}, new String[]{"tv_dsp", "tv_skip"})) {
            var marker = new LiveRules.Node(pkg + ":id/" + pair[0], true, false);
            var close = new LiveRules.Node(pkg + ":id/" + pair[1], true, true);
            assertNotNull(rules.match(pkg, 4262, activity, List.of(marker, close)));
            assertNull(rules.match(pkg, 4262, activity, List.of(close)));
            assertNull(rules.match(pkg, 4262, activity, List.of(marker, close, close)));
            assertNull(rules.match(pkg, 4263, activity, List.of(marker, close)));
        }
    }

    private static NetdiskSplash.Node node(NetdiskSplash.Label label, boolean clickable, int parent,
                                           int left, int top, int right, int bottom) {
        return new NetdiskSplash.Node(null, label, true, clickable, parent,
                new NetdiskSplash.Box(left, top, right, bottom));
    }
}
