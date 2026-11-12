package com.ahuamiao.adprotection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import li.songe.zadixsource.data.StartupProfile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RulePackage {
    static final String SUFFIX = ".zadixsource";
    static final String MIME = "application/octet-stream";
    private static final String MANIFEST = "manifest.json";
    private static final String PROFILE = "profile.json";
    private static final String LEGACY_PROFILE = "transfer_data.json";
    private static final int MAX_SOURCES = 126;
    private static final byte[] HEADER = utf8("{\"format\":\"zadixsource\",\"version\":1}");
    record Parsed(StartupProfile profile, byte[] archive) {}

    static Parsed read(byte[] archive) throws IOException {
        var entries = readZip(archive, true);
        if (!entries.containsKey(MANIFEST) || !entries.containsKey(PROFILE)) {
            throw new IOException("请选择有效的 .zadixsource 资料包");
        }
        var sources = new ArrayList<String>();
        for (int i = 1; i <= entries.size() - 2; i++) {
            byte[] rule = entries.get("rules/" + i + ".json5");
            if (rule == null || rule.length == 0) throw new IOException("资料包包含未知条目或缺少规则文件");
            sources.add(string(rule));
        }
        try {
            var manifest = new JSONObject(string(entries.get(MANIFEST)));
            if (!keys(manifest).equals(Set.of("format", "version"))
                    || !"zadixsource".equals(manifest.get("format"))
                    || !Integer.valueOf(1).equals(manifest.get("version"))) {
                throw new IOException("此版本无法读取该资料包格式");
            }
            var profile = settings(new JSONObject(string(entries.get(PROFILE))), false);
            var result = StartupProfile.readSource(profile.toString(), sources);
            entries.put(MANIFEST, HEADER);
            entries.put(PROFILE, utf8(profile.toString()));
            return new Parsed(result, writeZip(entries));
        } catch (RuntimeException | JSONException error) {
            throw new IOException("资料包规则或启用配置无效", error);
        }
    }

    static byte[] fromLegacy(List<byte[]> documents) throws IOException {
        var sources = new ArrayList<byte[]>();
        JSONObject profile = null;
        try {
            for (byte[] document : documents) {
                if (!isZip(document)) {
                    sources.add(document);
                    continue;
                }
                if (profile != null) throw new IOException("旧规则包含多份配置");
                var entries = readZip(document, false);
                byte[] config = entries.get(LEGACY_PROFILE);
                if (config == null) throw new IOException("缺少旧规则配置");
                var original = new JSONObject(string(config));
                if (!"transfer_data".equals(original.getString("type"))) throw new IOException("旧规则配置无效");
                profile = settings(original, true);
                for (var entry : entries.entrySet()) {
                    if (entry.getKey().matches("files/-[0-9]+\\.json")) sources.add(entry.getValue());
                }
            }
        } catch (JSONException error) {
            throw new IOException("旧规则配置无效", error);
        }
        if (profile == null || sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw new IOException("旧规则文件数量无效");
        }
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(MANIFEST, HEADER);
        entries.put(PROFILE, utf8(profile.toString()));
        for (int i = 0; i < sources.size(); i++) entries.put("rules/" + (i + 1) + ".json5", sources.get(i));
        return writeZip(entries);
    }

    private static JSONObject settings(JSONObject original, boolean legacy) throws JSONException, IOException {
        if (!legacy && !keys(original).equals(Set.of("sources", "groups", "categories"))) {
            throw new IOException("资料包配置包含未知字段");
        }
        var profile = new JSONObject();
        copyList(original, profile, legacy, "subsItems", "sources", "id", "order", "enable");
        copyList(original, profile, legacy, "subsConfigs", "groups",
                "subsItemId", "type", "appId", "groupKey", "enable", "exclude");
        copyList(original, profile, legacy, "categoryConfigs", "categories", "subsItemId", "categoryKey", "enable");
        return profile;
    }

    private static void copyList(JSONObject source, JSONObject target, boolean legacy, String oldName,
                                 String name, String... fields) throws JSONException, IOException {
        var key = legacy ? oldName : name;
        var list = legacy && !source.has(key) ? new JSONArray() : source.getJSONArray(key);
        var clean = new JSONArray();
        var allowed = Set.of(fields);
        for (int i = 0; i < list.length(); i++) {
            var row = list.getJSONObject(i);
            if (!legacy && !allowed.containsAll(keys(row))) throw new IOException("资料包配置包含未知字段");
            var copy = new JSONObject();
            for (String field : fields) {
                Object value = row.opt(field);
                if (!legacy && !validValue(field, value)) {
                    throw new IOException("资料包配置字段无效");
                }
                if (value != null) copy.put(field, value);
            }
            clean.put(copy);
        }
        target.put(name, clean);
    }

    private static Set<String> keys(JSONObject value) {
        var result = new HashSet<String>();
        value.keys().forEachRemaining(result::add);
        return result;
    }

    private static boolean validValue(String field, Object value) {
        boolean optional = value == null || value == JSONObject.NULL;
        return switch (field) {
            case "enable" -> optional || value instanceof Boolean;
            case "appId", "exclude" -> optional || value instanceof String;
            case "groupKey" -> value == null || value instanceof Integer;
            case "id", "subsItemId" -> value instanceof Integer || value instanceof Long;
            default -> value instanceof Integer;
        };
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    private static Map<String, byte[]> readZip(byte[] bytes, boolean canonical) throws IOException {
        if (bytes.length > StartupProfile.MAX_BYTES || !isZip(bytes)) throw new IOException("资料包格式或大小无效");
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int remaining = StartupProfile.MAX_BYTES;
            int count = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 128) throw new IOException("资料包文件过多");
                var data = zip.readNBytes(remaining + 1);
                if (data.length > remaining) throw new IOException("资料包解压后超过大小限制");
                remaining -= data.length;
                if (entry.isDirectory()) {
                    if (canonical) throw new IOException("资料包含有未知目录");
                } else if (entries.putIfAbsent(entry.getName(), data) != null) {
                    throw new IOException("资料包含有重复文件");
                }
            }
        }
        return entries;
    }

    private static byte[] writeZip(Map<String, byte[]> entries) throws IOException {
        long size = 0;
        for (byte[] data : entries.values()) size += data.length;
        if (size > StartupProfile.MAX_BYTES) throw new IOException("资料包超过大小限制");
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setTime(0);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        if (out.size() > StartupProfile.MAX_BYTES) throw new IOException("资料包超过大小限制");
        return out.toByteArray();
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String string(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private RulePackage() {}
}
