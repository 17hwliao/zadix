package com.ahuamiao.adprotection;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Android adapter in the protection process, shared by the tile and accessibility service. */
final class LiveRuntime {
    private static final String DESIRED = "desired";
    private static final String PAUSED_OPENING_APPS = "paused-opening-apps";
    private static LiveRuntime instance;
    final LiveController gate;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private volatile Set<String> pausedOpeningApps;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private final ArrayDeque<String> results = new ArrayDeque<>();
    private LiveAccessibility service;
    private LiveController.Request scheduled;

    static synchronized LiveRuntime get(Context context) {
        if (instance == null) instance = new LiveRuntime(context.getApplicationContext());
        return instance;
    }

    private LiveRuntime(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("live-skip", Context.MODE_PRIVATE);
        pausedOpeningApps = Set.copyOf(preferences.getStringSet(PAUSED_OPENING_APPS, Set.of()));
        // The system's first grant is enough to start. A saved pause takes precedence.
        gate = new LiveController(preferences.getBoolean(DESIRED, true));
        readPermission();
        gate.uncertainCleanup(Protection.cleanupMayStillRun(context));
        var resolver = context.getContentResolver();
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        refreshPermission();
                    }
                });
    }

    void refreshPermission() {
        readPermission();
        dispatch();
    }

    private void readPermission() {
        ComponentName own = new ComponentName(context, LiveAccessibility.class);
        // The saved user grant and the currently bound service are separate signals.
        String settings = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean enabled = settings != null && java.util.Arrays.stream(settings.split(":"))
                .map(ComponentName::unflattenFromString).anyMatch(own::equals);
        gate.permission(enabled);
    }

    long connect(LiveAccessibility connected, boolean ready) {
        readPermission();
        service = connected;
        long connection = gate.connect(ready);
        main.post(this::dispatch);
        return connection;
    }

    void disconnect(LiveAccessibility old, long connection) {
        if (service != old) return;
        service = null;
        gate.disconnect(connection);
        dispatch();
    }

    void choose(boolean desired) {
        readPermission();
        saveChoice(desired);
    }

    private void saveChoice(boolean desired) {
        if (preferences.edit().putBoolean(DESIRED, desired).commit()) gate.choose(desired);
        else gate.storageFailed();
        dispatch();
    }

    /** Returns whether the caller should open system authorization after recording the user's intent. */
    boolean toggle() {
        readPermission();
        var state = gate.status();
        boolean needsGrant = state.phase() == LiveController.Phase.PERMISSION;
        saveChoice(needsGrant || !state.desired());
        return needsGrant;
    }

    static Intent permissionSettings(Context context) {
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(context, LiveAccessibility.class).flattenToString());
        var resolved = context.getPackageManager().resolveActivity(details,
                PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null && resolved.activityInfo.exported) {
            String permission = resolved.activityInfo.permission;
            if (permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return details.setComponent(new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name));
            }
        }
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    boolean beginCleanup() {
        if (!gate.beginCleanup()) return false;
        main.post(this::dispatch);
        return true;
    }

    void finishCleanup(boolean mayStillRun) {
        gate.finishCleanup(mayStillRun);
        main.post(this::dispatch);
    }

    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }

    boolean allowsOpening(String packageName) { return !pausedOpeningApps.contains(packageName); }

    boolean chooseOpening(String packageName, boolean enabled) {
        if (allowsOpening(packageName) == enabled) return true;
        var updated = new LinkedHashSet<>(pausedOpeningApps);
        if (enabled) updated.remove(packageName);
        else updated.add(packageName);
        if (!preferences.edit().putStringSet(PAUSED_OPENING_APPS, updated).commit()) return false;
        pausedOpeningApps = Set.copyOf(updated);
        if (service != null) service.openingChoiceChanged(packageName);
        return true;
    }

    void record(String packageName, String rule, String result) {
        if (results.size() == 12) results.removeLast();
        results.addFirst(Instant.now() + " · " + packageName + " · " + rule + " · " + result);
        refreshViews();
    }

    String recentResult() { return results.isEmpty() ? "" : results.getFirst(); }

    private void dispatch() {
        LiveController.Request request = gate.pending();
        if (request != null && !request.equals(scheduled)) {
            scheduled = request;
            main.postDelayed(() -> {
                if (!request.equals(gate.pending())) return;
                gate.timeout(request);
                if (service != null) service.suspendActions();
                refreshViews();
            }, 2_000);
            main.post(() -> {
                if (!request.equals(gate.pending()) || service == null) return;
                if (service.apply(request)) gate.acknowledge(request);
                refreshViews();
            });
        } else if (request == null && gate.status().phase() != LiveController.Phase.RUNNING && service != null) {
            service.suspendActions();
        }
        refreshViews();
    }

    private void refreshViews() { List.copyOf(listeners).forEach(Runnable::run); }

    static String label(LiveController.Status status) {
        return switch (status.phase()) {
            case PERMISSION -> "待授权";
            case PAUSED -> "已暂停";
            case STARTING -> "启动中";
            case PAUSING -> "暂停中";
            case RUNNING -> "运行中";
            case CLEANING -> "清理中";
            case RECOVERY -> status.failure() == LiveController.Failure.DISCONNECTED ? "等待系统连接" : "需恢复";
        };
    }

    static String detail(LiveController.Status status) {
        return switch (status.failure()) {
            case DISCONNECTED -> "已保留开启选择，系统重新连接后会自动恢复。";
            case RULES -> "没有可用的实时规则。";
            case TIMEOUT -> "服务未确认本次切换，动作入口已关闭。";
            case STORAGE -> "无法保存本次选择，动作入口已关闭。";
            case NONE -> status.phase() == LiveController.Phase.CLEANING
                    ? "系统清理正在执行或上一条命令尚未确认结束。"
                    : "";
        };
    }
}
