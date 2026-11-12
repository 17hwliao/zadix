package com.ahuamiao.adprotection;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import li.songe.zadixsource.data.AppInfo;
import li.songe.zadixsource.data.StartupProfile;

final class RuleCatalog {
    static final String OPENING = "opening";
    private static final Set<String> CATEGORIES = Set.of(OPENING, "fullscreen", "partial", "inline", "mixed", "other");
    private static final int PAGE_SIZE = 60;
    private record Row(String pkg, String label, boolean available, boolean enabled) {}
    private final Context context;
    private final PackageManager packages;
    private final LiveRuntime runtime;
    private final LiveRules builtIn;

    RuleCatalog(Context context, LiveRuntime runtime) {
        this.context = context;
        this.runtime = runtime;
        packages = context.getPackageManager();
        try (var input = context.getAssets().open("live-rules.json")) {
            builtIn = LiveRules.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("无法读取内置规则", error);
        }
    }

    Bundle page(String category, int offset) {
        if (category == null || !CATEGORIES.contains(category) || offset < 0) {
            throw new IllegalArgumentException("未知规则分类");
        }
        var profile = profile();
        Map<String, Set<String>> appCategories = profile == null ? Map.of() : profile.getAppCategories();
        boolean opening = OPENING.equals(category);
        String home = opening ? homePackage() : "";
        var matches = new ArrayList<Row>();
        for (PackageInfo info : visiblePackages(appCategories, category)) {
            if (info.packageName.equals(context.getPackageName())) continue;
            ApplicationInfo application = applicationInfo(info);
            if (application == null) continue;
            boolean listed = appCategories.getOrDefault(info.packageName, Set.of()).contains(category);
            boolean available = false;
            if (opening) {
                boolean builtInApp = builtIn.packages().contains(info.packageName)
                        || QqMiniAppSplash.PACKAGE.equals(info.packageName);
                if (!listed && !builtInApp && profile == null) continue;
                available = supports(info, application, profile, home);
                if (!listed && !builtInApp && !available) continue;
            } else if (!listed) {
                continue;
            }
            String label = application.loadLabel(packages).toString().strip();
            if (label.isEmpty()) label = info.packageName;
            if (label.length() > 120) label = label.substring(0, 120);
            matches.add(new Row(info.packageName, label, available, available && runtime.allowsOpening(info.packageName)));
        }
        var collator = Collator.getInstance();
        matches.sort(Comparator.comparing(Row::label, collator).thenComparing(Row::pkg));
        var rows = new ArrayList<Bundle>();
        int end = Math.min(matches.size(), Math.min(matches.size(), offset) + PAGE_SIZE);
        for (Row row : matches.subList(Math.min(offset, matches.size()), end)) {
            var item = new Bundle();
            item.putString("packageName", row.pkg());
            item.putString("label", row.label());
            item.putBoolean("available", row.available());
            item.putBoolean("enabled", row.enabled());
            rows.add(item);
        }
        var result = new Bundle();
        result.putParcelableArrayList("apps", rows);
        result.putInt("next", end < matches.size() ? end : -1);
        return result;
    }

    private Iterable<PackageInfo> visiblePackages(Map<String, Set<String>> appCategories, String category) {
        var found = new LinkedHashMap<String, PackageInfo>();
        for (PackageInfo info : packages.getInstalledPackages(0)) found.put(info.packageName, info);
        var candidates = new LinkedHashSet<String>();
        var launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (var activity : packages.queryIntentActivities(launch, 0)) {
            if (activity.activityInfo != null) candidates.add(activity.activityInfo.packageName);
        }
        appCategories.forEach((pkg, categories) -> {
            if (categories.contains(category)) candidates.add(pkg);
        });
        if (OPENING.equals(category)) {
            candidates.addAll(builtIn.packages());
            candidates.add(QqMiniAppSplash.PACKAGE);
        }
        for (String pkg : candidates) {
            if (found.containsKey(pkg) || pkg.length() > 255) continue;
            try {
                found.put(pkg, packages.getPackageInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException notVisibleOrNotInstalled) {
                // Keep only packages Android confirms are installed for this user.
            }
        }
        return found.values();
    }

    private ApplicationInfo applicationInfo(PackageInfo info) {
        if (info.applicationInfo != null) return info.applicationInfo;
        try {
            return packages.getApplicationInfo(info.packageName, 0);
        } catch (PackageManager.NameNotFoundException notVisibleOrNotInstalled) {
            return null;
        }
    }

    boolean supportsOpening(String pkg) {
        if (pkg == null || pkg.length() > 255 || pkg.equals(context.getPackageName())) return false;
        try {
            var info = packages.getPackageInfo(pkg, 0);
            return supports(info, applicationInfo(info), profile(), homePackage());
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private StartupProfile profile() {
        StartupSourceStore.load(context);
        var current = StartupSourceStore.current();
        return current == null ? null : current.profile();
    }

    private String homePackage() {
        var home = packages.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return home == null || home.activityInfo == null ? "" : home.activityInfo.packageName;
    }

    private boolean supports(PackageInfo info, ApplicationInfo application, StartupProfile profile, String home) {
        if (application == null || !application.enabled) return false;
        String pkg = info.packageName;
        long version = info.getLongVersionCode();
        if (QqMiniAppSplash.supports(pkg, version)) return true;
        if (!(profile != null && NetdiskSplash.PACKAGE.equals(pkg)) && builtIn.supports(pkg, version)) return true;
        if (profile == null || !LiveAccessibility.sourceAllowed(context.getPackageName(), pkg)) return false;
        boolean system = (application.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        return profile.supportsOpening(new AppInfo(pkg, version, info.versionName, system, pkg.equals(home)));
    }
}
