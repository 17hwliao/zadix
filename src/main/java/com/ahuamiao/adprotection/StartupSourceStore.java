package com.ahuamiao.adprotection;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import li.songe.zadixsource.data.StartupProfile;

final class StartupSourceStore {
    private static final String LEGACY_FILE = "gkd-startup-profile";
    record Snapshot(StartupProfile profile, String description) {}
    private static volatile Snapshot current;
    private static byte[] archive;

    static Snapshot current() { return current; }

    static synchronized void load(Context context) {
        if (current != null) return;
        AtomicFile file = file(context);
        AtomicFile legacy = new AtomicFile(new File(context.getFilesDir(), LEGACY_FILE));
        try {
            byte[] data;
            if (file.getBaseFile().exists()) {
                try (var input = file.openRead()) {
                    data = input.readNBytes(StartupProfile.MAX_BYTES + 1);
                }
            } else if (legacy.getBaseFile().exists() || new File(legacy.getBaseFile().getPath() + ".bak").exists()) {
                data = RulePackage.fromLegacy(readLegacy(legacy));
            } else {
                return;
            }
            var parsed = RulePackage.read(data);
            archive = parsed.archive();
            current = new Snapshot(parsed.profile(), parsed.profile().description());
        } catch (Exception error) {
            current = new Snapshot(null, "已保存的资料包无法读取，可重新导入");
        }
    }

    private static List<byte[]> readLegacy(AtomicFile file) throws IOException {
        try (var input = new DataInputStream(file.openRead())) {
            if (input.readInt() != 1) throw new IOException("无法识别已保存的规则");
            int count = input.readInt();
            if (count < 1 || count > 16) throw new IOException("规则文件数无效");
            var documents = new ArrayList<byte[]>(count);
            int remaining = StartupProfile.MAX_BYTES;
            for (int i = 0; i < count; i++) {
                int size = input.readInt();
                if (size <= 0 || size > remaining) throw new IOException("规则文件大小无效");
                byte[] bytes = new byte[size];
                input.readFully(bytes);
                remaining -= size;
                documents.add(bytes);
            }
            if (input.read() != -1) throw new IOException("规则文件包含未知数据");
            return documents;
        }
    }

    static synchronized Uri export(Context context) throws IOException {
        load(context);
        if (archive == null) throw new IOException("尚无可分享的资料包");
        return RuleShareProvider.stage(context, archive);
    }

    static synchronized Snapshot install(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("请选择一个 .zadixsource 资料包");
        byte[] data;
        try (var stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("无法读取所选文件");
            data = stream.readNBytes(StartupProfile.MAX_BYTES + 1);
        }
        var source = RulePackage.read(data);
        var parsed = new Snapshot(source.profile(), source.profile().description());
        AtomicFile file = file(context);
        var stream = file.startWrite();
        try {
            stream.write(source.archive());
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
        archive = source.archive();
        current = parsed;
        return parsed;
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "startup.zadixsource"));
    }
}
