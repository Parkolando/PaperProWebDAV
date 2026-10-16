package dev.paper.webdav;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Runs rclone (built from rclone/ in this repository) as a WebDAV server on 127.0.0.1, so the cloud tab can browse
 * every service rclone supports through the existing WebDAV client. Remotes live in rclone's own config file in the
 * Ridi app's private storage.
 */
final class Rclone {
    static final String DOWNLOAD = "https://github.com/Parkolando/PaperProCloud/releases/download/rclone-1.75.2-android/rclone-armv7.gz";
    /** SHA-256 of the uncompressed binary; a download or copied file must match it. */
    static final String SHA256 = "1af1b716636050316eab94db45728f9d2f822bc2f5069435a014348df51654e4";
    static final int PORT = 35353;
    static final String USER = "paper";

    private static Process server;
    private static String served; // remote list the running server was started with

    static File home(Context app) { return new File(app.getFilesDir(), "rclone"); }
    static File binary(Context app) { return new File(home(app), "rclone"); }
    static File config(Context app) { return new File(home(app), "rclone.conf"); }

    /** Installed, and the same build this module expects; a module update with a newer rclone downloads it again. */
    static boolean installed(Context app) {
        return binary(app).canExecute() && SHA256.equals(prefs(app).getString("rclone-sha", ""));
    }

    private static android.content.SharedPreferences prefs(Context app) {
        return app.getSharedPreferences("paper_webdav", Context.MODE_PRIVATE);
    }

    /** A copy the user put on the device's storage, used instead of downloading. */
    static File offline() {
        File folder = new File(Environment.getExternalStorageDirectory(), "PaperProCloud");
        File plain = new File(folder, "rclone");
        return plain.isFile() ? plain : new File(folder, "rclone-armv7.gz");
    }

    /** Installs the binary from storage or downloads it, refusing anything whose checksum differs from SHA256. */
    static void install(Context app, DavClient.Progress progress) throws IOException {
        File home = home(app), part = new File(home, "rclone.part"), packed = new File(home, "rclone.gz");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("rclone 폴더를 만들 수 없습니다.");
        File source = offline();
        if (!source.isFile()) {
            DavClient.fetch(DOWNLOAD, packed, progress);
            source = packed;
        }
        try {
            InputStream input = new FileInputStream(source);
            OutputStream output = new FileOutputStream(part);
            try {
                if (source.getName().endsWith(".gz")) input = new GZIPInputStream(input, 65536);
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            } finally { output.close(); input.close(); }
        } finally { packed.delete(); }
        if (!SHA256.equals(sha256(part))) { part.delete(); throw new IOException("rclone 파일이 원본과 다릅니다. 다시 받아 주세요."); }
        File binary = binary(app);
        if (binary.exists() && !binary.delete() || !part.renameTo(binary) || !binary.setExecutable(true, true)) throw new IOException("rclone을 설치할 수 없습니다.");
        prefs(app).edit().putString("rclone-sha", SHA256).commit();
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream input = new FileInputStream(file);
            try {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
            } finally { input.close(); }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    /** A process running rclone with the environment it needs on this Android version. */
    static ProcessBuilder command(Context app, String... args) {
        List<String> line = new ArrayList<String>();
        line.add(binary(app).getAbsolutePath());
        line.addAll(Arrays.asList(args));
        line.add("--config");
        line.add(config(app).getAbsolutePath());
        line.add("--cache-dir");
        line.add(new File(home(app), "cache").getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(line).directory(home(app)).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("HOME", home(app).getAbsolutePath());
        environment.put("SSL_CERT_DIR", "/system/etc/security/cacerts"); // Go does not look there by itself
        environment.put("RCLONE_ANDROID_DNS", dns()); // read by our build: Android has no /etc/resolv.conf
        return builder;
    }

    private static String dns() {
        try {
            Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "net.dns1");
            return value == null ? "" : value.toString();
        } catch (Exception e) { return ""; }
    }

    /** Runs a short rclone command and returns its output, or throws with that output when it fails. */
    static String run(Context app, String... args) throws IOException {
        Process process = command(app, args).start();
        try {
            String output = read(process.getInputStream());
            if (process.waitFor() != 0) throw new IOException(output.trim().isEmpty() ? "rclone 실행에 실패했습니다." : output.trim());
            return output;
        } catch (InterruptedException e) { process.destroy(); throw new InterruptedIOException(); }
    }

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
        return bytes.toString("UTF-8");
    }

