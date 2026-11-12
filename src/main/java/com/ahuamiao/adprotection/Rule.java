package com.ahuamiao.adprotection;

import org.json.JSONObject;

record Rule(String id, int version, String brand, String model, String build, String namespace,
            String key, String target, String title, boolean enabled) {
    Rule {
        if (!namespace.matches("system|secure") || !key.matches("[a-zA-Z0-9_]+") || !validValue(target)) {
            throw new IllegalArgumentException("规则配置无效");
        }
    }

    static Rule parse(String json) throws Exception {
        JSONObject j = new JSONObject(json);
        return new Rule(j.getString("id"), j.getInt("version"), j.getString("brand"), j.getString("model"),
                j.getString("build"), j.getString("namespace"), j.getString("key"),
                j.getString("target"), j.getString("title"), j.getBoolean("enabled"));
    }

    static boolean validValue(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    String readCommand() {
        return "field() { label=$1; shift; value=$(\"$@\") || return 1; printf 'AAP_%s=%s\\n' \"$label\" \"$value\"; }; "
                + "field DEVICE getprop ro.serialno && field BRAND getprop ro.product.brand && "
                + "field MODEL getprop ro.product.model && field BUILD getprop ro.build.display.id && "
                + "field USER am get-current-user && field BOOT settings get global boot_count && "
                + "field VALUE settings --user 0 get " + namespace + " " + key;
    }

    String writeCommand(String value) {
        if (!enabled || !validValue(value)) throw new IllegalArgumentException("这项规则尚不可写入");
        return "settings --user 0 put " + namespace + " " + key + " " + value;
    }

    boolean matches(Reading reading) {
        return reading.user() == 0 && brand.equals(reading.brand()) && model.equals(reading.model())
                && build.equals(reading.build());
    }
}

record Reading(String device, String brand, String model, String build, int user, int boot, String value) {
    static Reading parse(String output) throws Exception {
        var values = new java.util.HashMap<String, String>();
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith("AAP_")) continue;
            int equals = line.indexOf('=');
            if (equals < 0 || values.put(line.substring(4, equals), line.substring(equals + 1)) != null) {
                throw new IllegalArgumentException("设备信息重复或无效");
            }
        }
        if (!values.keySet().equals(java.util.Set.of("DEVICE", "BRAND", "MODEL", "BUILD", "USER", "BOOT", "VALUE"))
                || !values.get("DEVICE").matches("[a-zA-Z0-9._-]{4,128}")) {
            throw new IllegalArgumentException("读取设备状态失败");
        }
        int user = Integer.parseInt(values.get("USER"));
        int boot = Integer.parseInt(values.get("BOOT"));
        if (user < 0 || boot < 0 || !Rule.validValue(values.get("VALUE"))) {
            throw new IllegalArgumentException("无法确认当前设置值");
        }
        return new Reading(Receipt.digest(values.get("DEVICE").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                values.get("BRAND"), values.get("MODEL"), values.get("BUILD"), user, boot, values.get("VALUE"));
    }
}
