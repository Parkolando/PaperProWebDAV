package dev.paper.webdav;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import dalvik.system.PathClassLoader;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

public final class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static String modulePath;
    private static final ThreadLocal<String> importing = new ThreadLocal<String>();
    /** Manifest key prefix for cover candidates by Epub.coverRank; the space keeps it apart from real item ids. */
    private static final String COVER = " cover";

    public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam param) {
        if (!"com.ridi.paper".equals(param.packageName) || !"com.ridi.paper".equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                Context context = (Context) hook.args[0];
                if (context.getPackageManager().getPackageInfo("com.ridi.paper", 0).versionCode != 2024081601) {
                    XposedBridge.log("PaperWebDAV: unsupported Ridi version; hooks disabled");
                    return;
                }
                install(param.classLoader, context);
            }
        });
    }

    private static void install(final ClassLoader host, Context context) throws Exception {
        // Parent must be the host loader so the existing OkHttp and Ridi classes are reused.
        ClassLoader loader = new PathClassLoader(modulePath, host);
        final Class<?> integration = loader.loadClass("dev.paper.webdav.Integration");
        integration.getMethod("init", Context.class).invoke(null, context);
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.main.activity.MainActivityPaper", host, "onCreate", Bundle.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) { invoke(integration, "attach", new Class<?>[]{Activity.class}, p.thisObject); }
        });
        // G() swaps the list adapter when the settings panel opens and whenever a tab is chosen.
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.main.fragment.SettingsFragmentPaper", host, "G", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                invoke(integration, "settings", new Class<?>[]{ListView.class, String.class}, XposedHelpers.getObjectField(p.thisObject, "l"),
                        ((Enum<?>) XposedHelpers.getObjectField(p.thisObject, "m")).name());
            }
        });
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.main.activity.MainActivity", host, "onBackPressed", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(invoke(integration, "back", new Class<?>[]{Activity.class}, p.thisObject))) p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.main.activity.MainActivityPaper", host, "onClick", View.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) { invoke(integration, "tabClicked", new Class<?>[]{Activity.class, View.class}, p.thisObject, p.args[0]); }
        });
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.main.activity.MainActivityPaper", host, "onDestroy", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) { invoke(integration, "destroy", new Class<?>[]{Activity.class}, p.thisObject); }
        });
        final Class<?> importer = host.loadClass("d.k.a.c.e0.w1.n.h");
        XposedHelpers.findAndHookMethod(importer, "a", String.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) { importing.set((String) p.args[0]); }
            protected void afterHookedMethod(MethodHookParam p) { importing.remove(); }
        });
        XposedHelpers.findAndHookMethod("com.ridi.books.viewer.common.library.Library", host, "c0",
                String.class, String.class, String.class, String.class, int.class, boolean.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && p.getResult() != null && importing.get() != null) {
                    invoke(integration, "imported", new Class<?>[]{String.class, String.class}, importing.get(), p.args[0]);
                }
            }
        });
        // Ridi keeps the last <dc:title> of an EPUB, which is the collection title (e.g. 일반책) when one follows the main title.
        XposedBridge.hookAllMethods(host.loadClass("d.k.a.c.i0.f3.n4.a$f"), "pushTitle", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                String title = (String) XposedHelpers.getObjectField(XposedHelpers.getObjectField(p.thisObject, "metadata"), "title");
                if (title != null && !title.trim().isEmpty()) p.setResult(null);
            }
        });
        // Ridi only follows the EPUB 2 <meta name="cover"> id, which is missing or wrong in some files. Remember the
        // EPUB 3 properties="cover-image" item, or failing that an image that looks like a cover, as the default.
        XposedBridge.hookAllMethods(host.loadClass("d.k.a.c.i0.f3.n4.a$d"), "pushManifestItem", new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam p) {
                XmlPullParser item = (XmlPullParser) XposedHelpers.getObjectField(p.args[0], "a");
                String href = item.getAttributeValue(null, "href");
                int rank = Epub.coverRank(item.getAttributeValue(null, "properties"), item.getAttributeValue(null, "media-type"),
                        item.getAttributeValue(null, "id"), href);
                if (rank < 0) return;
                Object context = XposedHelpers.getObjectField(p.thisObject, "context");
                Map<String, String> manifest = (Map<String, String>) XposedHelpers.getObjectField(context, "manifest");
                if (!manifest.containsKey(COVER + rank)) manifest.put(COVER + rank, href);
            }
        });
        XposedBridge.hookAllConstructors(host.loadClass("d.k.a.c.i0.f3.n4.a$f"), new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Map<?, ?> manifest = (Map<?, ?>) XposedHelpers.getObjectField(p.args[0], "manifest");
                Object href = null;
                for (int rank = 0; rank <= 2 && href == null; rank++) href = manifest.get(COVER + rank);
                if (href != null) XposedHelpers.setObjectField(p.args[1], "coverFile",
                        new File((String) XposedHelpers.getObjectField(p.args[0], "contentsBaseDir"), (String) href));
            }
        });
        // The importer copies its source file into the book store. A finished download can be renamed there instead.
        XposedHelpers.findAndHookMethod("d.g.a.c.e.l.m", host, "x", File.class, File.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(invoke(integration, "move", new Class<?>[]{File.class, File.class}, p.args[0], p.args[1]))) p.setResult(true);
            }
        });
        XposedBridge.log("PaperWebDAV: P213_1.6.5 hooks ready");
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = type.getMethod(name, signature);
            return method.invoke(null, args);
        } catch (Throwable failure) {
            XposedBridge.log("PaperWebDAV: " + name + " failed: " + failure.getClass().getSimpleName());
            return null;
        }
    }
}
