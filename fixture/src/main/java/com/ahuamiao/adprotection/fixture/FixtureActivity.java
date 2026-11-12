package com.ahuamiao.adprotection.fixture;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A deterministic test target with no dependency on the Ad-Killer implementation. */
public final class FixtureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String scenario;
    private LinearLayout page;
    private TextView countView;
    private Dialog dialog;
    private int accessibilityCount;
    private int manualCount;
    private boolean finished;
    private boolean firstClickFromAccessibility;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showScenario(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showScenario(intent);
    }

    @Override
    protected void onDestroy() {
        resetCallbacks();
        super.onDestroy();
    }

    private void resetCallbacks() {
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void showScenario(Intent intent) {
        resetCallbacks();
        scenario = intent.getStringExtra("scenario");
        if (scenario == null) scenario = "ad";
        finished = false;
        accessibilityCount = 0;
        manualCount = 0;

        page = newLayout();
        int pad = dp(24);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        setContentView(page);
        showCountsOn(page);
        switch (scenario) {
            case "ad", "manual" -> addAd(page);
            case "countdown" -> {
                addAd(page);
                handler.postDelayed(() -> showResult(R.id.result_natural, "倒计时自然结束"),
                        delay(intent, 1800));
            }
            case "repeat" -> {
                addAd(page);
                TextView heartbeat = addText(page, View.NO_ID, "刷新 0");
                for (int frame = 1; frame <= 16; frame++) {
                    final int count = frame;
                    handler.postDelayed(() -> {
                        heartbeat.setText("刷新 " + count);
                        heartbeat.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                    }, frame * 50L);
                }
            }
            case "switch" -> {
                addAd(page);
                handler.postDelayed(this::showOtherWindow, delay(intent, 400));
            }
            case "normal" -> {
                addText(page, View.NO_ID, "普通页面");
                addClose(page);
            }
            case "sensitive" -> {
                addText(page, R.id.sensitive_marker, "测试登录确认");
                addAd(page);
            }
            case "ambiguous" -> {
                addAd(page);
                addClose(page);
            }
            default -> addText(page, View.NO_ID, "未知测试场景");
        }
    }

    private void addAd(LinearLayout layout) {
        addText(layout, R.id.ad_marker, "测试广告");
        addClose(layout);
    }

    private void addClose(LinearLayout layout) {
        CloseButton button = new CloseButton();
        button.setId(R.id.ad_close);
        button.setText("跳过");
        button.setOnClickListener(view -> onClose(button.fromAccessibility));
        layout.addView(button);
    }

    private void onClose(boolean fromAccessibility) {
        if (finished) return;
        if (fromAccessibility) accessibilityCount++;
        else manualCount++;
        updateCounts();
        if (dialog != null || "normal".equals(scenario) || "sensitive".equals(scenario)
                || "ambiguous".equals(scenario)) {
            showResult(R.id.result_unexpected, "拒绝样本收到点击");
            return;
        }
        if ("repeat".equals(scenario)) {
            // Keep the same node alive across more accessibility events after the first click.
            if (accessibilityCount + manualCount == 1) {
                firstClickFromAccessibility = fromAccessibility;
                handler.postDelayed(() -> showClickResult(firstClickFromAccessibility), 1100);
            }
            return;
        }
        showClickResult(fromAccessibility);
    }

    private void showOtherWindow() {
        if (finished) return;
        dialog = new Dialog(this);
        dialog.setTitle("测试窗口切换");
        LinearLayout content = newLayout();
        addText(content, R.id.result_switch, "已切换到新的窗口");
        showCountsOn(content);
        // The foreground dialog deliberately reuses the same IDs.
        addAd(content);
        dialog.setContentView(content);
        dialog.setCancelable(false);
        dialog.show();
    }

    private void showClickResult(boolean fromAccessibility) {
        showResult(fromAccessibility ? R.id.result_auto : R.id.result_manual,
                fromAccessibility ? "收到无障碍节点点击" : "收到手动点击");
    }

    private void showResult(int id, String text) {
        if (finished) return;
        finished = true;
        resetCallbacks();
        page.removeAllViews();
        addText(page, id, text);
        showCountsOn(page);
    }

    private void showCountsOn(LinearLayout layout) {
        countView = addText(layout, R.id.action_count, "");
        updateCounts();
    }

    private void updateCounts() {
        countView.setText("accessibility=" + accessibilityCount + ", manual=" + manualCount);
    }

    private LinearLayout newLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    private TextView addText(LinearLayout layout, int id, String text) {
        TextView view = new TextView(this);
        view.setId(id);
        view.setText(text);
        view.setTextSize(18);
        layout.addView(view);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static long delay(Intent intent, int fallback) {
        return Math.max(0, Math.min(30_000, intent.getIntExtra("delay_ms", fallback)));
    }

    private final class CloseButton extends Button {
        private boolean fromAccessibility;

        private CloseButton() {
            super(FixtureActivity.this);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) {
                return super.performAccessibilityAction(action, arguments);
            }
            fromAccessibility = true;
            try {
                return super.performAccessibilityAction(action, arguments);
            } finally {
                fromAccessibility = false;
            }
        }
    }
}
