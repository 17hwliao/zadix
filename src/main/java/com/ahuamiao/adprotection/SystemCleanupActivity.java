package com.ahuamiao.adprotection;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class SystemCleanupActivity extends Activity {
    private static final int NOTIFICATIONS = 1;
    private Protection protection;
    private TextView info;
    private TextView result;
    private Button recover;
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            protection = new Protection(this);
        } catch (Exception ignored) { /* The screen reports the unavailable rule. */ }
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bounds = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom);
            return insets;
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("系统清理");
        title.setTextSize(28);
        layout.addView(title);
        info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, padding, 0, padding);
        layout.addView(info);
        if (protection != null) {
            button(layout, "开始无线调试配对", view -> preparePairing());
            button(layout, "只读检查", view -> perform(protection::inspect));
            recover = button(layout, "核对后恢复本周期", view -> confirmRecovery());
            button(layout, "应用／恢复本周期", view -> confirmSystemChange());
            button(layout, "恢复并卸载", view -> uninstall());
        }
        result = new TextView(this);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(result);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private Button button(LinearLayout layout, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        layout.addView(button);
        return button;
    }

    private void render() {
        if (protection == null) {
            info.setText("系统清理 · 无法加载本机规则");
            return;
        }
        try {
            Protection.State state = protection.current();
            String text = "系统清理 · " + protection.rule.title() + (protection.rule.enabled() ? "" : "（候选，仅可只读检查）")
                    + "\n无线调试" + (protection.adb.wirelessOn() ? "已打开" : "已关闭");
            String notice = protection.checkNotice();
            if (!notice.isEmpty()) text += " · " + notice;
            recover.setEnabled(state != null && state.attempted() && state.needsReview());
            if (state == null) text += "\n电脑尚未准备开启周期。";
            else {
                text += "\n" + label(state) + " · " + state.receipt().cycle().substring(0, 8)
                        + "\n原值 " + state.receipt().original() + "，目标 " + state.receipt().target();
                if (state.readAt() > 0) {
                    text += "\n最近读回 " + state.observed() + " · " + time.format(Instant.ofEpochMilli(state.readAt()));
                }
            }
            info.setText(text);
        } catch (Exception error) {
            recover.setEnabled(false);
            info.setText("周期记录无法读取，已保留原件。请使用电脑上的恢复记录核对。");
        }
    }

    static String label(Protection.State state) {
        return switch (state.phase()) {
            case READY -> "待开启";
            case APPLIED -> "已读回生效";
            case RESTORED -> "已读回恢复";
            default -> "需检查";
        };
    }

    private void perform(Protection.Operation operation) {
        result.setText("正在处理…");
        Protection.submit(operation, message -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            result.setText(message);
            render();
        }));
    }

    private void confirmSystemChange() {
        try {
            Protection.State state = protection.current();
            if (!protection.rule.enabled() || state == null) {
                result.setText("当前没有可执行的系统清理规则");
                return;
            }
            if (state.needsReview()) {
                confirmRecovery();
                return;
            }
            if (state.phase() != Protection.Phase.READY && state.phase() != Protection.Phase.APPLIED) {
                result.setText("请先准备新的恢复记录");
                return;
            }
            String action = state.phase() == Protection.Phase.READY ? "应用" : "恢复";
            new AlertDialog.Builder(this).setTitle(action + protection.rule.title() + "？")
                    .setMessage("本次只处理这项设置，执行前会再次核对当前值和恢复记录。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(action, (dialog, which) -> perform(() -> protection.toggle(state, false))).show();
        } catch (Exception error) {
            result.setText("无法核对周期记录");
        }
    }

    private void confirmRecovery() {
        try {
            Protection.State state = protection.current();
            if (state == null || !state.needsReview() || !protection.fresh(state)) {
                result.setText("请先用只读检查确认当前值");
                return;
            }
            new AlertDialog.Builder(this).setTitle("恢复到本周期的原值？")
                    .setMessage("原值 " + state.receipt().original() + "，目标 " + state.receipt().target()
                            + "，最近读回 " + state.observed() + "。恢复会重新核对当前值。")
                    .setNegativeButton("取消", null).setPositiveButton("确认恢复", (dialog, which) ->
                            perform(() -> protection.toggle(state, true))).show();
        } catch (Exception error) {
            result.setText("无法核对周期记录");
        }
    }

    private void preparePairing() {
        new AlertDialog.Builder(this).setTitle("授权本机无线调试")
                .setMessage("打开系统设置里的无线调试，选择「使用配对码配对设备」。保留配对窗口，下拉通知栏输入六位配对码。无线调试允许执行系统命令，配对目标仅为本机。")
                .setNegativeButton("取消", null).setPositiveButton("继续", (dialog, which) -> {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                    } else openPairingSettings();
                }).show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            openPairingSettings();
        }
    }

    private void openPairingSettings() {
        PairReceiver.showRequest(this);
        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    private void uninstall() {
        if (Protection.busy()) {
            result.setText("请等待当前操作完成");
            return;
        }
        try {
            Protection.State state = protection.current();
            if (state != null && state.phase() == Protection.Phase.APPLIED) {
                new AlertDialog.Builder(this).setTitle("先恢复系统设置")
                        .setMessage("本周期的设置仍处于已应用状态。恢复并读回后再清理配对。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复", (dialog, which) -> perform(() -> protection.toggle(state, false))).show();
                return;
            }
            if (state != null && state.attempted() && state.phase() != Protection.Phase.RESTORED) {
                result.setText("先用只读检查核对本周期，再确认恢复");
                return;
            }
            if (protection.pairingNeedsCleanup()) {
                new AlertDialog.Builder(this).setTitle("清理无线配对")
                        .setMessage("在系统的无线调试里忘记 " + LocalAdb.PAIR_NAME
                                + "，保持无线调试打开以便核验，再回这里点击「核验配对」。")
                        .setNegativeButton("取消", null)
                        .setNeutralButton("打开开发者选项", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                        .setPositiveButton("核验配对", (dialog, which) -> perform(protection::verifyPairRemoved)).show();
                return;
            }
            protection.requireUninstallReady();
            LiveRuntime.get(this).choose(false);
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception error) {
            result.setText("卸载前的核对还未完成。请保留电脑上的恢复记录。");
        }
    }
}
