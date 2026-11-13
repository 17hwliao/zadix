package com.ahuamiao.adprotection;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.ahuamiao.adprotection.LiveTiming.Outcome;
import com.ahuamiao.adprotection.LiveTiming.ReadPhase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import li.songe.zadixsource.data.AppInfo;
import li.songe.zadixsource.data.RuleSession;

public final class LiveAccessibility extends AccessibilityService {
    private static final int EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
    private static final int MAX_NODES = 512;
    private static final String FIXTURE = "com.ahuamiao.adprotection.fixture";
    private static final Set<String> SOURCE_EXCLUDED_PACKAGES = Set.of("android", "com.android.settings",
            "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "li.songe.gkd");
    private record Window(String packageName, String activity, int id) {}
    private record Visit(AccessibilityNodeInfo source, int parent) {}
    private record Tree(List<LiveRules.Node> nodes, List<AccessibilityNodeInfo> sources,
                        NetdiskSplash.Result brandAd, LiveRules.Match qqAd) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::scanWindow;
    private final Runnable scanSource = this::scanSourceWindow;
    private final Runnable confirmClose = this::confirmClose;
    private final SkipBanner skipBanner = new SkipBanner(this, main);
    private record CloseCheck(Window window, String rule, LiveController.Work work,
                              StartupSourceStore.Snapshot profile, ClosingEvidence evidence) {}
    private CloseCheck closeCheck;
    private Window confirmedCloseWindow;
    private final RuleSession sourceSession = new RuleSession(SystemClock::uptimeMillis);
    private StartupSourceStore.Snapshot sourceProfile;
    private String sourceForeground;
    private long sourceEnteredAt = -1;
    private long sourceScanAt = -1;
    private Window sourceWindow;
    private LiveRuntime runtime;
    private LiveRules rules;
    private long connection;
    private long bindingGeneration;
    private Window current;
    private long currentReportedAt = -1;
    private LiveController.Work scheduledWork;
    private LiveTiming timing;
    private LiveTiming.Pass scheduledTiming;
    private boolean scheduledQqWake;
    private int netdiskWindow = -1;
    private long netdiskLaunch = -1;
    private long qqOpenedAt = -1;
    private long qqAdSeenAt = -1;

    @Override protected void onServiceConnected() {
        runtime = LiveRuntime.get(this);
        if (connection != 0) disconnect();
        long binding = ++bindingGeneration;
        timing = BuildConfig.LOCAL_TIMING && new File(getFilesDir(), LiveTiming.ENABLE_FILE).isFile()
                ? new LiveTiming(SystemClock::uptimeMillis,
                        BuildConfig.LOCAL_TREE_TIMING ? SystemClock::elapsedRealtimeNanos : null,
                        line -> Log.i(LiveTiming.TAG, line)) : null;
        try (var input = getAssets().open("live-rules.json")) {
            rules = LiveRules.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception error) {
            rules = null;
        }
        suspendActions();
        new Thread(() -> {
            StartupSourceStore.load(getApplicationContext());
            main.post(() -> {
                if (binding != bindingGeneration) return;
                adoptSourceProfile();
                connection = runtime.connect(this, rules != null && !rules.packages().isEmpty());
            });
        }, "load-source-startup").start();
    }

    boolean apply(LiveController.Request request) {
        if (connection != request.connection()) return false;
        if (!request.start()) return suspendActions();
        if (rules == null) return false;
        boolean enabled = changeEvents(EVENTS);
        if (enabled) seedSourceForeground();
        return enabled;
    }

    boolean suspendActions() {
        current = null;
        currentReportedAt = -1;
        clearNetdiskOpening();
        clearQqOpening();
        cancelScan(Outcome.GATE_REJECTED);
        resetSource();
        cancelCloseCheck();
        confirmedCloseWindow = null;
        skipBanner.dismiss();
        return changeEvents(0);
    }

    private boolean changeEvents(int events) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return false;
        if (info.eventTypes != events) {
            info.eventTypes = events;
            setServiceInfo(info);
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        long arrival = timing == null ? -1 : SystemClock.uptimeMillis();
        if (runtime == null || runtime.gate.work(connection) == null || rules == null) return;
        handleLegacyEvent(event, arrival);
        handleSourceEvent(event);
    }

    private void handleLegacyEvent(AccessibilityEvent event, long arrival) {
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String activity = event.getClassName() == null ? "" : event.getClassName().toString();
            int windowId = event.getWindowId();
            Window previous = current;
            // A cold launch can deliver the placeholder window after the real activity.
            if (previous != null && LiveController.staleStartingWindow(previous.packageName(), previous.id(),
                    currentReportedAt, pkg, activity, windowId, event.getEventTime())) return;
            if (closeCheck != null && windowId >= 0 && windowId != closeCheck.window().id()
                    && isActivity(pkg, activity)) cancelCloseCheck();
            runtime.gate.windowChanged(connection);
            cancelScan(Outcome.WINDOW_CHANGED);
            if (windowId >= 0 && windowId != netdiskWindow && NetdiskSplash.ownsWindow(pkg, activity)) {
                netdiskWindow = windowId;
                netdiskLaunch = event.getEventTime();
            }
            if (windowId >= 0 && QqMiniAppSplash.ownsWindow(pkg, activity)) {
                Window opening = new Window(pkg, activity, windowId);
                if (!opening.equals(current)) {
                    qqOpenedAt = event.getEventTime();
                    qqAdSeenAt = -1;
                }
                current = opening;
            } else if (!(current != null && QqMiniAppSplash.ownsWindow(current.packageName(), current.activity())
                    && current.id() == windowId && pkg.equals(current.packageName())
                    && activity.startsWith("android.widget."))) {
                clearQqOpening();
                current = rules.packages().contains(pkg) && windowId >= 0 && activity.startsWith(pkg + ".")
                        ? new Window(pkg, activity, windowId) : null;
            }
            if (current == null) currentReportedAt = -1;
            else if (current != previous) {
                currentReportedAt = current.equals(previous) ? Math.max(currentReportedAt, event.getEventTime())
                        : event.getEventTime();
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Focus/active changes invalidate work queued for the previous window.
            int changes = event.getWindowChanges();
            if ((changes & (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
                    | AccessibilityEvent.WINDOWS_CHANGE_REMOVED)) != 0) {
                runtime.gate.windowChanged(connection);
                cancelScan(Outcome.WINDOW_CHANGED);
            }
            if ((changes & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0 && event.getWindowId() >= 0) {
                if (netdiskWindow == event.getWindowId()) clearNetdiskOpening();
                if (closeCheck != null && closeCheck.window().id() == event.getWindowId()) cancelCloseCheck();
                if (confirmedCloseWindow != null && confirmedCloseWindow.id() == event.getWindowId()) {
                    confirmedCloseWindow = null;
                }
                forgetWindow(event.getWindowId());
            }
        } else if (current == null || !current.packageName().equals(pkg) || current.id() != event.getWindowId()) {
            return;
        }
        if (current != null && scheduledWork == null && runtime.allowsOpening(current.packageName())) {
            scheduledWork = runtime.gate.work(connection);
            if (scheduledWork != null) {
                if (timing != null) {
                    LiveTiming.Target target = timingTarget(current);
                    if (target != null) scheduledTiming = timing.schedule(target, connection,
                            current.id(), event.getEventTime(), arrival, 65);
                }
                main.postDelayed(scan, 65);
            }
        }
    }

    private void handleSourceEvent(AccessibilityEvent event) {
        adoptSourceProfile();
        var profile = sourceProfile;
        if (profile == null || profile.profile() == null) return;
        int type = event.getEventType();
        int windowId = event.getWindowId();
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && windowId >= 0 && !pkg.isEmpty()) {
            String activity = event.getClassName() == null ? "" : event.getClassName().toString();
            if (!isActivity(pkg, activity)) return;
            if (!pkg.equals(sourceForeground)) {
                boolean observedPrevious = sourceForeground != null;
                resetSource();
                sourceForeground = pkg;
                sourceEnteredAt = observedPrevious ? event.getEventTime() : -1;
            }
            var window = new Window(pkg, activity, windowId);
            if (!window.equals(sourceWindow)) {
                sourceWindow = null;
                cancelSourceScan();
                if (sourceEnteredAt < 0 || !sourceAllowed(getPackageName(), pkg) || !runtime.allowsOpening(pkg)
                        || QqMiniAppSplash.ownsWindow(pkg, activity)) return;
                AppInfo app = sourceAppInfo(pkg);
                if (app == null) return;
                sourceSession.foreground(profile.profile(), app, activity, sourceEnteredAt);
                sourceWindow = window;
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (sourceWindow != null && sourceWindow.id() == windowId &&
                    (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
                sourceWindow = null;
                cancelSourceScan();
                return;
            }
        } else if (sourceWindow == null || !pkg.equals(sourceWindow.packageName()) || windowId != sourceWindow.id()) {
            return;
        }
        if (sourceWindow != null && sourceSession.opening()) scheduleSourceScan(0);
    }

    private boolean isActivity(String pkg, String name) {
        if (name.isEmpty() || name.startsWith("android.widget.")) return false;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, name), 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private AppInfo sourceAppInfo(String pkg) {
        try {
            var pm = getPackageManager();
            var info = pm.getPackageInfo(pkg, 0);
            var application = info.applicationInfo == null ? pm.getApplicationInfo(pkg, 0) : info.applicationInfo;
            boolean system = (application.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            var home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return new AppInfo(pkg, info.getLongVersionCode(), info.versionName, system,
                    home != null && home.activityInfo != null && pkg.equals(home.activityInfo.packageName));
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private void scanSourceWindow() {
        sourceScanAt = -1;
        Window window = sourceWindow;
        var work = runtime.gate.work(connection);
        if (window == null || work == null || !runtime.allowsOpening(window.packageName())
                || sourceProfile != StartupSourceStore.current() || locked()) return;
        try {
            RuleSession.Match match = runtime.gate.query(work, () -> {
                var root = sourceRoot(window);
                return root == null ? null : sourceSession.query(root);
            });
            if (match != null) {
                runtime.gate.submit(work, () -> {
                    if (!runtime.allowsOpening(window.packageName()) || sourceProfile != StartupSourceStore.current()
                            || !window.equals(sourceWindow) || locked()) return false;
                    var root = sourceRoot(window);
                    if (root == null) return false;
                    boolean observedNetdiskAd = isNetdiskConfirmationWindow(window)
                            && visibleNetdiskAd(window, root);
                    boolean accepted = sourceSession.submit(match, this, root);
                    if (accepted) {
                        runtime.record(window.packageName(), match.id(), "已提交 · 结果未确认");
                        if (observedNetdiskAd) beginCloseCheck(window, match.id(), work);
                    }
                    return accepted;
                });
            }
        } catch (RuntimeException error) {
            Log.w("StartupSource", "开屏规则执行中断", error);
            sourceWindow = null;
            return;
        }
        long wake = sourceSession.nextWake();
        if (wake >= 0) scheduleSourceScan(wake);
    }

    private AccessibilityNodeInfo sourceRoot(Window window) {
        var root = getRootInActiveWindow();
        if (root == null || root.getWindowId() != window.id() || !samePackage(window, root)) return null;
        var info = root.getWindow();
        return info != null && info.isActive() && info.isFocused()
                && info.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && info.getDisplayId() == Display.DEFAULT_DISPLAY ? root : null;
    }

    private void resetSource() {
        cancelSourceScan();
        sourceWindow = null;
        sourceForeground = null;
        sourceEnteredAt = -1;
        sourceSession.clear();
    }

    private void adoptSourceProfile() {
        var profile = StartupSourceStore.current();
        if (profile == sourceProfile) return;
        resetSource();
        sourceProfile = profile;
        if (connection != 0 && runtime.gate.work(connection) != null) seedSourceForeground();
    }

    private void seedSourceForeground() {
        var profile = StartupSourceStore.current();
        if (profile == null || profile.profile() == null || sourceForeground != null || locked()) return;
        try {
            var root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) sourceForeground = root.getPackageName().toString();
        } catch (RuntimeException ignored) {
            // The first window event establishes the baseline if Android cannot return a root.
        }
    }

    private void cancelSourceScan() {
        main.removeCallbacks(scanSource);
        sourceScanAt = -1;
    }

    private void scheduleSourceScan(long delay) {
        long deadline = SystemClock.uptimeMillis() + delay;
        if (sourceScanAt >= 0 && sourceScanAt <= deadline) return;
        cancelSourceScan();
        if (main.postAtTime(scanSource, deadline)) sourceScanAt = deadline;
    }

    private boolean sourceOwns(Window window) {
        var imported = StartupSourceStore.current();
        return imported != null && imported.profile() != null && NetdiskSplash.PACKAGE.equals(window.packageName());
    }

    static boolean sourceAllowed(String ownPackage, String pkg) {
        return !ownPackage.equals(pkg) && !FIXTURE.equals(pkg) && !SOURCE_EXCLUDED_PACKAGES.contains(pkg);
    }

    void openingChoiceChanged(String pkg) {
        if (closeCheck != null && pkg.equals(closeCheck.window().packageName())) cancelCloseCheck();
        skipBanner.dismiss();
        if (current != null && pkg.equals(current.packageName())) {
            runtime.gate.windowChanged(connection);
            cancelScan(Outcome.GATE_REJECTED);
            clearNetdiskOpening();
            clearQqOpening();
        }
        if (pkg.equals(sourceForeground)) {
            resetSource();
            sourceForeground = pkg;
        }
    }

    private static LiveTiming.Target timingTarget(Window window) {
        if (QqMiniAppSplash.ownsWindow(window.packageName(), window.activity())) return LiveTiming.Target.QQ;
        if (NetdiskSplash.ownsWindow(window.packageName(), window.activity())) return LiveTiming.Target.NETDISK;
        return FIXTURE.equals(window.packageName()) ? LiveTiming.Target.FIXTURE : null;
    }

    private void cancelScan(Outcome reason) {
        LiveTiming.Pass canceled = scheduledTiming;
        scheduledWork = null;
        scheduledTiming = null;
        scheduledQqWake = false;
        main.removeCallbacks(scan);
        if (canceled != null) {
            canceled.outcome(reason);
            canceled.finish();
        }
    }

    private void clearQqOpening() {
        qqOpenedAt = -1;
        qqAdSeenAt = -1;
    }

    private void clearNetdiskOpening() {
        netdiskWindow = -1;
        netdiskLaunch = -1;
    }

    private void scanWindow() {
        LiveTiming.Pass pass = scheduledTiming;
        scheduledTiming = null;
        mark(pass, LiveTiming.Step.SCAN);
        try {
            scanWindow(pass);
        } finally {
            if (pass != null) pass.finish();
        }
    }

    private void scanWindow(LiveTiming.Pass pass) {
        LiveController.Work work = scheduledWork;
        scheduledWork = null;
        boolean qqWake = scheduledQqWake;
        boolean batchQqRead = BuildConfig.QQ_BATCH_READ && (qqWake || BuildConfig.QQ_INITIAL_BATCH_READ);
        scheduledQqWake = false;
        Window window = current;
        if (window == null) {
            outcome(pass, Outcome.WINDOW_CHANGED);
            return;
        }
        if (sourceOwns(window)) {
            outcome(pass, Outcome.OUTSIDE_OPENING);
            return;
        }
        if (work == null || locked() || !runtime.allowsOpening(window.packageName())) {
            outcome(pass, Outcome.GATE_REJECTED);
            return;
        }
        long version;
        try {
            version = getPackageManager().getPackageInfo(window.packageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            outcome(pass, Outcome.WINDOW_CHANGED);
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean qq = QqMiniAppSplash.inOpening(window.packageName(), window.activity(), version, qqOpenedAt, now);
        if ((!qq && !rules.supports(window.packageName(), version))
                || (NetdiskSplash.PACKAGE.equals(window.packageName()) && !netdiskOpening(window, version))) {
            outcome(pass, Outcome.OUTSIDE_OPENING);
            return;
        }
        if (BuildConfig.QQ_DIRECT_WAKE_RECHECK && qqWake && qq
                && QqMiniAppSplash.readyForScheduledCheck(qqAdSeenAt, now)) {
            // A prior match scheduled this wake. The submission gate still requires a complete new match.
            submitVerified(work, window, version, QqMiniAppSplash.RULE, pass, true);
            return;
        }
        outcome(pass, Outcome.GATE_REJECTED);
        Tree tree = runtime.gate.query(work, () -> {
            outcome(pass, null);
            mark(pass, LiveTiming.Step.QUERY_START);
            Tree queried = readWindow(window, version, batchQqRead && qq, pass, ReadPhase.QUERY);
            mark(pass, LiveTiming.Step.QUERY_END);
            return queried;
        });
        if (tree == null) return;
        if (!hasAdMarker(window, version, tree)) {
            // Startup ads may briefly hide their badge while animating. Only a removed
            // window releases a Netdisk claim, so a failed or delayed click cannot repeat.
            if (qq) qqAdSeenAt = -1;
            else if (!NetdiskSplash.PACKAGE.equals(window.packageName())) forgetInstance(instance(window));
            outcome(pass, Outcome.NO_AD_MATCH);
            return;
        }
        LiveRules.Match match = match(window, version, tree);
        if (match == null) {
            outcome(pass, Outcome.NO_AD_MATCH);
            return;
        }
        mark(pass, LiveTiming.Step.MATCH);
        if (QqMiniAppSplash.RULE.equals(match.ruleId())) {
            now = SystemClock.uptimeMillis();
            if (qqAdSeenAt < 0) qqAdSeenAt = now;
            long readyDelay = BuildConfig.QQ_SHORT_WAIT
                    ? QqMiniAppSplash.EARLY_TEST_DELAY_MS : QqMiniAppSplash.READY_DELAY_MS;
            long wait = readyDelay - (now - qqAdSeenAt);
            if (wait > 0) {
                scheduledWork = work;
                scheduledQqWake = true;
                if (pass != null) scheduledTiming = pass.waiting(wait);
                main.postDelayed(scan, wait);
                return;
            }
        }
        submitVerified(work, window, version, match.ruleId(), pass, false);
    }

    private void submitVerified(LiveController.Work work, Window window, long version, String expectedRule,
                                LiveTiming.Pass pass, boolean directWake) {
        outcome(pass, Outcome.GATE_REJECTED);
        runtime.gate.submit(work, () -> {
            if (locked() || !runtime.allowsOpening(window.packageName()) || sourceOwns(window)) return false;
            if (!window.equals(current)) {
                outcome(pass, Outcome.WINDOW_CHANGED);
                return false;
            }
            // Re-resolve the current window and all constraints at submission, including deny markers.
            mark(pass, LiveTiming.Step.RECHECK_START);
            Tree fresh = readWindow(window, version, false, pass, ReadPhase.RECHECK);
            mark(pass, LiveTiming.Step.RECHECK_END);
            if (fresh == null) return false;
            LiveRules.Match verified = match(window, version, fresh);
            if (verified == null || !expectedRule.equals(verified.ruleId())) {
                if (directWake && !hasAdMarker(window, version, fresh)) qqAdSeenAt = -1;
                outcome(pass, Outcome.NO_AD_MATCH);
                return false;
            }
            mark(pass, LiveTiming.Step.RECHECK_MATCH);
            AccessibilityNodeInfo target = fresh.sources().get(verified.targetIndex());
            boolean qqAd = QqMiniAppSplash.RULE.equals(verified.ruleId());
            NetdiskSplash.Box skipBounds = qqAd ? box(target) : null;
            if (qqAd) {
                mark(pass, LiveTiming.Step.TARGET_REFRESH_START);
                boolean refreshed = target.refresh();
                mark(pass, LiveTiming.Step.TARGET_REFRESH_END);
                if (!refreshed) {
                    outcome(pass, Outcome.TARGET_UNAVAILABLE);
                    return false;
                }
                if (!target.isVisibleToUser() || !target.isEnabled() || target.getWindowId() != window.id()
                        || !samePackage(window, target)
                        || QqMiniAppSplash.label(target.getText(), target.getContentDescription()) != QqMiniAppSplash.Label.SKIP
                        || !skipBounds.equals(box(target))) {
                    outcome(pass, Outcome.TARGET_CHANGED);
                    return false;
                }
            }
            mark(pass, LiveTiming.Step.CLAIM_START);
            boolean claimed = claimInstance(instance(window), pass);
            mark(pass, LiveTiming.Step.CLAIM_END);
            if (!claimed) return false;
            boolean gesture = qqAd && !clickable(target);
            if (pass != null) pass.submitting(gesture ? LiveTiming.Action.GESTURE : LiveTiming.Action.NODE);
            boolean accepted = gesture ? tapQqSkip(skipBounds)
                    : target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (pass != null) pass.receipt(accepted);
            runtime.record(window.packageName(), verified.ruleId(), "规则命中");
            if (accepted && (FIXTURE.equals(window.packageName()) || isNetdiskConfirmationWindow(window))) {
                beginCloseCheck(window, verified.ruleId(), work);
            }
            runtime.record(window.packageName(), verified.ruleId(), accepted ? "已提交 · 结果未确认" : "点击未获接受");
            return accepted;
        });
    }

    private boolean isNetdiskConfirmationWindow(Window window) {
        if (!NetdiskSplash.ownsWindow(window.packageName(), window.activity())) return false;
        var info = sourceAppInfo(window.packageName());
        return info != null && info.getVersionCode() == NetdiskSplash.SUPPORTED_VERSION;
    }

    private void beginCloseCheck(Window window, String rule, LiveController.Work work) {
        if (window.equals(confirmedCloseWindow) || (closeCheck != null && window.equals(closeCheck.window()))) return;
        cancelCloseCheck();
        long observationMs = FIXTURE.equals(window.packageName())
                ? ClosingEvidence.FIXTURE_OBSERVATION_MS : ClosingEvidence.OBSERVATION_MS;
        closeCheck = new CloseCheck(window, rule, work, StartupSourceStore.current(),
                new ClosingEvidence(SystemClock.uptimeMillis(), observationMs));
        main.postDelayed(confirmClose, ClosingEvidence.INTERVAL_MS);
    }

    private void confirmClose() {
        var check = closeCheck;
        if (check == null) return;
        long now = SystemClock.uptimeMillis();
        var window = check.window();
        if (check.evidence().expired(now) || check.profile() != StartupSourceStore.current()
                || !runtime.allowsOpening(window.packageName()) || locked()) {
            cancelCloseCheck();
            return;
        }
        Boolean confirmed = runtime.gate.observeResult(check.work(), () -> {
            var root = sourceRoot(window);
            if (FIXTURE.equals(window.packageName())) {
                return root != null && visibleId(window, root, FIXTURE + ":id/result_auto");
            }
            var screen = root == null ? null : new ClosingEvidence.Screen(visibleNetdiskAd(window, root),
                    visibleText(window, root, "首页"), visibleText(window, root, "文件"));
            return check.evidence().observe(screen, SystemClock.uptimeMillis());
        });
        if (confirmed == null) {
            cancelCloseCheck();
        } else if (confirmed) {
            cancelCloseCheck();
            reportClosed(window, check.rule());
        } else {
            main.postDelayed(confirmClose, ClosingEvidence.INTERVAL_MS);
        }
    }

    private static boolean visibleNetdiskAd(Window window, AccessibilityNodeInfo root) {
        for (var node : root.findAccessibilityNodeInfosByViewId(NetdiskSplash.AD_CONTAINER)) {
            if (visibleIn(window, node) && node.getChildCount() > 0) return true;
        }
        for (var id : NetdiskSplash.AD_MARKER_IDS) {
            if (visibleId(window, root, id)) return true;
        }
        if (visibleText(window, root, "品牌广告")) return true;
        return root.findAccessibilityNodeInfosByText("跳过").stream()
                .anyMatch(node -> visibleIn(window, node)
                        && (NetdiskSplash.label(node.getText()) == NetdiskSplash.Label.SKIP
                        || NetdiskSplash.label(node.getContentDescription()) == NetdiskSplash.Label.SKIP));
    }

    private static boolean visibleId(Window window, AccessibilityNodeInfo root, String id) {
        return root.findAccessibilityNodeInfosByViewId(id).stream().anyMatch(node -> visibleIn(window, node));
    }

    private static boolean visibleText(Window window, AccessibilityNodeInfo root, String expected) {
        return root.findAccessibilityNodeInfosByText(expected).stream()
                .anyMatch(node -> visibleIn(window, node)
                        && (expected.contentEquals(node.getText() == null ? "" : node.getText())
                        || expected.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())));
    }

    private static boolean visibleIn(Window window, AccessibilityNodeInfo node) {
        return node.isVisibleToUser() && node.getWindowId() == window.id() && samePackage(window, node);
    }

    private void cancelCloseCheck() {
        closeCheck = null;
        main.removeCallbacks(confirmClose);
    }

    private void reportClosed(Window window, String rule) {
        if (window.equals(confirmedCloseWindow)) return;
        confirmedCloseWindow = window;
        runtime.record(window.packageName(), rule, "已确认广告关闭");
        skipBanner.show();
    }

    private static String instance(Window window) {
        return window.packageName() + ":" + window.activity() + ":" + window.id();
    }

    private boolean hasAdMarker(Window window, long version, Tree tree) {
        return rules.hasAdMarker(window.packageName(), version, window.activity(), tree.nodes())
                || (tree.brandAd() != null && tree.brandAd().adPresent()) || tree.qqAd() != null;
    }

    private static void mark(LiveTiming.Pass pass, LiveTiming.Step step) {
        if (pass != null) pass.mark(step);
    }

    private static void outcome(LiveTiming.Pass pass, Outcome reason) {
        if (pass != null) pass.outcome(reason);
    }

    private LiveRules.Match match(Window window, long version, Tree tree) {
        if (tree.qqAd() != null) return tree.qqAd();
        LiveRules.Match exact = rules.match(window.packageName(), version, window.activity(), tree.nodes());
        LiveRules.Match brand = tree.brandAd() == null ? null : tree.brandAd().match();
        if (exact == null && brand != null
                && rules.hasAdMarker(window.packageName(), version, window.activity(), tree.nodes())) return null;
        if (exact != null && brand != null && exact.targetIndex() != brand.targetIndex()) return null;
        return exact != null ? exact : brand;
    }

    private Tree readWindow(Window window, long version, boolean batchRead, LiveTiming.Pass pass, ReadPhase phase) {
        LiveTiming.TreeRead trace = pass == null ? null : pass.treeRead(phase);
        try {
            return readWindow(window, version, batchRead, pass, trace);
        } finally {
            if (trace != null) trace.finishWalk();
        }
    }

    private Tree readWindow(Window window, long version, boolean batchRead, LiveTiming.Pass pass,
                            LiveTiming.TreeRead trace) {
        if (locked()) {
            outcome(pass, Outcome.GATE_REJECTED);
            return null;
        }
        // Keep the hybrid traversal and request a batch for the selected QQ scans.
        int prefetch = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE;
        long start = readTime(trace);
        AccessibilityNodeInfo root = batchRead ? getRootInActiveWindow(prefetch) : getRootInActiveWindow();
        if (trace != null) trace.rootRead(start);
        if (root == null) {
            outcome(pass, Outcome.ROOT_UNAVAILABLE);
            return null;
        }
        if (root.getWindowId() != window.id() || !samePackage(window, root)) {
            outcome(pass, Outcome.WINDOW_CHANGED);
            return null;
        }
        boolean inspectBrand = NetdiskSplash.PACKAGE.equals(window.packageName());
        AccessibilityNodeInfo scanRoot = root;
        if (inspectBrand) {
            if (!netdiskOpening(window, version)) {
                outcome(pass, Outcome.OUTSIDE_OPENING);
                return null;
            }
            start = readTime(trace);
            scanRoot = netdiskAdContainer(window, root);
            if (trace != null) trace.containerRead(start);
            if (scanRoot == null) {
                outcome(pass, Outcome.NO_AD_MATCH);
                return null;
            }
        }
        boolean inspectQq = QqMiniAppSplash.inOpening(window.packageName(), window.activity(), version,
                qqOpenedAt, SystemClock.uptimeMillis());
        var queue = new ArrayDeque<Visit>();
        var nodes = new ArrayList<LiveRules.Node>();
        var sources = new ArrayList<AccessibilityNodeInfo>();
        var brandNodes = inspectBrand ? new ArrayList<NetdiskSplash.Node>() : null;
        var qqNodes = inspectQq ? new ArrayList<QqMiniAppSplash.Node>() : null;
        queue.add(new Visit(scanRoot, -1));
        if (trace != null) trace.startWalk();
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            AccessibilityNodeInfo source = visit.source();
            if (sources.size() == MAX_NODES) {
                outcome(pass, Outcome.TREE_BUDGET);
                return null;
            }
            if (source.getWindowId() != window.id() || !samePackage(window, source)) {
                outcome(pass, Outcome.WINDOW_CHANGED);
                return null;
            }
            if (trace != null) trace.visit();
            boolean clickable = clickable(source);
            String id = source.getViewIdResourceName();
            boolean visible = source.isVisibleToUser();
            int index = sources.size();
            nodes.add(new LiveRules.Node(id, visible, clickable));
            if (brandNodes != null) {
                var text = NetdiskSplash.label(source.getText());
                var description = NetdiskSplash.label(source.getContentDescription());
                var label = text == NetdiskSplash.Label.NONE ? description
                        : description == NetdiskSplash.Label.NONE || description == text ? text : NetdiskSplash.Label.NONE;
                brandNodes.add(new NetdiskSplash.Node(id, label, visible, clickable, visit.parent(), box(source, trace)));
            }
            if (qqNodes != null) {
                var label = QqMiniAppSplash.label(source.getText(), source.getContentDescription());
                String kind = source.getClassName() == null ? "" : source.getClassName().toString();
                qqNodes.add(new QqMiniAppSplash.Node(id, kind, label, visible, source.isEnabled(), visit.parent(),
                        box(source, trace)));
            }
            sources.add(source);
            int children = source.getChildCount();
            if (sources.size() + queue.size() + children > MAX_NODES) {
                outcome(pass, Outcome.TREE_BUDGET);
                return null;
            }
            for (int i = 0; i < children; i++) {
                start = readTime(trace);
                AccessibilityNodeInfo child = batchRead ? source.getChild(i, prefetch) : source.getChild(i);
                if (trace != null) trace.childRead(start);
                if (child == null) {
                    outcome(pass, Outcome.TREE_INCOMPLETE);
                    return null;
                }
                queue.addLast(new Visit(child, index));
            }
        }
        if (trace != null) trace.finishWalk();
        start = readTime(trace);
        NetdiskSplash.Result brand = brandNodes == null ? null : NetdiskSplash.match(brandNodes, box(root, trace));
        LiveRules.Match qqAd = qqNodes == null ? null : QqMiniAppSplash.match(qqNodes, box(root, trace));
        if (trace != null) trace.matched(start);
        return new Tree(nodes, sources, brand, qqAd);
    }

    private static long readTime(LiveTiming.TreeRead trace) { return trace == null ? 0 : trace.now(); }

    private static NetdiskSplash.Box box(AccessibilityNodeInfo node, LiveTiming.TreeRead trace) {
        if (trace != null) trace.boundsRead();
        return box(node);
    }

    private boolean netdiskOpening(Window window, long version) {
        return window.id() == netdiskWindow && NetdiskSplash.inOpening(window.packageName(),
                window.activity(), version, netdiskLaunch, SystemClock.uptimeMillis());
    }

    private AccessibilityNodeInfo netdiskAdContainer(Window window, AccessibilityNodeInfo root) {
        if (!root.findAccessibilityNodeInfosByViewId(NetdiskSplash.BLOCKED_VIEW).isEmpty()) return null;
        var viewport = box(root);
        // The normal page behind the ad changes independently and may exhaust the tree budget.
        AccessibilityNodeInfo container = null;
        for (var node : root.findAccessibilityNodeInfosByViewId(NetdiskSplash.AD_CONTAINER)) {
            if (!node.isVisibleToUser() || node.getWindowId() != window.id()
                    || !samePackage(window, node) || !viewport.equals(box(node))) continue;
            if (container != null && !container.equals(node)) return null;
            container = node;
        }
        return container;
    }

    private boolean tapQqSkip(NetdiskSplash.Box bounds) {
        if (locked()) return false;
        Path path = new Path();
        path.moveTo(bounds.left() + bounds.width() / 2f, bounds.top() + bounds.height() / 2f);
        long tapDuration = BuildConfig.QQ_SHORT_TAP ? 32 : 80;
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, tapDuration)).build();
        return dispatchGesture(gesture, null, null);
    }

    private static boolean clickable(AccessibilityNodeInfo node) {
        return node.isEnabled() && node.isClickable()
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    private static NetdiskSplash.Box box(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return new NetdiskSplash.Box(rect.left, rect.top, rect.right, rect.bottom);
    }

    private boolean locked() { return getSystemService(KeyguardManager.class).isKeyguardLocked(); }

    private static boolean samePackage(Window window, AccessibilityNodeInfo node) {
        return node.getPackageName() != null && window.packageName().contentEquals(node.getPackageName());
    }

    private boolean claimInstance(String instance, LiveTiming.Pass pass) {
        var prefs = getSharedPreferences("live-instances", MODE_PRIVATE);
        int boot = Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        var claims = prefs.getInt("boot", -1) == boot || boot < 0
                ? new HashSet<>(prefs.getStringSet("claims", java.util.Set.of())) : new HashSet<String>();
        if (claims.contains(instance)) {
            outcome(pass, Outcome.ALREADY_CLAIMED);
            return false;
        }
        if (claims.size() >= 128) {
            outcome(pass, Outcome.CLAIM_CAPACITY);
            return false;
        }
        claims.add(instance);
        boolean saved = prefs.edit().putInt("boot", boot).putStringSet("claims", claims).commit();
        if (!saved) outcome(pass, Outcome.CLAIM_STORAGE_FAILED);
        return saved;
    }

    private void forgetInstance(String instance) {
        var prefs = getSharedPreferences("live-instances", MODE_PRIVATE);
        var claims = new HashSet<>(prefs.getStringSet("claims", java.util.Set.of()));
        if (claims.remove(instance)) prefs.edit().putStringSet("claims", claims).apply();
    }

    private void forgetWindow(int id) {
        var prefs = getSharedPreferences("live-instances", MODE_PRIVATE);
        var claims = new HashSet<>(prefs.getStringSet("claims", java.util.Set.of()));
        if (claims.removeIf(claim -> claim.endsWith(":" + id))) prefs.edit().putStringSet("claims", claims).apply();
    }

    @Override public void onInterrupt() {
        cancelScan(Outcome.GATE_REJECTED);
        resetSource();
        cancelCloseCheck();
        skipBanner.dismiss();
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        bindingGeneration++;
        cancelScan(Outcome.GATE_REJECTED);
        current = null;
        currentReportedAt = -1;
        clearNetdiskOpening();
        clearQqOpening();
        resetSource();
        cancelCloseCheck();
        confirmedCloseWindow = null;
        skipBanner.dismiss();
        if (runtime != null) runtime.disconnect(this, connection);
        connection = 0;
    }
}