    /** Names of the configured remotes. Only simple names are served, since they become folder names. */
    static List<String> remotes(Context app) throws IOException {
        List<String> names = new ArrayList<String>();
        if (!config(app).isFile()) return names;
        for (String line : run(app, "listremotes").split("\n")) {
            String name = line.trim();
            if (name.endsWith(":")) name = name.substring(0, name.length() - 1);
            if (name.matches("[A-Za-z0-9_.-]+")) names.add(name);
        }
        return names;
    }

    /**
     * Starts (or keeps) the local WebDAV server for all remotes and waits until it answers. Blocks for a few seconds
     * on the first call, so call it off the UI thread.
     */
    static synchronized void start(Context app, String password) throws IOException {
        if (!installed(app)) throw new IOException("rclone이 설치되어 있지 않아요. 설정 > 파일 관리 > 클라우드 설정에서 설치하세요.");
        List<String> remotes = remotes(app);
        if (remotes.isEmpty()) throw new IOException("rclone 연결이 없습니다. 설정 > 파일 관리 > 클라우드 설정에서 연결을 추가하세요.");
        StringBuilder upstreams = new StringBuilder();
        for (String name : remotes) upstreams.append(upstreams.length() == 0 ? "" : " ").append(name).append('=').append(name).append(':');
        if (server != null && upstreams.toString().equals(served) && alive(server) && listening()) return;
        stop(app);
        // `exec` keeps the pid of the shell, which the first output line reports, so a server left behind by a killed
        // app can be stopped next time.
        ProcessBuilder builder = command(app, "serve", "webdav", ":combine,upstreams='" + upstreams + "':",
                "--addr", "127.0.0.1:" + PORT, "--user", USER, "--pass", password, "--read-only");
        List<String> line = new ArrayList<String>(builder.command());
        StringBuilder script = new StringBuilder("echo $$; exec");
        for (String part : line) script.append(" '").append(part.replace("'", "'\\''")).append('\'');
        builder.command("/system/bin/sh", "-c", script.toString());
        final Process process = builder.start();
        final BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        String pid = output.readLine();
        if (pid != null && pid.trim().matches("\\d+")) remember(app, Integer.parseInt(pid.trim()));
        new Thread("paper-webdav-rclone") {
            public void run() {
                try { for (String text; (text = output.readLine()) != null; ) Log.i("PaperWebDAV", "rclone: " + text); }
                catch (IOException ignored) { /* The process ended. */ }
            }
        }.start();
        server = process;
        served = upstreams.toString();
        for (int i = 0; i < 100; i++) { // up to 30 s; the 70 MB binary takes a few seconds to start on this device
            if (listening()) return;
            if (!alive(process)) break;
            try { Thread.sleep(300); } catch (InterruptedException e) { throw new InterruptedIOException(); }
        }
        stop(app);
        throw new IOException("rclone을 시작하지 못했습니다.");
    }

    static synchronized void stop(Context app) {
        if (server != null) server.destroy();
        server = null;
        served = null;
        int stale = prefs(app).getInt("rclone-pid", 0);
        if (stale > 0 && new File("/proc/" + stale + "/cmdline").isFile() && cmdline(stale).contains("rclone")) android.os.Process.killProcess(stale);
        remember(app, 0);
    }

    private static void remember(Context app, int pid) {
        prefs(app).edit().putInt("rclone-pid", pid).commit();
    }

    private static String cmdline(int pid) {
        try { return read(new FileInputStream("/proc/" + pid + "/cmdline")); } catch (IOException e) { return ""; }
    }

    private static boolean alive(Process process) {
        try { process.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    private static boolean listening() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (IOException e) { return false; }
        finally { try { socket.close(); } catch (IOException ignored) { } }
    }
}
