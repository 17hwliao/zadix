package com.ahuamiao.adprotection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;

record Receipt(String encoded, String cycle, String device, int user, String brand, String model,
               String build, String rule, int version, String original, String target) {
    static String digest(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item & 255));
        return hex.toString();
    }

    static Receipt parse(String encoded, Rule rule) throws Exception {
        if (encoded == null || encoded.length() > 8192) throw new IllegalArgumentException("恢复记录大小无效");
        JSONObject envelope = new JSONObject(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        if (envelope.getInt("format") != 1) throw new IllegalArgumentException("恢复记录版本无效");
        byte[] payload = Base64.getUrlDecoder().decode(envelope.getString("payload"));
        if (!digest(payload).equals(envelope.getString("sha256"))) {
            throw new IllegalArgumentException("恢复记录校验失败");
        }
        JSONObject j = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        String cycle = j.getString("cycle");
        Instant.parse(j.getString("read_at"));
        Receipt receipt = new Receipt(encoded, cycle, j.getString("device"), j.getInt("user"),
                j.getString("brand"), j.getString("model"), j.getString("build"), j.getString("rule"),
                j.getInt("version"), j.getString("original"), j.getString("target"));
        if (!UUID.fromString(cycle).toString().equals(cycle) || !receipt.device.matches("[0-9a-f]{64}")
                || receipt.user != 0 || !rule.brand().equals(receipt.brand) || !rule.model().equals(receipt.model)
                || !rule.build().equals(receipt.build) || !rule.id().equals(receipt.rule)
                || rule.version() != receipt.version || !Rule.validValue(receipt.original)
                || !rule.target().equals(receipt.target) || receipt.original.equals(receipt.target)
                || !j.getString("restore").equals("restore-original")) {
            throw new IllegalArgumentException("恢复记录与规则不匹配");
        }
        return receipt;
    }

    boolean matches(Reading reading) {
        return device.equals(reading.device()) && user == reading.user() && brand.equals(reading.brand())
                && model.equals(reading.model()) && build.equals(reading.build());
    }
}
