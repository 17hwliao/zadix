package com.ahuamiao.adprotection;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClosingEvidenceTest {
    @Test public void requiresStableDestinationAndNoAd() {
        var evidence = new ClosingEvidence(1000);
        var destination = new ClosingEvidence.Screen(false, true, true);
        assertFalse(evidence.observe(new ClosingEvidence.Screen(false, true, false), 1120));
        assertFalse(evidence.observe(destination, 1240));
        assertFalse(evidence.observe(new ClosingEvidence.Screen(true, true, true), 1360));
        assertFalse(evidence.observe(destination, 1480));
        assertFalse(evidence.observe(destination, 1599));
        assertTrue(evidence.observe(destination, 1600));
    }

    @Test public void aMissingWindowOrLateDestinationDoesNotConfirm() {
        var evidence = new ClosingEvidence(1000);
        var destination = new ClosingEvidence.Screen(false, true, true);
        assertFalse(evidence.observe(destination, 1200));
        assertFalse(evidence.observe(null, 1320));
        assertFalse(evidence.observe(destination, 1890));
        assertFalse(evidence.observe(destination, 2010));
        assertTrue(evidence.expired(2010));
    }
}
