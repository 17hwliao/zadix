package com.ahuamiao.adprotection;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://zadix.local";
    private static final String PAGE = ORIGIN + "/index.html";
    private static final String RELEASES = "https://github.com/" + BuildConfig.RELEASE_REPOSITORY + "/releases";
    private static final String RELEASE_API = "https://api.github.com/repos/" + BuildConfig.RELEASE_REPOSITORY
            + "/releases?per_page=20";
    private static final String TAG = "v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}";
    private static final int IMPORT_RULES = 2;
    private static final int SAVE_RULES = 3;
    private static final Map<String,String> ASSETS = Map.of("/index.html", "text/html", "/style.css", "text/css",
            "/app.js", "application/javascript", "/rules.js", "application/javascript");

    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ContentObserver liveChanges = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    private WebView web;
    private WebMessagePort ui;
    private Uri endpoint;
    private Uri pendingSave;
    private boolean refreshing;
    private boolean refreshAgain;
    private boolean changing;
    private boolean rulesBusy;
    private boolean updating;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        endpoint = LiveControlProvider.endpoint(this);
        if (saved != null) {
            pendingSave = saved.getParcelable("pendingSave", Uri.class);
            rulesBusy = pendingSave != null;
        }
        var frame = new FrameLayout(this);
        frame.setBackgroundColor(0xfff3f4f1);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            var controller = view.getWindowInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
            return insets;
        });
        web = new WebView(this);
        web.setBackgroundColor(0xfff3f4f1);
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                var url = request.getUrl();
                return !request.isForMainFrame() || !trusted(url) || !"/index.html".equals(url.getPath());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String mime = trusted(url) ? ASSETS.get(url.getPath()) : null;
                if (mime != null && "GET".equals(request.getMethod())) {
                    try {
                        return new WebResourceResponse(mime, "utf-8", getAssets().open("zadix" + url.getPath()));
                    } catch (IOException ignored) { }
                }
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Map.of(),
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!PAGE.equals(url) || ui != null || isDestroyed()) return;
                WebMessagePort[] pair = view.createWebMessageChannel();
                ui = pair[0];
                ui.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort port, WebMessage message) {
                        if (port == ui && !isDestroyed()) action(message.getData());
                    }
                });
                view.postWebMessage(new WebMessage("zadix-ready", new WebMessagePort[]{pair[1]}), Uri.parse(ORIGIN));
            }
        });
        frame.addView(web);
        setContentView(frame);
        web.loadUrl(PAGE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> web.evaluateJavascript("typeof zadixBack === 'function' && zadixBack()",
                        handled -> { if (!"true".equals(handled)) finish(); }));
    }

    private static boolean trusted(Uri url) {
        return "https".equals(url.getScheme()) && "zadix.local".equals(url.getEncodedAuthority());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("pendingSave", pendingSave);
    }

    @Override protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(endpoint, false, liveChanges);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        post("catalog-refresh");
    }

    @Override protected void onStop() {
        getContentResolver().unregisterContentObserver(liveChanges);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (ui != null) ui.close();
        ui = null;
        web.destroy();
        requests.shutdown();
        network.shutdown();
        super.onDestroy();
    }

    private void action(String name) {
        if (name == null) return;
        switch (name) {
            case "refresh" -> refresh();
            case "toggle" -> toggle();
            case "settings" -> openAccessibility();
            case "tile" -> addTile();
            case "pick" -> pick();
            case "share" -> export(false);
            case "save" -> export(true);
            case "update" -> checkUpdate();
            case "releases" -> openLink(RELEASES);
            case "cleanup" -> launch(new Intent(this, SystemCleanupActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "无法打开系统清理");
            default -> {
                if (name.startsWith("release:") && name.substring(8).matches(TAG)) {
                    openLink(RELEASES + "/tag/" + name.substring(8));
                } else if (name.startsWith("catalog:")) {
                    readCatalog(name.split(":", -1));
                } else if (name.startsWith("opening:")) {
                    chooseOpening(name.split(":", -1));
                }
            }
        }
    }

    private void readCatalog(String[] fields) {
        if (fields.length != 4 || !fields[1].matches("[a-z]{1,12}")
                || !fields[2].matches("[0-9]{1,7}") || !fields[3].matches("[0-9]{1,9}")) return;
        String category = fields[1];
        int offset = Integer.parseInt(fields[2]), requestId = Integer.parseInt(fields[3]);
        var arguments = new Bundle();
        arguments.putString("category", category);
        arguments.putInt("offset", offset);
        request(LiveControlProvider.CATALOG, arguments, result -> {
            if (result == null) {
                post("catalog", "requestId", requestId, "error", "无法读取应用列表，请重试。");
                return;
            }
            var apps = new JSONArray();
            for (var app : result.getParcelableArrayList("apps", Bundle.class)) {
                apps.put(json("packageName", app.getString("packageName"), "label", app.getString("label"),
                        "available", app.getBoolean("available"), "enabled", app.getBoolean("enabled")));
            }
            post("catalog", "requestId", requestId, "apps", apps, "next", result.getInt("next", -1));
        });
    }

    private void chooseOpening(String[] fields) {
        if (fields.length != 3 || !fields[1].matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
                || fields[1].length() > 255 || !("0".equals(fields[2]) || "1".equals(fields[2]))) return;
        String pkg = fields[1];
        var arguments = new Bundle();
        arguments.putString("packageName", pkg);
        arguments.putBoolean("enabled", "1".equals(fields[2]));
        request(LiveControlProvider.CHOOSE_OPENING, arguments, result -> post("opening",
                "packageName", pkg, "enabled", result != null && result.getBoolean("enabled"),
                "refresh", result == null,
                "error", result == null ? "保存结果待确认，正在重新读取。" : result.getString("error")));
    }

    private void refresh() {
        if (isDestroyed()) return;
        if (refreshing) {
            refreshAgain = true;
            return;
        }
        refreshing = true;
        request(LiveControlProvider.SNAPSHOT, null, reply -> {
            refreshing = false;
            render(reply);
            if (refreshAgain) {
                refreshAgain = false;
                refresh();
            }
        });
    }

    private void toggle() {
        if (changing) return;
        changing = true;
        request(LiveControlProvider.TOGGLE, null, reply -> {
            changing = false;
            if (reply != null) render(reply);
            post("toggle", "message", reply == null ? "切换结果待确认，正在重新读取。" : "");
            if (reply == null) refresh();
            else if (reply.getBoolean("needsGrant")) openAccessibility();
        });
    }

    private void render(Bundle state) {
        var value = json("type", "state", "version", BuildConfig.VERSION_NAME,
                "build", BuildConfig.VERSION_CODE, "rulesBusy", rulesBusy);
        try {
            if (state == null) {
                value.put("phase", "UNAVAILABLE").put("label", "无法连接");
            } else {
                for (String key : state.keySet()) {
                    if (!"needsGrant".equals(key)) value.put(key, state.get(key));
                }
            }
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        post(value);
    }

    private void openAccessibility() {
        try {
            startActivity(LiveRuntime.permissionSettings(this));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开系统无障碍设置");
        }
    }

    private void addTile() {
        try {
            getSystemService(StatusBarManager.class).requestAddTileService(new ComponentName(this, ProtectionTile.class),
                    ProtectionTile.LABEL, Icon.createWithResource(this, R.drawable.shield), getMainExecutor(), code -> {
                        boolean added = code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                || code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                        post("notice", "message", added ? "「实时跳过」磁贴已添加"
                                : "可在控制中心的编辑页手动添加「实时跳过」");
                    });
        } catch (RuntimeException unavailable) {
            post("notice", "message", "请在控制中心的编辑页手动添加「实时跳过」");
        }
    }

    private void pick() {
        if (rulesBusy) return;
        rulesBusy = true;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), IMPORT_RULES);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            rulesResult("无法打开文件选择器", true);
        }
    }

    private void export(boolean save) {
        if (rulesBusy) return;
        rulesBusy = true;
        request(LiveControlProvider.EXPORT, null, reply -> {
            Uri file = reply == null ? null : reply.getParcelable("file", Uri.class);
            if (file == null) {
                rulesResult(reply == null ? "无法读取打包结果" : reply.getString("message", "无法打包规则"), true);
                return;
            }
            if (save) {
                pendingSave = file;
                try {
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(RulePackage.MIME)
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, RuleShareProvider.NAME), SAVE_RULES);
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    pendingSave = null;
                    rulesResult("无法打开保存位置", true);
                }
            } else {
                var share = new Intent(Intent.ACTION_SEND).setType(RulePackage.MIME)
                        .putExtra(Intent.EXTRA_STREAM, file)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newRawUri(RuleShareProvider.NAME, file));
                boolean opened = launch(Intent.createChooser(share, "分享 Zadix 规则包"), "无法打开系统分享");
                rulesResult(opened ? "规则包已准备好，请选择分享目标。" : "无法打开系统分享。", !opened);
            }
        });
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == IMPORT_RULES) {
            if (code != RESULT_OK || data == null) {
                rulesResult("已取消选择文件。", false);
                return;
            }
            Uri file = data.getData();
            var clip = data.getClipData();
            if (file == null && clip != null && clip.getItemCount() == 1) file = clip.getItemAt(0).getUri();
            if (file == null || (clip != null && clip.getItemCount() > 1)) {
                rulesResult("请选择一个 .zadixsource 资料包。", true);
                return;
            }
            var args = new Bundle();
            args.putParcelable("file", file);
            request(LiveControlProvider.IMPORT, args, reply -> {
                rulesResult(reply == null ? "导入结果待确认，正在重新读取规则。" : reply.getString("message"),
                        reply == null || reply.getBoolean("error"));
                refresh();
            });
        } else if (request == SAVE_RULES) {
            Uri source = pendingSave;
            pendingSave = null;
            Uri target = code == RESULT_OK && data != null ? data.getData() : null;
            if (target == null || source == null) {
                rulesResult(target == null ? "已取消保存。" : "规则包已失效，请重新打包。", target != null);
                return;
            }
            requests.execute(() -> {
                boolean saved = false;
                try (var input = getContentResolver().openInputStream(source);
                     var output = getContentResolver().openOutputStream(target, "wt")) {
                    if (input != null && output != null) {
                        input.transferTo(output);
                        output.flush();
                        saved = true;
                    }
                } catch (IOException | RuntimeException failed) { saved = false; }
                boolean success = saved;
                runOnUiThread(() -> rulesResult(success ? "规则包已保存。" : "保存失败，请重新选择位置。", !success));
            });
        }
    }

    private void rulesResult(String message, boolean error) {
        rulesBusy = false;
        post("rules", "message", message == null ? "规则操作结果暂不可用。" : message, "error", error);
    }

    private boolean launch(Intent intent, String failure) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            post("notice", "message", failure);
            return false;
        }
    }

    private void openLink(String link) {
        launch(new Intent(Intent.ACTION_VIEW, Uri.parse(link)), "没有可打开发布记录的浏览器");
    }

    private void checkUpdate() {
        if (updating) return;
        updating = true;
        network.execute(() -> {
            JSONObject result;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Zadix/" + BuildConfig.VERSION_NAME);
                int status = connection.getResponseCode();
                if (status != 200) {
                    result = json("type", "update", "error", status == 403 || status == 429
                            ? "GitHub 暂时限制了查询，请稍后重试。" : "暂时无法读取发布记录。");
                } else try (var input = connection.getInputStream()) {
                    byte[] bytes = input.readNBytes(524289);
                    if (bytes.length > 524288) throw new IOException("发布记录超出读取限制。");
                    var releases = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                    var tags = new JSONArray();
                    for (int i = 0; i < releases.length(); i++) {
                        var release = releases.getJSONObject(i);
                        String tag = release.optString("tag_name");
                        if (tag.matches(TAG) && !release.optBoolean("prerelease") && !release.optBoolean("draft")) {
                            var assets = new JSONArray();
                            var published = release.optJSONArray("assets");
                            for (int j = 0; published != null && j < published.length(); j++) {
                                var asset = published.optJSONObject(j);
                                if (asset != null && "uploaded".equals(asset.optString("state"))) {
                                    assets.put(json("name", asset.optString("name"), "state", "uploaded"));
                                }
                            }
                            tags.put(json("tag_name", tag, "assets", assets));
                        }
                    }
                    result = json("type", "update", "releases", tags);
                }
            } catch (java.net.SocketTimeoutException error) {
                result = json("type", "update", "error", "检查更新超时，请重试。");
            } catch (IOException | JSONException error) {
                result = json("type", "update", "error", "无法检查更新，请检查网络或打开 GitHub 查看。");
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject response = result;
            runOnUiThread(() -> {
                updating = false;
                post(response);
            });
        });
    }

    private void request(String method, Bundle arguments, Consumer<Bundle> finished) {
        requests.execute(() -> {
            Bundle reply;
            try {
                reply = getContentResolver().call(endpoint, method, null, arguments);
            } catch (RuntimeException unavailable) {
                reply = null;
            }
            Bundle received = reply;
            runOnUiThread(() -> { if (!isDestroyed()) finished.accept(received); });
        });
    }

    private void post(String type, Object... fields) {
        JSONObject value = json(fields);
        try { value.put("type", type); }
        catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        post(value);
    }

    private void post(JSONObject value) {
        if (ui != null && !isDestroyed()) ui.postMessage(new WebMessage(value.toString()));
    }

    private static JSONObject json(Object... fields) {
        var result = new JSONObject();
        try {
            for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        return result;
    }
}
