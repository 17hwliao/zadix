package com.ahuamiao.adprotection;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.Test;
import org.json.JSONObject;

public final class ProtectionTest {
    private final Rule rule = new Rule("test-rule", 1, "OnePlus", "PKR110", "build", "system", "ad_switch", "0", "测试", true);
    private final String device = "0".repeat(64);
    private final Reading before = new Reading(device, "OnePlus", "PKR110", "build", 0, 8, "1");

    private Protection.State ready() {
        return new Protection.State(new Receipt("", "cycle", device, 0, "OnePlus", "PKR110", "build", "test-rule", 1, "1", "0"),
                Protection.Phase.READY, false, -1, null, "", 0, -1);
    }

    @Test public void journalSurvivesLostAcknowledgementWithoutClaimingSuccess() throws Exception {
        List<Protection.State> journal = new ArrayList<>();
        assertThrows(IOException.class, () -> Protection.writeSequence(ready(), before, "0", Protection.Phase.APPLY_PENDING,
                Protection.Phase.APPLIED, journal::add, () -> {
                    assertEquals(Protection.Phase.APPLY_PENDING, journal.get(0).phase());
                    throw new IOException("transport lost");
                }, () -> { throw new AssertionError("must wait for command acknowledgement"); }));
        assertEquals(1, journal.size());
        assertNull(journal.get(0).exitCode());
        assertTrue(journal.get(0).needsReview());
    }

    @Test public void successRequiresBothAcknowledgementAndMatchingReadback() throws Exception {
        List<Protection.State> journal = new ArrayList<>();
        Reading target = new Reading(device, "OnePlus", "PKR110", "build", 0, 8, "0");
        Protection.writeSequence(ready(), before, "0", Protection.Phase.APPLY_PENDING,
                Protection.Phase.APPLIED, journal::add, () -> new LocalAdb.Result(0, ""), () -> before);
        assertEquals(Protection.Phase.APPLY_PENDING, journal.get(2).phase());
        assertEquals(Integer.valueOf(0), journal.get(2).exitCode());
        journal.clear();
        Protection.writeSequence(ready(), before, "0", Protection.Phase.APPLY_PENDING,
                Protection.Phase.APPLIED, journal::add, () -> new LocalAdb.Result(0, ""), () -> target);
        assertEquals(Protection.Phase.APPLIED, journal.get(2).phase());
        assertEquals("0", journal.get(2).observed());
    }

    @Test public void recordRejectsTamperingAndWrongBuild() throws Exception {
        JSONObject payload = new JSONObject().put("device", device).put("user", 0).put("brand", "OnePlus")
                .put("model", "PKR110").put("build", "build").put("rule", "test-rule").put("version", 1)
                .put("cycle", "5f251c77-10cb-4c6a-8203-37b743b35aea").put("read_at", "2026-11-08T00:00:00Z")
                .put("original", "1").put("target", "0").put("restore", "restore-original");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        String valid = envelope(bytes, Receipt.digest(bytes));
        assertTrue(Receipt.parse(valid, rule).matches(before));
        byte[] changed = payload.put("build", "unexpected").toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Receipt.parse(envelope(changed, Receipt.digest(bytes)), rule));
        assertThrows(IllegalArgumentException.class, () -> Receipt.parse(envelope(changed, Receipt.digest(changed)), rule));
    }

    @Test public void pairingCertificateUsesAndSignsTheGeneratedKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var certificate = LocalAdb.selfSignedCertificate(keys);
        certificate.verify(keys.getPublic());
        assertEquals(keys.getPublic(), certificate.getPublicKey());
    }

    @Test public void readbackIgnoresShellBannersButRejectsRepeatedFields() throws Exception {
        String output = "shell message\nAAP_DEVICE=test-serial\nAAP_BRAND=OnePlus\nAAP_MODEL=PKR110\n"
                + "AAP_BUILD=build\nAAP_USER=0\nAAP_BOOT=29\nAAP_VALUE=0";
        assertEquals("0", Reading.parse(output).value());
        assertThrows(IllegalArgumentException.class, () -> Reading.parse(output + "\nAAP_VALUE=1"));
    }

    private static String envelope(byte[] payload, String checksum) throws Exception {
        var encoder = Base64.getUrlEncoder();
        return encoder.encodeToString(new JSONObject().put("format", 1).put("payload", encoder.encodeToString(payload))
                .put("sha256", checksum).toString().getBytes(StandardCharsets.UTF_8));
    }
}
