package com.ahuamiao.adprotection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import li.songe.zadixsource.data.AppInfo;
import li.songe.zadixsource.data.RuleSession;
import li.songe.zadixsource.data.StartupProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RulePackageTest {
    @Test public void convertsLegacyConfigurationAndPreservesRuleBehavior() throws Exception {
        var settings = """
                {"type":"transfer_data","subsItems":[{"id":501,"order":0,"enable":true,
                  "updateUrl":"https://example.invalid/private"},{"id":502,"order":1,"enable":false}],
                 "subsConfigs":[{"subsItemId":501,"type":3,"groupKey":0,"exclude":"sample.app/sample.app.Files"}],
                 "categoryConfigs":[],"history":["private-record"]}
                """;
        var source = """
                {id:501,name:'示例规则',version:1,globalGroups:[{key:0,name:'开屏广告',
                  rules:[{matches:'[text="跳过"]'}]}]}
                """;
        byte[] backup = zip(Map.of("transfer_data.json", settings, "other.txt", "private"));
        var bytes = RulePackage.fromLegacy(List.of(backup, source.getBytes(StandardCharsets.UTF_8)));
        var entries = entries(bytes);
        assertEquals(List.of("manifest.json", "profile.json", "rules/1.json5"), List.copyOf(entries.keySet()));
        assertEquals(source, entries.get("rules/1.json5"));
        assertFalse(entries.get("profile.json").contains("private"));
        assertFalse(entries.get("profile.json").contains("updateUrl"));
        assertFalse(entries.get("profile.json").contains("history"));
        var legacy = StartupProfile.read(new ByteArrayInputStream(backup), List.of(source));
        var decoded = RulePackage.read(bytes);
        assertEquals(legacy.description(), decoded.profile().description());
        assertArrayEquals(decoded.archive(), RulePackage.read(decoded.archive()).archive());
        var app = new AppInfo("sample.app", 1, "1", false, false);
        var legacyRule = legacy.compile(app, new RuleSession(() -> 4000L)).get(0);
        var decodedRule = decoded.profile().compile(app, new RuleSession(() -> 4000L)).get(0);
        assertTrue(decodedRule.matchActivity(app.getId(), "sample.app.Opening"));
        assertFalse(decodedRule.matchActivity(app.getId(), "sample.app.Files"));
        assertEquals(legacyRule.matchActivity(app.getId(), "sample.app.Files"),
                decodedRule.matchActivity(app.getId(), "sample.app.Files"));
    }

    @Test public void rejectsWrongFormatAndUnknownEntries() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("manifest.json", "{\"format\":\"zadixsource\",\"version\":2}");
        entries.put("profile.json", "{\"sources\":[],\"groups\":[],\"categories\":[]}");
        assertThrows(IOException.class, () -> RulePackage.read(zip(entries)));
        entries.put("manifest.json", "{\"format\":\"zadixsource\",\"version\":1}");
        entries.put("../foreign", "not a rule file");
        assertThrows(IOException.class, () -> RulePackage.read(zip(entries)));
        assertThrows(IOException.class, () -> RulePackage.read(zip(Map.of("transfer_data.json", "{}"))));
    }

    @Test public void rejectsMissingEnabledSourceAndUnknownProfileFields() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("manifest.json", "{\"format\":\"zadixsource\",\"version\":1}");
        entries.put("profile.json", """
                {"sources":[{"id":501,"order":0}],"groups":[],"categories":[]}
                """);
        entries.put("rules/1.json5", "{id:502,name:'示例',version:1}");
        assertThrows(IOException.class, () -> RulePackage.read(zip(entries)));
        entries.put("profile.json", """
                {"sources":[{"id":502,"order":0,"unknown":true}],"groups":[],"categories":[]}
                """);
        assertThrows(IOException.class, () -> RulePackage.read(zip(entries)));
    }

    private static Map<String, String> entries(byte[] archive) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
