package com.ahuamiao.adprotection;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class LiveRulesTest {
    private static final String PACKAGE = "example.fixture.app";
    private static final String ACTIVITY = PACKAGE + ".AdsActivity";
    private static final String RULE = """
            {
              "id": "close-fixture-ad",
              "packageName": "example.fixture.app",
              "minVersionCode": 3,
              "maxVersionCode": 5,
              "activityName": "example.fixture.app.AdsActivity",
              "adMarkerId": "example.fixture.app:id/ad",
              "closeViewId": "example.fixture.app:id/close",
              "blockedViewIds": ["example.fixture.app:id/confirm_payment"],
              "action": "clickNode"
            }
            """;
    private static final LiveRules.Node MARKER = node("ad", true, false);
    private static final LiveRules.Node CLOSE = node("close", true, true);
    private static final LiveRules.Node PAYMENT = node("confirm_payment", true, true);

    @Test public void matchesExactAppActivityAndInclusiveVersions() throws Exception {
        LiveRules rules = parse(RULE);
        List<LiveRules.Node> page = List.of(new LiveRules.Node(null, true, false), MARKER, CLOSE);
        assertEquals(Set.of(PACKAGE), rules.packages());
        assertTrue(rules.supports(PACKAGE, 3));
        assertTrue(rules.supports(PACKAGE, 5));
        assertFalse(rules.supports(PACKAGE + ".other", 3));
        assertFalse(rules.supports(PACKAGE, 2));
        assertEquals(new LiveRules.Match("close-fixture-ad", 2), rules.match(PACKAGE, 3, ACTIVITY, page));
        assertEquals(new LiveRules.Match("close-fixture-ad", 2), rules.match(PACKAGE, 5, ACTIVITY, page));
        assertNull(rules.match(PACKAGE, 6, ACTIVITY, page));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY + "Dialog", page));
        assertNull(rules.match(PACKAGE + ".other", 3, ACTIVITY, page));
    }

    @Test public void rejectsNormalSensitiveAndAmbiguousPages() throws Exception {
        LiveRules rules = parse(RULE);
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(CLOSE)));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(MARKER, CLOSE, PAYMENT)));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(node("ad", false, false), CLOSE)));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(MARKER, node("close", true, false))));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(MARKER, CLOSE, node("close", false, true))));
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, List.of(MARKER, MARKER, CLOSE)));
        LiveRules overlapping = parse(RULE + "," + RULE.replace("close-fixture-ad", "other-rule"));
        assertNull(overlapping.match(PACKAGE, 3, ACTIVITY, List.of(MARKER, CLOSE)));
    }

    @Test public void adPresenceSurvivesAnIneligibleCloseControl() throws Exception {
        LiveRules rules = parse(RULE);
        List<LiveRules.Node> page = List.of(MARKER, CLOSE, CLOSE);
        assertNull(rules.match(PACKAGE, 3, ACTIVITY, page));
        assertTrue(rules.hasAdMarker(PACKAGE, 3, ACTIVITY, page));
        assertTrue(rules.hasAdMarker(PACKAGE, 3, ACTIVITY, List.of(MARKER, PAYMENT)));
        assertTrue(rules.hasAdMarker(PACKAGE, 3, ACTIVITY, List.of(node("ad", false, false))));
        assertFalse(rules.hasAdMarker(PACKAGE, 3, ACTIVITY, List.of(CLOSE)));
        assertFalse(rules.hasAdMarker(PACKAGE, 3, ACTIVITY + "Other", page));
    }

    @Test public void refusesToDropAnUnsupportedConditionOrAction() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                RULE + "," + RULE.replace("\"id\": \"close-fixture-ad\",", "\"id\": \"other\", \"matchDelay\": 300,")));
        assertThrows(IllegalArgumentException.class, () -> parse(RULE.replace("clickNode", "clickCenter")));
        assertThrows(IllegalArgumentException.class, () -> parse(
                RULE.replace("\"minVersionCode\": 3,", "")));
        assertThrows(IllegalArgumentException.class, () -> parse(
                RULE.replace("example.fixture.app:id/close", "com.android.settings:id/close")));
    }

    @Test public void rejectsDuplicateFieldsAndAmbiguousJson() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                RULE.replace("\"minVersionCode\": 3,", "\"minVersionCode\": 3, \"minVersionCode\": 1,")));
        assertThrows(IllegalArgumentException.class, () -> parse(RULE.replace("\"minVersionCode\": 3", "\"minVersionCode\": \"3\"")));
        assertThrows(IllegalArgumentException.class, () -> LiveRules.parse(wrap(RULE) + " {}"));
        assertThrows(IllegalArgumentException.class, () -> parse(RULE + "," + RULE));
    }

    private static LiveRules.Node node(String id, boolean visible, boolean clickable) {
        return new LiveRules.Node(PACKAGE + ":id/" + id, visible, clickable);
    }

    private static LiveRules parse(String rules) throws Exception {
        return LiveRules.parse(wrap(rules));
    }

    private static String wrap(String rules) {
        return "{\"schemaVersion\":1,\"rules\":[" + rules + "]}";
    }
}
