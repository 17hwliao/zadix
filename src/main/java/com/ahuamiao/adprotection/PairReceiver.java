package com.ahuamiao.adprotection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class PairReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "wireless-pairing";
    private static final String CODE = "pair-code";
    private static final int NOTIFICATION = 121;

    static void showRequest(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent reply = PendingIntent.getBroadcast(context, 0, new Intent(context, PairReceiver.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        Notification.Action action = new Notification.Action.Builder(null, "输入配对码", reply)
                .addRemoteInput(new RemoteInput.Builder(CODE).setLabel("六位配对码").build()).build();
        manager.notify(NOTIFICATION, builder(context).setContentText("保留系统配对窗口，从这里输入配对码。")
                .setTimeoutAfter(120_000).addAction(action).build());
    }

    @Override public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = results == null ? null : results.getCharSequence(CODE);
        if (code == null) return;
        PendingResult pending = goAsync();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Protection.submit(() -> new Protection(context).pair(code.toString().strip()), message -> {
            try {
                manager.notify(NOTIFICATION, builder(context).setContentText(message).setTimeoutAfter(45_000).build());
            } finally {
                pending.finish();
            }
        });
    }

    private static Notification.Builder builder(Context context) {
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, SystemCleanupActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.shield)
                .setContentTitle("Zadix 配对").setContentIntent(open).setAutoCancel(true);
    }
}
