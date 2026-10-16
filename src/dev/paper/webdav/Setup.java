package dev.paper.webdav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.nayuki.qrcodegen.QrCode;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Adds and removes rclone remotes from a phone. While the dialog is open, a small web server on the e-reader's Wi-Fi
 * address serves the setup page; the address, with a random key in it, is shown as a QR code. Logins that need a
 * browser (OAuth) happen on the phone: the provider sends the phone to rclone's local address, which the phone
 * cannot reach, so the user pastes that address back into the page and the e-reader delivers it to rclone itself.
 */
final class Setup {
    private static final Pattern AUTH = Pattern.compile("http://(127\\.0\\.0\\.1|localhost):53682/auth\\?state=[\\w-]+");
    private final Context app;
    private final ServerSocket socket;
    private final String key;
    final String address;
    private String providers;
    private Session session;

    private Setup(Context app) throws IOException {
        this.app = app;
        InetAddress wifi = wifi();
        socket = new ServerSocket(0, 8, wifi);
        byte[] random = new byte[9];
        new SecureRandom().nextBytes(random);
        key = android.util.Base64.encodeToString(random, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
        address = "http://" + wifi.getHostAddress() + ":" + socket.getLocalPort() + "/" + key + "/";
        new Thread("paper-webdav-setup") {
            public void run() {
                while (!socket.isClosed()) {
                    try {
                        final Socket client = socket.accept();
                        new Thread("paper-webdav-setup-request") { public void run() { serve(client); } }.start();
                    } catch (IOException closed) { return; }
                }
            }
        }.start();
    }

    private static InetAddress wifi() throws IOException {
        NetworkInterface wlan = NetworkInterface.getByName("wlan0");
        if (wlan != null) for (InetAddress address : Collections.list(wlan.getInetAddresses())) {
            if (address instanceof Inet4Address) return address;
        }
        throw new IOException("Wi-Fi에 연결되어 있지 않아요. Wi-Fi를 켠 뒤 다시 시도하세요.");
    }

    void close() {
        try { socket.close(); } catch (IOException ignored) { }
        new Thread("paper-webdav-rclone-cleanup") { public void run() { abandon(); } }.start();
    }

    /** Shows the QR code and instructions; the page stops working when the dialog closes. */
    static void show(final Activity activity, final Runnable closed) {
        final Setup setup;
        // rclone writes its whole config file when it saves; a running server refreshing a token could undo a change
        // made here, so it stays stopped while remotes are being edited.
        Rclone.stop(activity.getApplicationContext());
        try { setup = new Setup(activity.getApplicationContext()); }
        catch (IOException error) {
            new AlertDialog.Builder(activity).setTitle("클라우드 연결 추가").setMessage(error.getMessage()).setPositiveButton("확인", null).show();
            return;
        }
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        ImageView code = new ImageView(activity);
        code.setImageBitmap(render(QrCode.encodeText(setup.address, QrCode.Ecc.MEDIUM), Math.round(240 * activity.getResources().getDisplayMetrics().density)));
        content.addView(code, new LinearLayout.LayoutParams(-1, -2));
        TextView guide = new TextView(activity);
        guide.setTextColor(Color.BLACK);
        guide.setGravity(Gravity.START);
        guide.setText("1. 휴대폰을 같은 Wi-Fi에 연결하고 위 QR을 찍어요. QR이 안 되면 아래 주소를 입력해요.");
        content.addView(guide, new LinearLayout.LayoutParams(-1, -2));
        TextView link = new TextView(activity);
        link.setTextColor(Color.BLACK);
        link.setText(setup.address);
        link.setSingleLine(true); // a wrapped address is hard to type
        link.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(link, new LinearLayout.LayoutParams(-1, -2));
        TextView steps = new TextView(activity);
        steps.setTextColor(Color.BLACK);
        steps.setText("2. 열린 페이지에서 서비스를 고르고 정보를 입력해요.\n"
                + "3. 로그인 뒤 '연결할 수 없음' 화면이 뜨면, 그 주소를 복사해 페이지에 붙여 넣어요.\n"
                + "다 했으면 [닫기]를 눌러요.");
        content.addView(steps, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("클라우드 연결 추가 · 관리").setView(scroll)
                .setPositiveButton("닫기", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        setup.close();
                        if (closed != null) closed.run();
                    }
                }).create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    private static Bitmap render(QrCode qr, int width) {
        int modules = qr.size + 8, scale = Math.max(1, width / modules), size = modules * scale; // 4-module quiet zone
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = qr.getModule(x / scale - 4, y / scale - 4) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
    }

    // ---- HTTP ----

    private void serve(Socket client) {
        try {
            client.setSoTimeout(120000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            String[] request = line(input).split(" ");
            int length = 0;
            for (String header; !(header = line(input)).isEmpty(); ) {
                if (header.toLowerCase(Locale.US).startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
            }
            if (length > 65536) throw new IOException("too large");
            byte[] body = new byte[length];
            for (int read = 0; read < length; ) {
                int count = input.read(body, read, length - read);
                if (count < 0) break;
                read += count;
            }
            String path = request.length > 1 ? request[1] : "";
            if (path.indexOf('?') >= 0) path = path.substring(0, path.indexOf('?'));
            String prefix = "/" + key + "/";
            if (!path.startsWith(prefix)) { respond(client, 404, "text/plain", "Not found"); return; }
            String route = path.substring(prefix.length());
            JSONObject data = length > 0 ? new JSONObject(new String(body, "UTF-8")) : new JSONObject();
            if (route.isEmpty()) respond(client, 200, "text/html; charset=utf-8", page());
            else if (route.equals("providers")) respond(client, 200, "application/json", providers());
            else if (route.equals("remotes")) respond(client, 200, "application/json", remotes());
            else if (route.equals("create")) respond(client, 200, "application/json", create(data));
            else if (route.equals("answer")) respond(client, 200, "application/json", answer(data));
            else if (route.equals("code")) respond(client, 200, "application/json", code(data.getString("url")));
            else if (route.equals("delete")) respond(client, 200, "application/json", delete(data.getString("name")));
            else respond(client, 404, "text/plain", "Not found");
        } catch (Exception error) {
            try { respond(client, 200, "application/json", fail(error.getMessage() == null ? error.toString() : error.getMessage())); }
            catch (IOException ignored) { }
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int c; (c = input.read()) != -1 && c != '\n'; ) if (c != '\r') text.append((char) c);
        return text.toString();
    }

    private static void respond(Socket client, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.0 " + status + (status == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + bytes.length + "\r\nCache-Control: no-store\r\n\r\n").getBytes("UTF-8"));
        output.write(bytes);
        output.flush();
    }

    private static String fail(String message) {
        try { return new JSONObject().put("error", message).toString(); } catch (Exception e) { return "{}"; }
    }

    private static String ok() { return "{\"ok\":true}"; }

    private String page() throws IOException {
        InputStream input = Setup.class.getClassLoader().getResourceAsStream("assets/setup.html");
        if (input == null) throw new IOException("setup page missing");
        try { return Rclone.read(input); } finally { input.close(); }
    }

    // ---- rclone ----

    private synchronized String providers() throws IOException {
        if (providers == null) providers = Rclone.run(app, "config", "providers");
        return providers;
    }

    private String remotes() throws Exception {
        JSONArray list = new JSONArray();
        if (Rclone.config(app).isFile()) {
            for (String line : Rclone.run(app, "listremotes", "--long").split("\n")) {
                String[] parts = line.trim().split(":\\s*", 2);
                if (parts.length == 2) list.put(new JSONObject().put("name", parts[0]).put("type", parts[1].trim()));
            }
        }
        return list.toString();
    }

    private String delete(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]+")) return fail("이름이 올바르지 않아요.");
        Rclone.run(app, "config", "delete", name);
        return ok();
    }

    /**
     * Starts `rclone config create` in rclone's question-and-answer mode. Every question rclone asks goes to the phone
     * (see answer()), except whether to log in with a browser, which is always yes: the login happens on the phone.
     */
    private String create(JSONObject data) throws Exception {
        String name = data.getString("name"), type = data.getString("type");
        if (!name.matches("[A-Za-z0-9_.-]+")) return fail("이름은 영문, 숫자, - _ . 만 쓸 수 있어요.");
        if (!type.matches("[a-z0-9]+")) return fail("서비스 종류가 올바르지 않아요.");
        List<String> args = new ArrayList<String>(Arrays.asList("config", "create", name, type));
        JSONObject options = data.optJSONObject("options");
        if (options != null) {
            for (Iterator<?> keys = options.keys(); keys.hasNext(); ) {
                String option = (String) keys.next(), value = options.getString(option);
                if (!option.matches("[a-z0-9_]+") || value.isEmpty()) continue;
                args.add(option);
                args.add(value);
            }
        }
        args.add("--obscure");
        args.add("--non-interactive");
        abandon(); // finish removing a half-made remote first: two rclone runs would overwrite each other's config
        boolean existed = Rclone.remotes(app).contains(name);
        Session started;
        synchronized (this) { session = started = new Session(name, existed); }
        return step(started, args.toArray(new String[0]));
    }

    /** Sends the user's answer to the question rclone asked last. */
    private String answer(JSONObject data) throws Exception {
        Session current;
        synchronized (this) { current = session; }
        if (current == null || current.state == null) return fail("진행 중인 연결이 없어요. 연결 만들기부터 다시 해 주세요.");
        String value = data.optString("value");
        if (data.optBoolean("password") && !value.isEmpty()) value = Rclone.run(app, "obscure", value).trim();
        return step(current, "config", "update", current.name, "--continue", "--state", current.state, "--result", value, "--non-interactive");
    }

    /**
     * Delivers the address the phone landed on after logging in (which it cannot open: it points at rclone on this
     * device) to rclone's local login server, then carries on with whatever rclone asks next.
     */
    private String code(String pasted) throws Exception {
        Session current;
        synchronized (this) { current = session; }
        Run waiting = current == null ? null : current.run;
        if (waiting == null || waiting.finished()) return fail("로그인을 기다리는 연결이 없어요. 연결 만들기부터 다시 해 주세요.");
        int mark = pasted.indexOf('?');
        String query = mark < 0 ? "" : pasted.substring(mark + 1).trim();
        if (query.indexOf('#') >= 0) query = query.substring(0, query.indexOf('#'));
        if (query.isEmpty()) return fail("로그인한 뒤 열린 페이지의 주소 전체를 붙여 넣어 주세요.");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:53682/?" + query).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        try { connection.getResponseCode(); } finally { connection.disconnect(); }
        waiting.awaitExit(120000);
        return outcome(current);
    }

    /** Runs one rclone config step and reports what happened: a login to do, a question to answer, or done. */
    private String step(Session current, String... args) throws Exception {
        Run run = new Run(Rclone.command(app, args).start());
        current.run = run;
        String link = run.awaitLinkOrExit(60000);
        if (link != null) return new JSONObject().put("auth", redirect(link)).toString();
        return outcome(current);
    }

    private String outcome(Session current) throws Exception {
        Run run = current.run;
        if (!run.finished()) {
            run.process.destroy();
            return fail("시간이 너무 오래 걸려서 멈췄어요. 다시 시도해 주세요.");
        }
        JSONObject reply = run.reply();
        if (reply == null) return fail(run.tail());
        JSONObject option = reply.optJSONObject("Option");
        String state = reply.optString("State"), error = reply.optString("Error");
        if (option == null && state.isEmpty()) {
            if (!error.isEmpty()) return fail(error);
            synchronized (this) {
                current.done = true;
                if (session == current) session = null;
            }
            return ok();
        }
        current.state = state;
        if (option != null && "config_is_local".equals(option.optString("Name"))) {
            return step(current, "config", "update", current.name, "--continue", "--state", state, "--result", "true", "--non-interactive");
        }
        return new JSONObject().put("question", option).put("error", error).toString();
    }

    /** rclone's own login link answers with a redirect to the provider; the phone needs the provider's address. */
    private static String redirect(String link) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        try {
            String location = connection.getHeaderField("Location");
            if (location == null) throw new IOException("로그인 주소를 받지 못했어요.");
            return location;
        } finally { connection.disconnect(); }
    }

    /**
     * Drops a remote that was not finished; rclone has already written the half-made remote, so remove it. Blocks
     * until done, so call it off the UI thread.
     */
    private void abandon() {
        final Session left;
        synchronized (this) {
            left = session;
            session = null;
        }
        if (left == null || left.done) return;
        try {
            if (left.run != null) {
                left.run.process.destroy();
                left.run.process.waitFor();
            }
            if (!left.existed) Rclone.run(app, "config", "delete", left.name); // keep a remote the user was redoing
        } catch (Exception error) { Log.w("PaperWebDAV", "could not remove unfinished remote", error); }
    }

    /** The remote being made, across rclone's questions. */
    private static final class Session {
        final String name;
        final boolean existed;
        String state;
        volatile Run run;
        boolean done;

        Session(String name, boolean existed) {
            this.name = name;
            this.existed = existed;
        }
    }

    /** One rclone config command: collects its output and notices the login link it prints when it needs a browser. */
    private static final class Run {
        final Process process;
        private final StringBuilder output = new StringBuilder();
        private boolean finished;

        Run(Process process) {
            this.process = process;
            final InputStream input = process.getInputStream();
            new Thread("paper-webdav-rclone-config") {
                public void run() {
                    try {
                        Reader reader = new InputStreamReader(input, "UTF-8");
                        char[] buffer = new char[4096];
                        for (int count; (count = reader.read(buffer)) != -1; ) {
                            synchronized (Run.this) {
                                output.append(buffer, 0, count);
                                Run.this.notifyAll();
                            }
                        }
                    } catch (IOException ignored) { /* The process ended. */ }
                    try { Run.this.process.waitFor(); } catch (InterruptedException ignored) { }
                    synchronized (Run.this) {
                        finished = true;
                        Run.this.notifyAll();
                    }
                }
            }.start();
        }

        synchronized boolean finished() { return finished; }

        /** Waits until rclone prints a login link (returned) or exits (null). */
        synchronized String awaitLinkOrExit(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (true) {
                Matcher link = AUTH.matcher(output);
                if (link.find()) return link.group();
                if (finished) return null;
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                wait(left);
            }
        }

        synchronized void awaitExit(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            for (long left = millis; !finished && left > 0; left = end - System.currentTimeMillis()) wait(left);
        }

        /** The JSON rclone prints in question-and-answer mode, after any log lines. */
        synchronized JSONObject reply() {
            int start = output.charAt(0) == '{' ? 0 : output.indexOf("\n{") + 1, end = output.lastIndexOf("}");
            if (start <= 0 && output.charAt(0) != '{' || end < start) return null;
            try { return new JSONObject(output.substring(start, end + 1)); } catch (Exception e) { return null; }
        }

        /** The end of the output, which holds an error; never the whole output, which can contain tokens. */
        synchronized String tail() {
            String text = output.toString().trim();
            Log.w("PaperWebDAV", "rclone config step failed");
            return text.isEmpty() ? "rclone이 응답하지 않았어요." : text.substring(Math.max(0, text.length() - 400));
        }
    }
}
