package com.ahuamiao.adprotection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import li.songe.zadixsource.data.AppInfo;
import li.songe.zadixsource.data.RuleSession;
import li.songe.zadixsource.data.StartupProfile;
import org.junit.Test;

public class StartupSourceProfileTest {
    private static final AppInfo APP = new AppInfo("sample.app", 3, "3", false, false);
    private static final String SOURCE = """
            {
              id: 101, name: 'Sample', version: 90,
              categories: [{key: 7, name: '开屏广告', enable: true}],
              globalGroups: [
                {key: 0, name: '开屏广告-全局', fastQuery: true, order: -10,
                 rules: [{matches: '[text*="跳过"]', excludeMatches: '[text="搜索"]'}]},
                {key: 1, name: '通知提示', rules: [{matches: '[text="关闭"]'}]},
                {key: 2, name: '开屏广告-非法选择器', rules: [{matches: '[inventedAttr=true]'}]},
              ],
              apps: [{id: 'sample.app', groups: [
                {key: 4, name: '开屏广告-应用', activityIds: ['.Opening'],
                 versionCodes: [3], resetMatch: 'app', actionMaximum: 1, actionMaximumKey: 0,
                 rules: [{key: 0, matches: '[vid="skip"]'}, {key: 1, matches: '[desc="跳过"]'}]},
              ]}],
            }
            """;

    @Test public void loadsJson5WithVersionScopeAndOnlyValidStartupGroups() throws IOException {
        var profile = read("[]", "[]", List.of(SOURCE));
        var rules = profile.compile(APP, new RuleSession(() -> 4000L));
        assertEquals("资料来源 #101 v90", profile.description());
        assertEquals(List.of("global", "app", "app"), rules.stream().map(rule -> rule.getType()).toList());
        assertTrue(rules.get(0).getMatchOption().getFastQuery());
        assertTrue(rules.get(1).matchActivity(APP.getId(), "sample.app.Opening"));
        assertFalse(rules.get(1).matchActivity(APP.getId(), "sample.app.Files"));
        assertEquals(1, profile.compile(new AppInfo("sample.app", 4, "4", false, false),
                new RuleSession(() -> 4000L)).size());
        rules.get(1).trigger();
        assertFalse(rules.get(2).getStatus().getOk());
    }

    @Test public void listsRecordedCategoriesSeparatelyFromExecutableOpeningRules() {
        var profile = StartupProfile.readSource("""
                {"sources":[{"id":101,"order":0},{"id":102,"order":1,"enable":false}],
                 "groups":[],"categories":[]}
                """, List.of("""
                {id:101,name:'Active',version:1,apps:[
                  {id:'sample.app',groups:[
                    {key:0,name:'开屏广告',versionCodes:[3],rules:[{matches:'[text="跳过"]'}]},
                    {key:1,name:'全屏广告-推广',rules:[{matches:'[text="关闭"]'}]}
                  ]}
                ]}
                """, """
                {id:102,name:'Paused',version:1,apps:[
                  {id:'paused.app',groups:[{key:0,name:'开屏广告',rules:[{matches:'[text="跳过"]'}]}]}
                ]}
                """));
        assertEquals(Set.of("opening", "fullscreen"), profile.getAppCategories().get(APP.getId()));
        assertEquals(Set.of("opening"), profile.getAppCategories().get("paused.app"));
        assertTrue(profile.supportsOpening(APP));
        assertFalse(profile.supportsOpening(new AppInfo(APP.getId(), 4, "4", false, false)));
        assertFalse(profile.supportsOpening(new AppInfo("paused.app", 3, "3", false, false)));
    }

    @Test public void unsupportedActionsAndConditionsCannotBecomeGenericClicks() {
        var profile = StartupProfile.readSource("""
                {"sources":[{"id":101,"order":0}],"groups":[],"categories":[]}
                """, List.of("""
                {id:101,name:'Active',version:1,apps:[{id:'sample.app',groups:[
                    {key:0,name:'开屏广告',rules:[{matches:'[text="跳过"]',action:'swipe'}]},
                    {key:1,name:'开屏广告',rules:[{matches:'[text="跳过"]',excludeAllMatches:['[text="支付"]']}]},
                    {key:2,name:'开屏广告',rules:[{matches:'[text="跳过"]',action:'clickNode'}]}
                ]}]}
                """));
        assertEquals(1, profile.compile(APP, new RuleSession(() -> 4000L)).size());
        assertEquals(2, profile.compile(APP, new RuleSession(() -> 4000L)).get(0).getG().getGroup().getKey());
    }

    @Test public void appliesAppGroupCategoryAndGlobalPageOverrides() throws IOException {
        String configs = """
                [
                  {"subsItemId":101,"type":2,"appId":"sample.app","groupKey":4,"enable":true},
                  {"subsItemId":101,"type":3,"groupKey":0,"exclude":"sample.app/sample.app.Files"}
                ]
                """;
        String categories = """
                [{"subsItemId":101,"categoryKey":7,"enable":false}]
                """;
        var profile = read(configs, categories, List.of(SOURCE));
        var session = new RuleSession(() -> 4000L);
        assertEquals(3, profile.compile(APP, session).size());
        assertFalse(profile.compile(APP, session).get(0).matchActivity(APP.getId(), "sample.app.Files"));
        assertEquals(1, read("[]", categories, List.of(SOURCE)).compile(APP, session).size());
        var disabled = read("""
                [{"subsItemId":101,"type":1,"appId":"sample.app","enable":false}]
                """, "[]", List.of(SOURCE));
        assertEquals(1, disabled.compile(APP, session).size());
    }

    @Test public void rejectsMissingOrDuplicateSubscriptionDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> read("[]", "[]", List.of()));
        assertThrows(IllegalArgumentException.class, () -> read("[]", "[]", List.of(SOURCE, SOURCE)));
    }

    @Test public void endsStartupWindowAndClearsWorkOnPause() throws IOException {
        var clock = new AtomicLong(4000);
        var session = new RuleSession(clock::get);
        session.foreground(read("[]", "[]", List.of(SOURCE)), APP, "sample.app.Opening", clock.get());
        assertTrue(session.opening());
        assertEquals(300, session.nextWake());
        clock.addAndGet(15_001);
        assertFalse(session.opening());
        session.clear();
        assertEquals(-1, session.nextWake());
    }

    private static StartupProfile read(String configs, String categories, List<String> sources) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("transfer_data.json"));
            String backup = """
                    {"type":"transfer_data","subsItems":[{"id":101,"order":0,"enable":true}],
                     "subsConfigs":%s,"categoryConfigs":%s}
                    """.formatted(configs, categories);
            zip.write(backup.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return StartupProfile.read(new ByteArrayInputStream(buffer.toByteArray()), sources);
    }
}
