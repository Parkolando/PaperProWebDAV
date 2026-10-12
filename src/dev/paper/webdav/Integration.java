package dev.paper.webdav;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.*;
import android.security.KeyPairGeneratorSpec;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.ridi.books.viewer.common.library.Library;
import com.ridi.books.viewer.common.library.book.download.DownloadTask;
import com.ridi.books.viewer.common.library.models.Book;
import com.ridi.books.viewer.common.library.models.BookGroup;
import com.ridi.books.viewer.common.library.models.UserShelf;
import com.ridi.books.viewer.main.view.BookDownloadProgressView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;
import okhttp3.HttpUrl;

public final class Integration {
    private static Context app;
    private static final Map<Activity, WeakReference<Browser>> browsers = new WeakHashMap<Activity, WeakReference<Browser>>();

    public static void init(Context context) {
        app = context.getApplicationContext();
        if (app == null) app = context;
        new Thread("paper-webdav-key") { // key generation takes seconds on this device; have it ready before the first save
            public void run() {
                try {
                    String plain = prefs().getString("password", null);
                    String sealed = seal(plain == null ? "" : plain);
                    if (plain != null && prefs().contains("password")) prefs().edit().putString("secret", sealed).remove("password").commit(); // saved before 0.3.0
                    android.util.Log.i("PaperWebDAV", prefs().contains("password") ? "password is stored unencrypted" : "password storage is encrypted");
                    password(); // decrypt now so the first folder request does not wait for it on the UI thread
                } catch (Exception error) { android.util.Log.w("PaperWebDAV", "password encryption unavailable", error); }
            }
        }.start();
    }

    private static final String KEY = "paper-webdav";
    private static volatile String password; // decrypted copy, kept only in memory

