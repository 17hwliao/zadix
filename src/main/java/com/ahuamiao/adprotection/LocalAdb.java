package com.ahuamiao.adprotection;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.AtomicFile;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

final class LocalAdb {
    static final String PAIR_NAME = "AdProtection-PKR110";
    private static final String LOOPBACK = "127.0.0.1";
    private final Context context;

    LocalAdb(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean wirelessOn() {
        return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    void pair(String code) throws Exception {
        if (!wirelessOn()) throw new IOException("请先在系统设置中打开无线调试");
        if (!code.matches("[0-9]{6}")) throw new IOException("请输入六位配对码");
        int port = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING);
        Identity identity = identity(true);
        try (AbsAdbConnectionManager manager = new AbsAdbConnectionManager() {
            @Override protected PrivateKey getPrivateKey() { return identity.key(); }
            @Override protected Certificate getCertificate() { return identity.cert(); }
            @Override protected String getDeviceName() { return PAIR_NAME; }
        }) {
            if (!manager.pair(LOOPBACK, port, code)) throw new IOException("无线调试配对未完成");
        }
    }

    Session open() throws Exception {
        if (!wirelessOn()) throw new IOException("无线调试已关闭，请在系统设置中打开");
        Identity identity = identity(false);
        int port = discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT);
        AdbConnection connection = new AdbConnection.Builder(LOOPBACK, port)
                .setApi(Build.VERSION.SDK_INT).setPrivateKey(identity.key()).setCertificate(identity.cert())
                .setDeviceName(PAIR_NAME).build();
        Session session = new Session(connection);
        try {
            session.start();
            return session;
        } catch (Exception error) {
            try { session.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private int discover(String service) throws Exception {
        Set<InetAddress> local = new HashSet<>();
        for (NetworkInterface network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            local.addAll(java.util.Collections.list(network.getInetAddresses()));
        }
        CountDownLatch found = new CountDownLatch(1);
        AtomicInteger port = new AtomicInteger();
        AdbMdns mdns = new AdbMdns(context, service, (host, candidate) -> {
            if (host != null && local.contains(host) && candidate > 0 && port.compareAndSet(0, candidate)) {
                found.countDown();
            }
        });
        mdns.start();
        try {
            if (!found.await(5, TimeUnit.SECONDS)) throw new IOException("没有发现本机无线调试端口");
            return port.get();
        } finally {
            mdns.stop();
        }
    }

    private record Identity(PrivateKey key, Certificate cert) {}

    private AtomicFile identityFile() {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity.json"));
    }

    boolean hasIdentity() throws IOException {
        return DiskState.read(identityFile()) != null;
    }

    private Identity identity(boolean create) throws Exception {
        AtomicFile file = identityFile();
        byte[] saved = DiskState.read(file);
        if (saved != null) {
            JSONObject json = new JSONObject(new String(saved, StandardCharsets.UTF_8));
            Base64.Decoder decoder = Base64.getDecoder();
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(decoder.decode(json.getString("private"))));
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(decoder.decode(json.getString("certificate"))));
            return new Identity(key, cert);
        }
        if (!create) throw new IOException("请先配对本 App 的无线调试连接");

        // Adapted from the certificate generation in the upstream Android AdbManager.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Certificate cert = selfSignedCertificate(pair);
        Base64.Encoder encoder = Base64.getEncoder();
        byte[] payload = new JSONObject().put("private", encoder.encodeToString(pair.getPrivate().getEncoded()))
                .put("certificate", encoder.encodeToString(cert.getEncoded())).toString().getBytes(StandardCharsets.UTF_8);
        DiskState.save(file, payload);
        return new Identity(pair.getPrivate(), cert);
    }

    static Certificate selfSignedCertificate(KeyPair pair) throws Exception {
        String algorithm = "SHA512withRSA";
        X500Name name = new X500Name("CN=AdProtection-PKR110");
        X509CertInfo info = new X509CertInfo();
        info.set(X509CertInfo.VERSION, new CertificateVersion(2));
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1));
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(name));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(name));
        info.set(X509CertInfo.KEY, new CertificateX509Key(pair.getPublic()));
        long now = System.currentTimeMillis();
        info.set(X509CertInfo.VALIDITY, new CertificateValidity(new Date(now - 60_000), new Date(now + 315_360_000_000L)));
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(pair.getPrivate(), algorithm);
        return cert;
    }

    record Result(int exitCode, String output) {}

    static final class Session implements AutoCloseable {
        private final AdbConnection connection;
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private AdbStream stream;
        private BufferedReader input;
        private OutputStream output;

        Session(AdbConnection connection) {
            this.connection = connection;
        }

        void start() throws Exception {
            bounded(() -> {
                if (!connection.connect(5, TimeUnit.SECONDS, true)) throw new IOException("无线 ADB 未授权或无法连接");
                stream = connection.open("exec:sh");
                input = new BufferedReader(new InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8));
                output = stream.openOutputStream();
                return null;
            });
        }

        Result run(String command) throws Exception {
            return bounded(() -> {
                // Keep one stream for the operation; a missing marker is never a successful exit.
                String marker = "AAP_" + UUID.randomUUID().toString().replace("-", "");
                String script = "(" + command + ") 2>&1; printf '\\n" + marker + " %s\\n' \"$?\"\n";
                output.write(script.getBytes(StandardCharsets.UTF_8));
                output.flush();
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.startsWith(marker + " ")) {
                        return new Result(Integer.parseInt(line.substring(marker.length() + 1)), result.toString().strip());
                    }
                    if (result.length() + line.length() > 8192) throw new IOException("系统返回的结果超出预期");
                    result.append(line).append('\n');
                }
                throw new IOException("无线 ADB 会话提前断开");
            });
        }

        private <T> T bounded(Callable<T> call) throws Exception {
            Future<T> task = io.submit(call);
            try {
                return task.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException error) {
                task.cancel(true);
                throw new IOException("无线 ADB 会话超时或中断", error);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw new IOException("无线 ADB 会话失败", error);
            }
        }

        @Override public void close() throws IOException {
            io.shutdownNow();
            try {
                if (stream != null) stream.close();
            } finally {
                connection.close();
            }
        }
    }
}
