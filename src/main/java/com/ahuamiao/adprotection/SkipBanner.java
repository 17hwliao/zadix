package com.ahuamiao.adprotection;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

final class SkipBanner {
    private final AccessibilityService service;
    private final Handler main;
    private final Runnable hide = this::dismiss;
    private View visible;

    SkipBanner(AccessibilityService service, Handler main) {
        this.service = service;
        this.main = main;
    }

    void show() {
        dismiss();
        var banner = new TextView(service);
        banner.setText(R.string.ad_closed_banner);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(14);
        banner.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        banner.setPadding(dp(19), dp(10), dp(19), dp(10));
        banner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        var background = new GradientDrawable();
        background.setColor(0xff20261f);
        background.setCornerRadius(dp(22));
        banner.setBackground(background);
        var params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(30);
        try {
            manager().addView(banner, params);
            visible = banner;
            main.postDelayed(hide, 1650);
        } catch (IllegalStateException | WindowManager.BadTokenException unavailable) {
            // A disconnect can invalidate the service window token between confirmation and display.
        }
    }

    void dismiss() {
        main.removeCallbacks(hide);
        if (visible == null) return;
        View previous = visible;
        visible = null;
        try {
            manager().removeViewImmediate(previous);
        } catch (IllegalArgumentException removed) {
            // The service window may already have been removed by Android.
        }
    }

    private WindowManager manager() { return service.getSystemService(WindowManager.class); }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
