package com.ahuamiao.adprotection;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Private IPC. All decisions about live protection stay with the system-bound service. */
public final class LiveControlProvider extends ContentProvider {
    static final String SNAPSHOT = "snapshot";
    static final String TOGGLE = "toggle";
    static final String IMPORT = "import";
    static final String EXPORT = "export";
    static final String CATALOG = "catalog";
    static final String CHOOSE_OPENING = "choose-opening";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context context;
    private LiveRuntime runtime;
    private RuleCatalog catalog;

    static Uri endpoint(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".live");
    }

    @Override public boolean onCreate() {
        context = Objects.requireNonNull(getContext()).getApplicationContext();
        runtime = LiveRuntime.get(context);
        runtime.listen(this::notifyClients);
        new Thread(() -> {
            StartupSourceStore.load(context);
            notifyClients();
        }, "load-startup-source").start();
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        return switch (method) {
            case SNAPSHOT -> onMain(this::snapshot);
            case TOGGLE -> onMain(() -> {
                boolean needsGrant = runtime.toggle();
                Bundle reply = snapshot();
                reply.putBoolean("needsGrant", needsGrant);
                return reply;
            });
            case IMPORT -> importRules(extras == null ? null : extras.getParcelable("file", Uri.class));
            case EXPORT -> exportRules();
            case CATALOG -> catalog().page(extras.getString("category"), extras.getInt("offset"));
            case CHOOSE_OPENING -> chooseOpening(extras);
            default -> throw new IllegalArgumentException("Unknown live-control operation");
        };
    }

    private synchronized RuleCatalog catalog() {
        if (catalog == null) catalog = new RuleCatalog(context, runtime);
        return catalog;
    }

    private Bundle chooseOpening(Bundle args) {
        String pkg = args.getString("packageName");
        boolean enabled = args.getBoolean("enabled");
        if (!catalog().supportsOpening(pkg)) {
            var result = new Bundle();
            result.putString("error", "此应用当前没有可用的开屏规则。");
            return result;
        }
        return onMain(() -> {
            var result = new Bundle();
            if (!runtime.chooseOpening(pkg, enabled)) result.putString("error", "保存失败，请重试。");
            result.putBoolean("enabled", runtime.allowsOpening(pkg));
            return result;
        });
    }

    private Bundle snapshot() {
        var status = runtime.gate.status();
        var rules = StartupSourceStore.current();
        Bundle result = new Bundle();
        result.putString("label", LiveRuntime.label(status));
        result.putString("phase", status.phase().name());
        result.putString("detail", LiveRuntime.detail(status));
        result.putString("lastResult", runtime.recentResult());
        result.putString("rules", rules == null ? "尚未导入资料包" : rules.description());
        result.putBoolean("permission", status.phase() != LiveController.Phase.PERMISSION);
        result.putBoolean("desired", status.desired());
        result.putBoolean("canExport", rules != null && rules.profile() != null);
        return result;
    }

    private Bundle importRules(Uri file) {
        Bundle reply = new Bundle();
        try {
            var installed = StartupSourceStore.install(context, file);
            reply.putString("message", "已导入资料包 · " + installed.description());
            notifyClients();
        } catch (Exception error) {
            reply.putString("message", "导入失败 · " + error.getMessage());
            reply.putBoolean("error", true);
        }
        return reply;
    }

    private Bundle exportRules() {
        Bundle reply = new Bundle();
        try {
            reply.putParcelable("file", StartupSourceStore.export(context));
        } catch (Exception error) {
            reply.putString("message", "无法打包规则 · " + error.getMessage());
        }
        return reply;
    }

    private Bundle onMain(Callable<Bundle> operation) {
        FutureTask<Bundle> task = new FutureTask<>(operation);
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else main.post(task);
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("服务请求已中断", error);
        } catch (TimeoutException error) {
            task.cancel(false);
            throw new IllegalStateException("服务响应超时", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("服务操作失败", error.getCause());
        }
    }

    private void notifyClients() {
        context.getContentResolver().notifyChange(endpoint(context), null, 0);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