    /** The keystore holding this module's RSA key pair. The private key cannot be exported from it. */
    private static synchronized KeyStore keys() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 50);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(app).setAlias(KEY).setSubject(new X500Principal("CN=" + KEY))
                    .setSerialNumber(BigInteger.ONE).setStartDate(new Date()).setEndDate(end.getTime()).build());
            generator.generateKeyPair();
        }
        return store;
    }

    /**
     * Encrypts the password for the settings file. The file alone no longer reveals it, since the key stays in the
     * Android keystore; software that has root or runs as the app itself can still ask the keystore to decrypt.
     */
    private static String seal(String value) throws Exception {
        KeyStore store = keys();
        if (value.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(KEY).getPublicKey());
        return android.util.Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), android.util.Base64.NO_WRAP);
    }

    private static String password() {
        String known = password;
        if (known != null) return known;
        String sealed = prefs().getString("secret", null);
        if (sealed == null) return prefs().getString("password", ""); // not converted yet
        known = "";
        if (!sealed.isEmpty()) try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, (PrivateKey) keys().getKey(KEY, null));
            known = new String(cipher.doFinal(android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)), "UTF-8");
        } catch (Exception error) { android.util.Log.w("PaperWebDAV", "stored password cannot be read; enter it again", error); }
        return password = known;
    }
    private static SharedPreferences prefs() { return app.getSharedPreferences("paper_webdav", Context.MODE_PRIVATE); }
    private static int resource(Context context, String type, String name) {
        int id = context.getResources().getIdentifier(name, type, "com.ridi.paper");
        if (id == 0) throw new IllegalStateException("Missing Ridi resource: " + name);
        return id;
    }
    private static View view(View root, String name) { return root.findViewById(resource(root.getContext(), "id", name)); }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static Browser browser(Activity activity) {
        WeakReference<Browser> reference = browsers.get(activity);
        return reference == null ? null : reference.get();
    }

    public static void attach(Activity activity) throws Exception {
        if (browser(activity) != null) return;
        Browser browser = new Browser(activity);
        browsers.put(activity, new WeakReference<Browser>(browser));
        wipe(storage()); // nothing is downloading yet; leftovers are from a killed run
        wipe(new File(app.getCacheDir(), "webdav")); // where downloads went before 0.3.0
        try { repair(browser.library, activity.getClassLoader()); }
        catch (Exception error) { android.util.Log.w("PaperWebDAV", "metadata repair failed", error); }
    }

    private static String read(File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
        } finally { input.close(); }
        return bytes.toString("UTF-8");
    }

    /** Volume number from an imported EPUB's own series metadata, cached per book; NaN for other formats or when absent. */
    private static float seriesIndex(String key, Book book) {
        String saved = prefs().getString("index:" + key, null);
        if (saved == null) {
            saved = "";
            try {
                Matcher path = Pattern.compile("full-path=\"([^\"]+)\"").matcher(read(new File(book.z0(), "META-INF/container.xml")));
                if (path.find()) saved = Epub.seriesIndex(read(new File(book.z0(), path.group(1))));
            } catch (Exception ignored) { /* Not an EPUB. */ }
            prefs().edit().putString("index:" + key, saved).apply();
        }
        return saved.isEmpty() ? Float.NaN : Float.parseFloat(saved);
    }

    private static void wipe(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) wipe(child);
        file.delete();
    }

    /** One-time: EPUBs imported before the parser fixes carry their collection title (e.g. 일반책) or no cover. */
    private static void repair(Library library, ClassLoader loader) throws Exception {
        if (prefs().getBoolean("metadata-repaired-2", false)) return;
        Method parse = loader.loadClass("d.k.a.c.i0.f3.n4.a").getMethod("c", File.class);
        Object realm = Library.class.getField("c").get(library);
        for (Map.Entry<String, ?> saved : prefs().getAll().entrySet()) {
            if (!saved.getKey().startsWith("book:")) continue;
            Book book = library.t((String) saved.getValue());
            if (book == null || !new File(book.z0(), "META-INF/container.xml").isFile()) continue;
            Object metadata = parse.invoke(null, book.z0());
            String title = (String) metadata.getClass().getField("title").get(metadata);
            if (title != null && !title.trim().isEmpty() && !title.equals(book.a())) {
                call(realm, "c", new Class<?>[0]);
                book.y1(title);
                call(realm, "i", new Class<?>[0]);
            }
            File cover = (File) metadata.getClass().getField("coverFile").get(metadata);
            if (cover == null || !cover.isFile() || book.w0().exists()) continue;
            Bitmap bitmap = (Bitmap) loader.loadClass("d.k.a.c.e0.w1.n.j").getMethod("a", File.class).invoke(null, cover); // Ridi's cover scaler
            if (bitmap == null) continue;
            book.w0().getParentFile().mkdirs();
            OutputStream output = new FileOutputStream(book.w0());
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            finally { output.close(); }
        }
        prefs().edit().putBoolean("metadata-repaired-2", true).remove("metadata-repaired").apply();
    }

    public static boolean back(Activity activity) {
        Browser browser = browser(activity);
        if (browser == null || !browser.visible()) return false;
        if (browser.folder != null && !browser.folder.equals(browser.root)) {
            HttpUrl parent = browser.folder.resolve("../");
            if (parent != null && DavClient.inside(browser.root, parent)) browser.load(parent);
            else browser.hide();
        } else browser.hide();
        return true;
    }

    public static void tabClicked(Activity activity, View clicked) {
        Browser browser = browser(activity);
        if (browser == null) return;
        for (String name : new String[]{"title_library", "title_purchased", "title_store", "title_ridiselect"}) {
            if (clicked.getId() == resource(activity, "id", name)) browser.hide();
        }
    }

    public static void destroy(Activity activity) {
        Browser browser = browser(activity);
        if (browser != null) {
            browser.cancel();
            browser.stop(null);
            browser.worker.shutdownNow();
            browser.downloads.shutdownNow();
        }
        browsers.remove(activity);
    }

    /** Shows the module's settings entries on the tab they belong to: WebDAV on top of 파일 관리, reboot at the end of 전원/보안. */
    public static void settings(ListView list, String category) {
        LinearLayout panel = (LinearLayout) list.getParent();
        final Context context = list.getContext();
        View webdav = panel.findViewWithTag("paper-webdav-settings");
        if (webdav == null) {
            webdav = row(context, "WebDAV 설정", "서버 주소와 계정", "main_actionbar_icon_settings", new View.OnClickListener() {
                public void onClick(View v) { editSettings(context, null); }
            });
            webdav.setTag("paper-webdav-settings");
            panel.addView(webdav, panel.indexOfChild(list), new LinearLayout.LayoutParams(-1, -2));
        }
        webdav.setVisibility("FILE_MANAGEMENT_SETTINGS".equals(category) ? View.VISIBLE : View.GONE);
        powerSettings(list, "POWER_SECURITY_SETTINGS".equals(category));
    }

    /** A row in Ridi's own settings item layout. */
    private static View row(Context context, String title, String description, String icon, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LayoutInflater.from(context).inflate(resource(context, "layout", "setting_item"), row, true);
        ((TextView) view(row, "title")).setText(title);
        ((TextView) view(row, "description")).setText(description);
        ((ImageView) view(row, "icon")).setImageResource(resource(context, "drawable", icon));
        row.setOnClickListener(action);
        return row;
    }

    /** Reboot entries at the end of the 전원/보안 settings list. The Ridi app runs as the system user, so it may reboot the device. */
    private static void powerSettings(ListView list, boolean shown) {
        int key = resource(list.getContext(), "id", "settings_list");
        View rows = (View) list.getTag(key);
        if (rows == null) {
            if (!shown) return;
            Context context = list.getContext();
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(reboot(context, "리커버리로 재부팅", "리커버리 모드로 다시 시작합니다.", "recovery"));
            // This device (NTX bootloader) enters fastboot on the reason "fastboot", as `adb reboot fastboot` does.
            layout.addView(reboot(context, "fastboot 모드로 재부팅", "PC의 fastboot 명령을 받는 모드로 다시 시작합니다.", "fastboot"));
            list.setTag(key, rows = layout);
        }
        if (!shown) list.removeFooterView(rows);
        else if (list.getFooterViewsCount() == 0) list.addFooterView(rows, null, false);
    }

    private static View reboot(final Context context, final String title, String description, final String reason) {
        return row(context, title, description, "main_setting_icon_shutdown", new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(context).setTitle(title).setMessage("기기를 지금 다시 시작할까요?").setNegativeButton("취소", null)
                        .setPositiveButton("재부팅", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).reboot(reason);
                            }
                        }).show();
            }
        });
    }

    private static void editSettings(final Context context, final Runnable saved) {
        final LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        fields.setPadding(padding, padding, padding, padding);
        final EditText url = field(context, fields, "폴더 주소 (https://, 내부망은 http://도 가능)", prefs().getString("url", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = field(context, fields, "사용자 이름", prefs().getString("user", ""), InputType.TYPE_CLASS_TEXT);
        final EditText password = field(context, fields, "비밀번호", password(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        final AlertDialog dialog = new AlertDialog.Builder(context).setTitle("WebDAV 설정").setView(scroll)
                .setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            HttpUrl checked = DavClient.rootUrl(url.getText().toString());
                            if (user.getText().toString().contains(":")) throw new IOException("사용자 이름에는 :을 사용할 수 없습니다.");
                            String entered = password.getText().toString(), sealed;
                            try { sealed = seal(entered); }
                            catch (Exception error) { throw new IOException("비밀번호를 암호화해 저장할 수 없습니다. 245바이트보다 짧은지 확인하세요."); }
                            if (!prefs().edit().putString("url", checked.toString()).putString("user", user.getText().toString())
                                    .putString("secret", sealed).remove("password").commit()) throw new IOException("설정을 저장할 수 없습니다.");
                            Integration.password = entered;
                            for (WeakReference<Browser> reference : browsers.values()) {
                                Browser browser = reference.get();
                                if (browser != null) { browser.cancel(); browser.stop(null); browser.folder = null; browser.hide(); }
                            }
                            dialog.dismiss();
                            if (saved != null) saved.run();
                        } catch (IOException error) { url.setError(error.getMessage()); }
                    }
                });
            }
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private static EditText field(Context context, LinearLayout parent, String label, String value, int input) {
        TextView title = new TextView(context);
        title.setText(label);
        parent.addView(title);
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(input);
        field.setText(value);
        field.setContentDescription(label);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    /**
     * Where downloads are written: a folder inside Ridi's book store, on whichever volume (internal or SD card) the
     * user chose for books. Being on the same volume lets the importer take the file by renaming it.
     */
    private static File storage() {
        try {
            Object ridi = app.getClassLoader().loadClass("com.ridi.books.viewer.RidibooksApp").getField("b").get(null);
            return new File((File) call(ridi, "c", new Class<?>[0]), ".webdav");
        } catch (Exception error) { return new File(app.getCacheDir(), "webdav"); }
    }

    private static boolean download(File file) throws IOException {
        File folder = file.getCanonicalFile().getParentFile();
        return folder != null && storage().getCanonicalFile().equals(folder.getParentFile()) && folder.getName().matches("[a-f0-9]{64}");
    }

    public static void imported(String path, String id) throws IOException {
        if (download(new File(path))) prefs().edit().putString("book:" + new File(path).getCanonicalFile().getParentFile().getName(), id).commit();
    }

    /** Hands a finished download to Ridi's importer by renaming it, which is instant, instead of copying the whole file. */
    public static boolean move(File source, File target) throws IOException {
        if (!download(source) || !source.renameTo(target)) return false;
        android.util.Log.d("PaperWebDAV", "download moved into the book store without copying");
        return true;
    }

    private static final class Browser {
        final Activity activity;
        final Library library;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final ExecutorService downloads = Executors.newSingleThreadExecutor();
        /** Entry URL -> percent, or -1 while waiting. */
        final ConcurrentHashMap<String, Integer> queue = new ConcurrentHashMap<String, Integer>();
        final Map<String, String> errors = new HashMap<String, String>();
        final LinearLayout panel;
        final TextView tab, status, path, all;
        final int progressId, errorId;
        final GridView grid;
        final View pager;
        final ViewGroup tabs;
        final List<Book> books = new ArrayList<Book>();
        final BooksAdapter adapter;
        List<DavClient.Entry> entries = Collections.emptyList();
        HttpUrl root, folder;
        DavClient client;
        boolean stale; // books were imported but Ridi's own shelves have not been told yet
        volatile DavClient downloader;
        volatile String active;
        String openWhenDone;
        int generation;

        Browser(final Activity activity) throws Exception {
            this.activity = activity;
            library = (Library) call(activity, "N", new Class<?>[0]);
            View decor = activity.getWindow().getDecorView();
            tabs = (ViewGroup) view(decor, "main_title_tab_container");
            FrameLayout temporary = new FrameLayout(activity);
            LayoutInflater.from(activity).inflate(resource(activity, "layout", "main_navigation_bar"), temporary, true);
            tab = (TextView) view(temporary, "title_ridiselect");
            ((ViewGroup) tab.getParent()).removeView(tab);
            tab.setId(View.NO_ID);
            tab.setText("WebDAV");
            tab.setContentDescription("WebDAV 서버 서재");
            tabs.addView(tab);
            panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(Color.WHITE);
            panel.setVisibility(View.GONE);
            LinearLayout toolbar = new LinearLayout(activity);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
            TextView up = button(toolbar, "상위", new View.OnClickListener() { public void onClick(View v) { back(activity); } });
            up.setContentDescription("상위 폴더로 이동");
            path = new TextView(activity);
            path.setTextColor(Color.BLACK);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            toolbar.addView(path, new LinearLayout.LayoutParams(0, -2, 1));
            all = button(toolbar, "전체 다운로드", new View.OnClickListener() { public void onClick(View v) { downloadAll(); } });
            all.setVisibility(View.GONE);
            button(toolbar, "새로고침", new View.OnClickListener() { public void onClick(View v) { if (folder != null) load(folder); } });
            button(toolbar, "설정", new View.OnClickListener() { public void onClick(View v) { editSettings(activity, new Runnable() { public void run() { show(); } }); } });
            status = new TextView(activity);
            status.setTextColor(Color.BLACK);
            status.setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 4));
            panel.addView(status, new LinearLayout.LayoutParams(-1, -2));
            grid = (GridView) LayoutInflater.from(activity).inflate(resource(activity, "layout", "shelf_page"), panel, false);
            progressId = resource(activity, "id", "download_progress");
            errorId = resource(activity, "id", "download_error");
            adapter = new BooksAdapter(activity, library, books) {
                void decorate(int position, View item) { Browser.this.decorate(position, item); }
            };
            grid.setAdapter(adapter);
            call(grid, "b", new Class<?>[]{int.class, int.class, int.class, int.class, int.class}, 0, dp(activity, 8), dp(activity, 8), dp(activity, 8), 0);
            panel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View v, int position, long id) { select(entries.get(position)); }
            });
            pager = view(decor, "main_pager");
            ((FrameLayout) view(decor, "main_pager_container")).addView(panel, new FrameLayout.LayoutParams(-1, -1));
            tab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { show(); } });
        }

        TextView button(LinearLayout parent, String text, View.OnClickListener listener) {
            TextView button = new TextView(activity);
            button.setText(text);
            button.setTextColor(Color.BLACK);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
            button.setOnClickListener(listener);
            parent.addView(button, new LinearLayout.LayoutParams(-2, -1));
            return button;
        }

        boolean visible() { return panel.getVisibility() == View.VISIBLE; }

        void show() {
            if (prefs().getString("url", "").isEmpty()) {
                editSettings(activity, new Runnable() { public void run() { show(); } });
                return;
            }
            for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setSelected(false);
            tab.setSelected(true);
            pager.setVisibility(View.INVISIBLE);
            panel.setVisibility(View.VISIBLE);
            try {
                root = DavClient.rootUrl(prefs().getString("url", ""));
                load(folder != null && DavClient.inside(root, folder) ? folder : root);
            } catch (IOException error) { status.setText(error.getMessage()); }
        }

        void hide() {
            panel.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            tab.setSelected(false);
            if (stale) { stale = false; announce(); }
            try {
                int current = (Integer) call(pager, "getCurrentItem", new Class<?>[0]);
                for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setSelected(i == current);
            } catch (Exception ignored) { /* The original tab listener also restores selection. */ }
        }

        /** Cancels the folder request; downloads keep running. */
        void cancel() {
            generation++;
            if (client != null) client.cancel();
        }

        DavClient newClient() throws IOException {
            return new DavClient(prefs().getString("url", ""), prefs().getString("user", ""), password());
        }

        void load(final HttpUrl target) {
            cancel();
            final int ticket = generation;
            final DavClient request;
            try { client = request = newClient(); }
            catch (IOException error) { status.setText(error.getMessage()); return; }
            folder = target;
            path.setText(android.net.Uri.parse(target.toString()).getPath());
            status.setText("폴더를 불러오는 중입니다...");
            entries = Collections.emptyList();
            render();
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        final List<DavClient.Entry> result = request.list(target);
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                if (ticket != generation || activity.isFinishing()) return;
                                entries = result;
                                if (group(target, result)) announce(); // also picks up books imported before grouping existed
                                render();
                                status.setText(result.isEmpty() ? "표시할 폴더나 책이 없습니다." : "폴더·책 " + result.size() + "개 · 책을 누르면 다운로드 후 열립니다.");
                                progress();
                            }
                        });
                    } catch (final IOException error) { failed(ticket, error); }
                }
            });
        }

        /** Rebuilds the grid for the current folder listing. */
        void render() {
            books.clear();
            for (DavClient.Entry entry : entries) {
                Book book = entry.directory ? null : local(entry);
                if (book == null) {
                    book = new Book();
                    book.U0("webdav-" + entry.key);
                    book.y1((entry.directory ? "폴더\n" : "") + entry.name);
                    book.b1(64);
                    book.Y0(2);
                }
                books.add(book);
            }
            adapter.notifyDataSetChanged();
            toolbar();
        }

        /** The imported Ridi book for a remote file, or null when it is not on the device. */
        Book local(DavClient.Entry entry) {
            String id = prefs().getString("book:" + entry.key, null);
            return id == null ? null : library.t(id);
        }

        /** Whether the listed item is a book file that has not been imported (the grid shows a placeholder for it). */
        boolean remote(int position) {
            return !entries.get(position).directory && books.get(position).v().startsWith("webdav-");
        }

        /** Offers "download all" while the folder has books to fetch, and "cancel all" while anything is queued. */
        void toolbar() {
            boolean missing = false;
            for (int i = 0; i < entries.size() && !missing; i++) missing = remote(i);
            all.setVisibility(queue.isEmpty() && !missing ? View.GONE : View.VISIBLE);
            all.setText(queue.isEmpty() ? "전체 다운로드" : "전체 취소");
        }

        /** Drives Ridi's own per-book download indicator from the WebDAV queue. */
        void decorate(int position, View item) {
            if (position >= entries.size()) return;
            DavClient.Entry entry = entries.get(position);
            BookDownloadProgressView indicator = (BookDownloadProgressView) item.findViewById(progressId);
            TextView error = (TextView) item.findViewById(errorId);
            Integer percent = queue.get(entry.url.toString());
            String failure = percent == null ? errors.get(entry.url.toString()) : null;
            if (entry.directory) indicator.setVisibility(View.GONE);
            else if (percent != null || failure != null || remote(position)) {
                indicator.setVisibility(View.VISIBLE);
                indicator.setGrouped(false);
                indicator.setProgress(percent == null ? 0 : Math.max(percent, 0));
                indicator.setState(percent != null ? (percent < 0 ? DownloadTask.State.WAITING : DownloadTask.State.DOWNLOADING)
                        : failure != null ? DownloadTask.State.FAILED : DownloadTask.State.STOPPED);
            }
            error.setText(failure);
            error.setVisibility(failure == null ? View.GONE : View.VISIBLE);
        }

        /** Updates the download indicators on screen without rebinding covers: one book, or all of them when url is null. */
        void redraw(String url) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                int position = grid.getFirstVisiblePosition() + i;
                if (position < entries.size() && (url == null || entries.get(position).url.toString().equals(url))) decorate(position, grid.getChildAt(i));
            }
            toolbar();
        }

        void progress() {
            if (!queue.isEmpty()) status.setText("다운로드 중 · 남은 책 " + queue.size() + "권 · 받는 중인 책을 누르면 취소합니다.");
        }

        void downloadAll() {
            if (!queue.isEmpty()) { stop(null); status.setText("다운로드를 취소했습니다."); redraw(null); return; }
            openWhenDone = null;
            for (int i = 0; i < entries.size(); i++) if (remote(i)) enqueue(entries.get(i));
            redraw(null);
        }

        void enqueue(final DavClient.Entry entry) {
            final String url = entry.url.toString();
            if (queue.putIfAbsent(url, -1) != null) return;
            errors.remove(url);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            progress();
            final HttpUrl home = folder;
            final List<DavClient.Entry> listing = entries;
            downloads.execute(new Runnable() { public void run() { transfer(entry, url, home, listing); } });
        }

        /** Cancels one queued or running download, or all of them when url is null. */
        void stop(String url) {
            synchronized (queue) {
                if (url == null) queue.clear(); else queue.remove(url);
                if (downloader != null && (url == null || url.equals(active))) downloader.cancel();
            }
        }

        /** Download worker: fetches one file and hands it to Ridi's own importer on this thread. */
        void transfer(DavClient.Entry entry, final String url, final HttpUrl home, final List<DavClient.Entry> listing) {
            String failure = null, detail = null;
            try {
                DavClient request = newClient();
                synchronized (queue) {
                    if (!queue.containsKey(url)) throw new InterruptedIOException(); // cancelled while waiting
                    downloader = request;
                    active = url;
                }
                final Runnable step = new Runnable() { public void run() { redraw(url); } };
                File file = request.download(entry, storage(), new DavClient.Progress() {
                    int shown = -1;
                    public void update(long done, long total) {
                        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                        if (shown >= 0 && percent / 5 == shown / 5) return; // e-ink: redraw in 5% steps
                        shown = percent;
                        if (queue.replace(url, percent) != null) activity.runOnUiThread(step);
                    }
                });
                try {
                    ClassLoader loader = activity.getClassLoader();
                    Object lazy = loader.loadClass("com.ridi.books.viewer.RidibooksApp").getField("d").get(null);
                    Object configuration = loader.loadClass("i.c").getMethod("getValue").invoke(lazy);
                    Object task = loader.loadClass("d.k.a.c.e0.w1.n.h").getConstructors()[0]
                            .newInstance(configuration, activity, Collections.singletonList(file.getAbsolutePath()));
                    Object count = task.getClass().getMethod("doInBackground", Object[].class).invoke(task, (Object) new Library[0]);
                    if (!Integer.valueOf(1).equals(count)) { failure = "가져오기 실패"; detail = "책 가져오기에 실패했습니다. 파일 형식을 확인하세요: " + entry.name; }
                } finally { wipe(file.getParentFile()); }
            } catch (Exception error) {
                if (queue.containsKey(url)) { // otherwise the user cancelled it
                    android.util.Log.w("PaperWebDAV", "download failed", error);
                    failure = "다운로드 실패";
                    detail = error instanceof javax.net.ssl.SSLException ? "HTTPS 인증서 또는 TLS 연결 오류입니다."
                            : error instanceof IOException && error.getMessage() != null ? error.getMessage() : "책을 받지 못했습니다: " + entry.name;
                }
            }
            final String shown = failure, message = detail;
            activity.runOnUiThread(new Runnable() { public void run() { finished(url, shown, message, home, listing); } });
        }

        void finished(String url, String failure, String detail, HttpUrl home, List<DavClient.Entry> listing) {
            if (activity.isFinishing()) return;
            queue.remove(url);
            String id = prefs().getString("book:" + DavClient.key(url), null);
            Book book = failure == null && id != null ? library.t(id) : null;
            if (failure != null) errors.put(url, failure);
            if (book != null) {
                group(home, listing);
                stale = true;
            }
            if (stale && (queue.isEmpty() || !visible())) { // while this tab covers the shelves, refresh them once per batch
                stale = false;
                announce();
            }
            if (listing != entries) {
                if (home.equals(folder)) render(); // the same folder was reloaded meanwhile
            } else for (int i = 0; i < entries.size(); i++) { // rebind only this book instead of the whole grid
                if (!entries.get(i).url.toString().equals(url)) continue;
                if (book != null) books.set(i, book);
                int child = i - grid.getFirstVisiblePosition();
                if (child >= 0 && child < grid.getChildCount()) adapter.getView(i, grid.getChildAt(child), grid);
                break;
            }
            toolbar();
            if (detail != null) status.setText(detail);
            else if (!queue.isEmpty()) progress();
            else if (book != null) status.setText(errors.isEmpty() ? "다운로드를 마쳤습니다." : "받지 못한 책이 있습니다. 책을 눌러 다시 시도하세요.");
            if (queue.isEmpty()) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (book != null && url.equals(openWhenDone) && visible()) open(id);
        }

        /** Posts the refresh events Ridi's importer posts after adding books. */
        void announce() {
            try {
                ClassLoader loader = activity.getClassLoader();
                Method post = loader.loadClass("d.k.a.b.a").getMethod("f", Object.class);
                post.invoke(null, loader.loadClass("d.k.a.c.e0.r0").newInstance());
                post.invoke(null, loader.loadClass("d.k.a.c.e0.y0").newInstance());
            } catch (Exception ignored) { /* The shelves also follow the database. */ }
        }

        /**
         * Stores the imported books of one server folder the way Ridi stores a purchased series: every volume belongs
         * to one BookGroup and only the last volume keeps a place on a shelf, so the series takes a single slot.
         * Volumes are ordered by EPUB series metadata when present, otherwise by file name. Returns whether anything changed.
         */
        boolean group(HttpUrl home, List<DavClient.Entry> listing) {
            TreeMap<Double, Book> sorted = new TreeMap<Double, Book>(); // series position first, then listing order
            for (int position = 0; position < listing.size(); position++) {
                DavClient.Entry entry = listing.get(position);
                Book book = entry.directory ? null : local(entry);
                if (book == null) continue;
                float index = seriesIndex(entry.key, book);
                sorted.put((Float.isNaN(index) ? 1e6 : index) + position * 1e-6, book);
            }
            final List<Book> volumes = new ArrayList<Book>(sorted.values());
            if (volumes.size() < 2) return false;
            final String reference = DavClient.key(home.toString()), key = "webdav:" + reference;
            List<String> segments = home.pathSegments();
            final String title = segments.size() > 1 ? segments.get(segments.size() - 2) : home.host();
            BookGroup current = library.w(key);
            boolean changed = current == null;
            for (int i = 0; i < volumes.size(); i++) {
                Book book = volumes.get(i);
                changed |= book.C() == null || !key.equals(book.C().F()) || book.n() != i + 1;
            }
            if (!changed) {
                int shelved = 0;
                for (Book book : current.v0()) if (book.Q() != null) shelved++;
                if (shelved == 1) return false; // already stored; avoid a needless shelf redraw
            }
            library.f(new i.r.a.a<i.m>() {
                public i.m invoke() {
                    BookGroup group = library.w(key);
                    if (group == null) {
                        try {
                            Object realm = Library.class.getField("c").get(library);
                            group = (BookGroup) call(realm, "c0", new Class<?>[]{Class.class, Object.class}, BookGroup.class, key);
                        } catch (Exception error) { throw new IllegalStateException(error); }
                        group.B0("webdav");
                        group.y0(reference);
                    }
                    group.A0(title);
                    group.C0("권");
                    Book holder = null; // the volume already holding this series' shelf slot keeps it, so the series stays where the user put it
                    for (Book book : group.v0()) if (holder == null && book.Q() != null) holder = book;
                    for (int i = 0; i < volumes.size(); i++) {
                        Book book = volumes.get(i);
                        BookGroup old = book.C();
                        if (old == null || !key.equals(old.F())) {
                            if (old != null) old.v0().remove(book);
                            group.v0().add(book);
                            book.f1(group);
                        }
                        book.o1(i + 1);
                    }
                    List<Book> all = new ArrayList<Book>(group.v0());
                    Book last = all.get(0);
                    for (Book book : all) if (book.n() > last.n()) last = book;
                    if (holder == null && last.Q() != null) holder = last;
                    for (Book book : all) {
                        if (book.Q() == null) continue;
                        if (holder == null) holder = book;
                        else if (!book.equals(holder)) unshelve(book);
                    }
                    if (holder == null) return null;
                    if (!holder.equals(last)) { // hand the slot to the last volume, as Ridi does when a newer volume arrives
                        UserShelf shelf = holder.Q();
                        shelf.v0().set(shelf.v0().indexOf(holder), last);
                        last.z1(shelf);
                        last.p1(holder.Z());
                        last.k1(holder.y());
                        holder.z1(null);
                        holder.p1(null);
                        holder.k1(false);
                    }
                    last.Q().T();
                    return null;
                }
            });
            return true;
        }

        void unshelve(Book book) {
            book.Q().v0().remove(book);
            book.z1(null);
            book.p1(null);
            book.k1(false);
        }

        void select(DavClient.Entry entry) {
            if (entry.directory) { load(entry.url); return; }
            String url = entry.url.toString();
            Book book = local(entry);
            if (book != null) { open(book.v()); return; }
            if (queue.containsKey(url)) {
                stop(url);
                redraw(url);
                if (queue.isEmpty()) status.setText("다운로드를 취소했습니다."); else progress();
                return;
            }
            openWhenDone = queue.isEmpty() ? url : null; // a lone tapped book opens itself; batches do not
            enqueue(entry);
            redraw(url);
        }

        void open(String id) {
            activity.startActivity(new Intent().setClassName("com.ridi.paper", "com.ridi.books.viewer.common.activity.BookOpenerActivity")
                    .putExtra("book_id", id).putExtra("open_book_from", "WebDAV").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }

        void failed(final int ticket, final IOException error) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    if (ticket != generation || activity.isFinishing()) return;
                    cancel();
                    if (error instanceof javax.net.ssl.SSLException) status.setText("HTTPS 인증서 또는 TLS 연결 오류입니다. 기기 날짜와 서버 인증서 호환성을 확인하세요.");
                    else status.setText(error.getMessage() == null ? "서버에 연결할 수 없습니다." : error.getMessage());
                }
            });
        }
    }
}
