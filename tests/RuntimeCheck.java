package dev.paper.webdav;

import java.lang.reflect.*;
import java.util.*;

public final class RuntimeCheck {
    private static int checks;
    private static Class<?> type(String name) throws Exception { return Class.forName(name, false, RuntimeCheck.class.getClassLoader()); }
    private static void returns(Class<?> owner, String method, Class<?> result, Class<?>... arguments) throws Exception {
        if (owner.getMethod(method, arguments).getReturnType() != result) throw new AssertionError(owner.getName() + "." + method + " return type");
        checks++;
    }
    private static void method(String name, String method, Class<?>... arguments) throws Exception {
        type(name).getDeclaredMethod(method, arguments);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper(); // AsyncTask (the importer's base class) needs one to initialize
        method("com.ridi.books.viewer.main.activity.MainActivityPaper", "onCreate", android.os.Bundle.class);
        method("com.ridi.books.viewer.main.activity.MainActivityPaper", "onClick", android.view.View.class);
        method("com.ridi.books.viewer.main.activity.MainActivityPaper", "onDestroy");
        method("com.ridi.books.viewer.main.activity.MainActivity", "N");
        method("com.ridi.books.viewer.main.activity.MainActivity", "onBackPressed");
        method("com.ridi.books.viewer.common.library.Library", "c0", String.class, String.class, String.class, String.class, int.class, boolean.class);
        method("com.ridi.books.viewer.common.library.Library", "t", String.class);
        for (String name : new String[]{"U0", "y1"}) method("com.ridi.books.viewer.common.library.models.Book", name, String.class);
        for (String name : new String[]{"b1", "Y0"}) method("com.ridi.books.viewer.common.library.models.Book", name, int.class);
        method("d.k.a.c.e0.w1.n.h", "a", String.class);
        method("d.k.a.c.e0.w1.n.h", "doInBackground", Object[].class);
        if (type("d.k.a.c.e0.w1.n.h").getConstructors().length != 1) throw new AssertionError("importer constructor");
        type("com.ridi.books.viewer.RidibooksApp").getField("d");
        method("i.c", "getValue");
        method("d.k.a.b.a", "f", Object.class);
        for (String name : new String[]{"d.k.a.c.e0.r0", "d.k.a.c.e0.y0"}) type(name).getConstructor();
        method("d.k.a.c.i0.f3.n4.a$f", "pushTitle", type("com.ridi.books.viewer.reader.epub.parser.SimpleXmlParser$a"));
        type("d.k.a.c.i0.f3.n4.a$f").getDeclaredField("metadata");
        if (type("d.k.a.c.i0.f3.n4.a").getMethod("c", java.io.File.class).getReturnType().getField("title").getType() != String.class) throw new AssertionError("EPUB metadata title");
        String parser = "d.k.a.c.i0.f3.n4.a";
        Class<?> element = type("com.ridi.books.viewer.reader.epub.parser.SimpleXmlParser$a");
        method(parser + "$d", "pushManifestItem", element);
        if (element.getField("a").getType() != org.xmlpull.v1.XmlPullParser.class) throw new AssertionError("XML element parser");
        if (type(parser + "$b").getField("manifest").getType() != Map.class || type(parser + "$b").getField("contentsBaseDir").getType() != String.class
                || type(parser + "$e").getField("coverFile").getType() != java.io.File.class) throw new AssertionError("EPUB cover fields");
        type(parser + "$c").getField("context");
        type(parser + "$f").getConstructor(type(parser + "$b"), type(parser + "$e"));
        if (type("d.k.a.c.e0.w1.n.j").getMethod("a", java.io.File.class).getReturnType() != android.graphics.Bitmap.class) throw new AssertionError("cover scaler");
        method("com.ridi.books.viewer.common.library.models.Book", "w0");
        checks += 5;
        returns(type("d.g.a.c.e.l.m"), "x", boolean.class, java.io.File.class, java.io.File.class);
        Class<?> ridi = type("com.ridi.books.viewer.RidibooksApp");
        if (ridi.getField("b").getType() != ridi) throw new AssertionError("app instance field");
        returns(ridi, "c", java.io.File.class);
        checks++;
        Class<?> dns = type("okhttp3.Dns");
        dns.getMethod("lookup", String.class);
        dns.getField("SYSTEM");
        type("okhttp3.OkHttpClient$Builder").getMethod("dns", dns);
        if (type("okhttp3.OkHttpClient").getMethod("dns").getReturnType() != dns) throw new AssertionError("OkHttp DNS hook");
        checks += 4;
        Class<?> settings = type("com.ridi.books.viewer.main.fragment.SettingsFragmentPaper");
        settings.getDeclaredMethod("G");
        if (settings.getField("l").getType() != android.widget.ListView.class) throw new AssertionError("settings list field");
        for (String name : new String[]{"FILE_MANAGEMENT_SETTINGS", "POWER_SECURITY_SETTINGS"}) settings.getField("m").getType().getField(name);
        checks += 3;
        String models = "com.ridi.books.viewer.common.library.models.";
        Class<?> book = type(models + "Book"), group = type(models + "BookGroup"), shelf = type(models + "UserShelf"), list = type("f.b.c2");
        if (!java.util.AbstractList.class.isAssignableFrom(list)) throw new AssertionError("RealmList is a List");
        returns(book, "C", group);
        returns(book, "f1", void.class, group);
        returns(book, "n", int.class);
        returns(book, "o1", void.class, int.class);
        returns(book, "Q", shelf);
        returns(book, "z1", void.class, shelf);
        returns(book, "Z", String.class);
        returns(book, "p1", void.class, String.class);
        returns(book, "y", boolean.class);
        returns(book, "k1", void.class, boolean.class);
        returns(group, "F", String.class);
        for (String name : new String[]{"B0", "y0", "A0", "C0"}) returns(group, name, void.class, String.class);
        returns(group, "v0", list);
        returns(shelf, "v0", list);
        returns(shelf, "T", void.class);
        Class<?> library = type("com.ridi.books.viewer.common.library.Library");
        returns(library, "w", group, String.class);
        returns(library, "f", void.class, type("i.r.a.a"));
        returns(type("i.r.a.a"), "invoke", Object.class);
        returns(library.getField("c").getType(), "c0", type("f.b.f2"), Class.class, Object.class);
        Class<?> realm = type("com.ridi.books.viewer.common.library.Library").getField("c").getType();
        realm.getMethod("c");
        realm.getMethod("i");
        method("com.ridi.books.viewer.common.library.models.Book", "z0");
        method("com.ridi.books.viewer.common.library.models.Book", "a");
        Class<?> state = type("com.ridi.books.viewer.common.library.book.download.DownloadTask$State");
        for (String name : new String[]{"WAITING", "DOWNLOADING", "FAILED"}) state.getField(name);
        method("com.ridi.books.viewer.main.view.BookDownloadProgressView", "setGrouped", boolean.class);
        method("com.ridi.books.viewer.main.view.BookDownloadProgressView", "setProgress", int.class);
        method("com.ridi.books.viewer.main.view.BookDownloadProgressView", "setState", state);
        checks += 9;
        method("com.ridi.books.viewer.main.view.library.ShelfView", "b", int.class, int.class, int.class, int.class, int.class);
        type("d.k.a.c.h0.l0.i0").getConstructor(android.content.Context.class, type("com.ridi.books.viewer.common.library.Library"), type("d.k.a.c.h0.l0.i0$a"));
        if (type("d.k.a.c.h0.l0.i0").getField("c").getType() != List.class) throw new AssertionError("native book list field");
        checks += 2;
        type("dev.paper.webdav.BooksAdapter");
        checks++;
        DavClient client = new DavClient("https://example.test/books/", "", "");
        byte[] xml = ("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/books/a.epub</d:href>"
                + "<d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>").getBytes("UTF-8");
        if (DavClient.parse(client.root, client.root, xml).size() != 1) throw new AssertionError("Android XML parser");
        checks++;
        System.out.println("PASS " + checks + " KitKat runtime and original APK API checks");
    }
}
