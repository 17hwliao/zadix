package com.ahuamiao.adprotection;

import android.util.AtomicFile;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class DiskState {
    private DiskState() {}

    static byte[] read(AtomicFile file) throws IOException {
        try {
            return file.readFully();
        } catch (FileNotFoundException error) {
            if (file.getBaseFile().exists()) throw error;
            return null;
        }
    }

    static void save(AtomicFile file, byte[] content) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(content);
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch (IOException error) {
            file.failWrite(stream);
            throw error;
        }
        if (!Arrays.equals(content, file.readFully())) throw new IOException("本机记录校验失败");
    }
}
