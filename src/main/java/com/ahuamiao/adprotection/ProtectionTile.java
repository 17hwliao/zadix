package com.ahuamiao.adprotection;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class ProtectionTile extends TileService {
    static final String LABEL = "实时跳过";
    private LiveRuntime runtime;
    private final Runnable refresh = this::render;

    @Override public void onStartListening() {
        runtime = LiveRuntime.get(this);
        runtime.listen(refresh);
        runtime.refreshPermission();
        render();
    }

    @Override public void onStopListening() {
        if (runtime != null) runtime.unlisten(refresh);
    }

    @Override public void onDestroy() {
        if (runtime != null) runtime.unlisten(refresh);
        super.onDestroy();
    }

    @Override public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::toggle);
            return;
        }
        toggle();
    }

    private void toggle() {
        runtime = LiveRuntime.get(this);
        if (runtime.toggle()) openPermissionSettings();
        render();
    }

    private void openPermissionSettings() {
        Intent intent = LiveRuntime.permissionSettings(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null || runtime == null) return;
        var status = runtime.gate.status();
        String subtitle = LiveRuntime.label(status);
        // ColorOS may omit our subtitle and announce its own generic switch text.
        tile.setLabel(colorOsLabel(status));
        tile.setSubtitle(subtitle);
        tile.setStateDescription(subtitle);
        tile.setContentDescription(LABEL + " · " + subtitle);
        tile.setState(status.phase() == LiveController.Phase.RUNNING
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private static String colorOsLabel(LiveController.Status status) {
        if (!"PKR110".equals(Build.MODEL) || !"PKR110_16.0.10.500(CN01)".equals(Build.DISPLAY)) return LABEL;
        return switch (status.phase()) {
            case PAUSED -> "跳过暂停";
            case RUNNING -> "跳过运行";
            case PERMISSION -> "跳过授权";
            case RECOVERY -> status.failure() == LiveController.Failure.DISCONNECTED ? "跳过连接" : "跳过待查";
            case STARTING, PAUSING -> "跳过切换";
            case CLEANING -> "跳过清理";
        };
    }
}
