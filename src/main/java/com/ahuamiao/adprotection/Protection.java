package com.ahuamiao.adprotection;

import android.app.KeyguardManager;
import android.content.Context;
import android.provider.Settings;
import android.util.AtomicFile;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class Protection {
    enum Phase { READY, APPLY_PENDING, APPLIED, RESTORE_PENDING, RESTORED, CONFLICT }

    record State(Receipt receipt, Phase phase, boolean attempted, int commandBoot, Integer exitCode,
                 String observed, long readAt, int readBoot) {
        State pending(Phase next, Reading before) {
            return new State(receipt, next, true, before.boot(), null, before.value(),
                    System.currentTimeMillis(), before.boot());
        }

        State observe(Phase next, Reading reading) {
            return new State(receipt, next, attempted, commandBoot, exitCode, reading.value(),
                    System.currentTimeMillis(), reading.boot());
        }

        State acknowledged(int exit) {
            return new State(receipt, phase, attempted, commandBoot, exit, observed, readAt, readBoot);
        }

        boolean needsReview() {
            return phase == Phase.APPLY_PENDING || phase == Phase.RESTORE_PENDING || phase == Phase.CONFLICT;
        }

        static State decode(String value, Rule rule) throws Exception {
            JSONObject j = new JSONObject(value);
            Receipt receipt = Receipt.parse(j.getString("receipt"), rule);
            return new State(receipt, Phase.valueOf(j.getString("phase")), j.getBoolean("attempted"),
                    j.getInt("commandBoot"), j.isNull("exit") ? null : j.getInt("exit"),
                    j.optString("observed", ""), j.getLong("readAt"), j.getInt("readBoot"));
        }

        byte[] encode() throws Exception {
            return new JSONObject().put("receipt", receipt.encoded()).put("phase", phase.name())
                    .put("attempted", attempted).put("commandBoot", commandBoot)
                    .put("exit", exitCode == null ? JSONObject.NULL : exitCode)
                    .put("observed", observed).put("readAt", readAt).put("readBoot", readBoot)
                    .toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final long FRESH_MS = 60_000;

    interface Operation { String run() throws Exception; }
    interface Finished { void result(String message); }

    static boolean busy() { return BUSY.get(); }

    static void submit(Operation operation, Finished finished) {
        if (!BUSY.compareAndSet(false, true)) {
            finished.result("正在处理，请等本次操作完成");
            return;
        }
        new Thread(() -> {
            String message;
            try {
                message = operation.run();
            } catch (Exception error) {
                var frames = error.getStackTrace();
                android.util.Log.w("AdProtection", error.getClass().getSimpleName()
                        + (frames.length == 0 ? "" : " at " + frames[0]));
                if (error instanceof AdbPairingRequiredException || error instanceof AdbAuthenticationFailedException) {
                    message = "本机无线调试需要重新配对";
                } else {
                    message = error instanceof IOException && error.getMessage() != null
                            ? error.getMessage() : "操作未完成，请检查当前状态";
                }
            } finally {
                BUSY.set(false);
            }
            finished.result(message);
        }, "ad-protection-operation").start();
    }

    final Context context;
    final Rule rule;
    final LocalAdb adb;
    private final AtomicFile current;
    private final AtomicFile pairing;

    Protection(Context context) throws Exception {
        this.context = context.getApplicationContext();
        try (var input = context.getAssets().open("rule.json")) {
            rule = Rule.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        adb = new LocalAdb(context);
        current = new AtomicFile(new File(context.getNoBackupFilesDir(), "cycle.json"));
        pairing = new AtomicFile(new File(context.getNoBackupFilesDir(), "pairing-state"));
    }

    State current() throws Exception {
        byte[] saved = DiskState.read(current);
        return saved == null ? null : State.decode(new String(saved, StandardCharsets.UTF_8), rule);
    }

    boolean fresh(State state) {
        long elapsed = System.currentTimeMillis() - state.readAt();
        return adb.wirelessOn() && elapsed >= 0 && elapsed < FRESH_MS
                && Settings.Global.getInt(context.getContentResolver(), "boot_count", -1) == state.readBoot();
    }

    String checkNotice() {
        return context.getSharedPreferences("checks", Context.MODE_PRIVATE).getString("notice", "");
    }

    void noteFailure(Exception error) {
        String label = error instanceof AdbPairingRequiredException || error instanceof AdbAuthenticationFailedException
                ? "需重新配对" : "需检查";
        context.getSharedPreferences("checks", Context.MODE_PRIVATE).edit().putString("notice", label).commit();
    }

    private void clearCheckNotice() {
        context.getSharedPreferences("checks", Context.MODE_PRIVATE).edit().remove("notice").apply();
    }

    String importCycle(String encoded) throws Exception {
        unlocked();
        if (!rule.enabled()) throw new IOException("候选规则还未通过验证，暂不可准备写入");
        Receipt receipt = Receipt.parse(encoded, rule);
        State old = current();
        if (old != null && old.receipt().cycle().equals(receipt.cycle())) return "这个周期已经接收过";
        if (old != null && old.attempted() && old.phase() != Phase.RESTORED) {
            throw new IOException("请先核对或恢复上一个周期");
        }
        File history = new File(context.getNoBackupFilesDir(), "cycles");
        if (!history.isDirectory() && !history.mkdirs()) throw new IOException("无法创建周期记录目录");
        if (DiskState.read(new AtomicFile(new File(history, receipt.cycle() + ".json"))) != null) {
            throw new IOException("这个周期已结束，请在电脑上准备新记录");
        }
        if (old != null) {
            AtomicFile archive = new AtomicFile(new File(history, old.receipt().cycle() + ".json"));
            if (DiskState.read(archive) == null) DiskState.save(archive, old.encode());
        }
        save(new State(receipt, Phase.READY, false, -1, null, "", 0, -1));
        return "已接收电脑核验的记录。开启前会重新读取原值。";
    }

    String inspect() throws Exception {
        try {
            String message = inspectConnection();
            clearCheckNotice();
            return message;
        } catch (Exception error) {
            noteFailure(error);
            throw error;
        }
    }

    private String inspectConnection() throws Exception {
        unlocked();
        try (var session = adb.open()) {
            Reading reading = read(session);
            State state = current();
            if (state == null) return rule.title() + "当前值为 " + reading.value() + "。会话已读回。";
            requireIdentity(state, reading);
            Phase next = state.phase();
            if (next == Phase.APPLIED && reading.value().equals(state.receipt().original())) next = Phase.RESTORED;
            if (next == Phase.READY && !reading.value().equals(state.receipt().original())) next = Phase.CONFLICT;
            save(state.observe(next, reading));
            return next == Phase.CONFLICT ? "原值与准备记录不同，请重新准备或核对"
                    : "已读回当前值 " + reading.value();
        }
    }

    String toggle(State expected, boolean confirmRecovery) throws Exception {
        unlocked();
        if (!rule.enabled()) throw new IOException("候选规则尚未通过验证，当前只支持只读检查");
        State state = current();
        if (state == null || state.phase() == Phase.RESTORED) throw new IOException("请先在电脑准备新周期");
        if (!state.equals(expected) || (confirmRecovery && !state.needsReview())) {
            throw new IOException("周期状态已变化，请重新核对");
        }
        try (var session = adb.open()) {
            Reading before = read(session);
            requireIdentity(state, before);
            Receipt receipt = state.receipt();
            if (state.phase() == Phase.READY) {
                if (!receipt.original().equals(before.value())) {
                    save(state.observe(Phase.CONFLICT, before));
                    return "原值已变化，已暂停写入。请重新准备记录。";
                }
                return write(session, state, before, receipt.target(), Phase.APPLY_PENDING, Phase.APPLIED);
            }
            if (state.needsReview()) {
                if (!confirmRecovery || !state.attempted()) throw new IOException("请先在 App 内核对本周期和当前值");
                if (!before.value().equals(expected.observed()) || !fresh(expected)) {
                    save(state.observe(state.phase(), before));
                    throw new IOException("当前值或读回时间已变化，请重新确认");
                }
                if (state.exitCode() == null && before.boot() <= state.commandBoot()) {
                    throw new IOException("上次命令可能尚未结束。请重启后重新核对，再决定是否恢复。");
                }
            }
            if (receipt.original().equals(before.value())) {
                save(state.observe(Phase.RESTORED, before));
                return "已读回原值，无需再次写入";
            }
            if (!receipt.target().equals(before.value())) {
                save(state.observe(Phase.CONFLICT, before));
                return "当前值与周期记录冲突，请先核对";
            }
            return write(session, state, before, receipt.original(), Phase.RESTORE_PENDING, Phase.RESTORED);
        }
    }

    private String write(LocalAdb.Session session, State state, Reading before, String value,
                         Phase pending, Phase done) throws Exception {
        unlocked();
        String shellCommand = rule.writeCommand(value);
        LiveRuntime live = LiveRuntime.get(context);
        if (!live.beginCleanup()) throw new IOException("上一项系统清理尚未确认结束");
        try {
            return writeSequence(state, before, value, pending, done, this::save,
                    () -> session.run(shellCommand), () -> read(session));
        } finally {
            live.finishCleanup(cleanupMayStillRun(context));
        }
    }

    static boolean cleanupMayStillRun(Context context) {
        try {
            State saved = new Protection(context).current();
            if (saved == null || saved.exitCode() != null
                    || (saved.phase() != Phase.APPLY_PENDING && saved.phase() != Phase.RESTORE_PENDING)) return false;
            int boot = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
            return boot <= saved.commandBoot();
        } catch (Exception error) {
            return new File(context.getNoBackupFilesDir(), "cycle.json").exists();
        }
    }

    interface Journal { void save(State state) throws Exception; }
    interface Command { LocalAdb.Result execute() throws Exception; }
    interface Readback { Reading read() throws Exception; }

    static String writeSequence(State state, Reading before, String value, Phase pending, Phase done,
                                Journal journal, Command command, Readback readback) throws Exception {
        State inFlight = state.pending(pending, before);
        journal.save(inFlight);
        LocalAdb.Result result = command.execute();
        inFlight = inFlight.acknowledged(result.exitCode());
        journal.save(inFlight);
        Reading after = readback.read();
        requireIdentity(inFlight, after);
        if (result.exitCode() != 0 || !value.equals(after.value()) || !result.output().isEmpty()) {
            journal.save(inFlight.observe(pending, after));
            return "系统没有确认本次改动，请核对当前值";
        }
        journal.save(inFlight.observe(done, after));
        return done == Phase.APPLIED ? "配置已读回生效" : "配置已读回恢复";
    }

    String pair(String code) throws Exception {
        unlocked();
        DiskState.save(pairing, "needs-cleanup".getBytes(StandardCharsets.UTF_8));
        adb.pair(code);
        return "配对已完成。回到 App，点击只读检查验证连接。";
    }

    String verifyPairRemoved() throws Exception {
        unlocked();
        State state = current();
        if (state != null && state.attempted() && state.phase() != Phase.RESTORED) {
            throw new IOException("请先恢复并核对系统设置，再清理配对记录");
        }
        if (!pairingNeedsCleanup()) return "没有待核对的配对记录";
        if (!adb.hasIdentity()) {
            DiskState.save(pairing, "removed".getBytes(StandardCharsets.UTF_8));
            return "配对未建立，无需清理";
        }
        try (var ignored = adb.open()) {
            return "系统仍接受本 App 的配对，请在无线调试里忘记 " + LocalAdb.PAIR_NAME;
        } catch (AdbPairingRequiredException | AdbAuthenticationFailedException expected) {
            DiskState.save(pairing, "removed".getBytes(StandardCharsets.UTF_8));
            return "已核实本 App 的配对授权失效";
        }
    }

    boolean pairingNeedsCleanup() throws Exception {
        byte[] saved = DiskState.read(pairing);
        return saved != null && !"removed".equals(new String(saved, StandardCharsets.UTF_8));
    }

    void requireUninstallReady() throws Exception {
        State state = current();
        if (state != null && state.attempted() && state.phase() != Phase.RESTORED) {
            throw new IOException("请先恢复并核对系统设置");
        }
        if (pairingNeedsCleanup()) throw new IOException("请先清理并核对本 App 的无线配对");
    }

    private Reading read(LocalAdb.Session session) throws Exception {
        LocalAdb.Result result = session.run(rule.readCommand());
        if (result.exitCode() != 0) {
            android.util.Log.w("AdProtection", "Readback shell exit=" + result.exitCode()
                    + ", permissionError=" + result.output().contains("SecurityException"));
            throw new IOException("系统拒绝读取设置");
        }
        Reading reading;
        try {
            reading = Reading.parse(result.output());
        } catch (IllegalArgumentException error) {
            android.util.Log.w("AdProtection", "Readback format: " + error.getClass().getSimpleName()
                    + ", fields=" + result.output().lines().filter(line -> line.startsWith("AAP_")).count()
                    + ", permissionError=" + result.output().contains("SecurityException"));
            throw new IOException("无法核对无线会话返回的设备信息或设置值", error);
        }
        if (!rule.matches(reading)) throw new IOException("设备、系统版本或用户不在首版范围内");
        return reading;
    }

    private static void requireIdentity(State state, Reading reading) throws IOException {
        if (!state.receipt().matches(reading)) throw new IOException("本机身份与恢复记录不一致，请保留原记录");
    }

    private void unlocked() throws IOException {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IOException("请先解锁手机");
    }

    private void save(State state) throws Exception { DiskState.save(current, state.encode()); }
}
