package com.ahuamiao.adprotection;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

public final class RuleShareProvider extends ContentProvider {
    static final String NAME = "Zadix" + RulePackage.SUFFIX;

    private static File directory(Context context) { return new File(context.getCacheDir(), "rule-shares"); }

    static Uri stage(Context context, byte[] archive) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建规则包缓存");
        File[] previous = directory.listFiles();
        if (previous != null) {
            long expired = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
            for (File file : previous) {
                if (valid(file.getName()) && file.lastModified() < expired) file.delete();
            }
        }
        File file = File.createTempFile("zadix-", RulePackage.SUFFIX, directory);
        boolean complete = false;
        try {
            try (var output = new FileOutputStream(file)) {
                output.write(archive);
            }
            complete = true;
            return Uri.parse("content://" + context.getPackageName() + ".rules/" + file.getName());
        } finally {
            if (!complete) file.delete();
        }
    }

    private static boolean valid(String name) { return name != null && name.matches("zadix-[0-9]+\\.zadixsource"); }

    private File resolve(Uri uri) {
        if (uri.getPathSegments().size() != 1 || !valid(uri.getLastPathSegment())) return null;
        var file = new File(directory(getContext()), uri.getLastPathSegment());
        return file.isFile() ? file : null;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return RulePackage.MIME; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        var result = new MatrixCursor(columns);
        File file = resolve(uri);
        if (file != null) {
            var row = result.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(NAME);
                else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
                else row.add(null);
            }
        }
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
