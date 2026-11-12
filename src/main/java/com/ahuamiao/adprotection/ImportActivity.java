package com.ahuamiao.adprotection;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

public final class ImportActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Protection protection = new Protection(this);
            String payload = getIntent().getStringExtra("cycle");
            Receipt receipt = Receipt.parse(payload, protection.rule);
            new AlertDialog.Builder(this).setTitle("接收电脑恢复记录")
                    .setMessage(protection.rule.title() + "\n原值 " + receipt.original() + "，目标 " + receipt.target()
                            + "\n周期 " + receipt.cycle().substring(0, 8)
                            + "\n请确认这份记录已保存在电脑。接收后可在 Zadix 的「系统清理」中查看。")
                    .setNegativeButton("取消", (dialog, which) -> finish()).setOnCancelListener(dialog -> finish())
                    .setPositiveButton("接收", (dialog, which) -> Protection.submit(() -> protection.importCycle(payload),
                            message -> runOnUiThread(() -> showResult(message)))).show();
        } catch (Exception error) {
            showResult("恢复记录无效，请检查电脑原件");
        }
    }

    private void showResult(String message) {
        if (isDestroyed()) return;
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("完成", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }
}
