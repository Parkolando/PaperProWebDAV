package dev.paper.webdav;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import okhttp3.*;

public final class DavClientTest {
    private static int passed;
    private static final HttpUrl ROOT = HttpUrl.parse("https://example.test/books/");
    private static String item(String href, String properties, String status) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + properties
                + "</d:prop><d:status>HTTP/1.1 " + status + "</d:status></d:propstat></d:response>";
    }
    private static byte[] xml(String items) throws Exception {
        return ("<d:multistatus xmlns:d=\"DAV:\">" + items + "</d:multistatus>").getBytes("UTF-8");
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String name) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check(DavClient.rootUrl("https://example.test/books").equals(ROOT), "normalize folder slash");
        check(DavClient.key("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "key is lowercase hex SHA-256");
        check(DavClient.rootUrl("http://192.168.0.2/books").toString().equals("http://192.168.0.2/books/"), "accept http root");
        check(!DavClient.inside(ROOT, HttpUrl.parse("http://example.test/books/a.epub")), "never switch scheme inside a root");
        for (String address : new String[]{"192.168.0.5", "10.1.2.3", "172.16.0.1", "127.0.0.1", "169.254.1.1", "fd00::1", "::1"}) {
            check(DavClient.local(java.net.InetAddress.getByName(address)), "local address " + address);
        }
        for (String address : new String[]{"172.32.0.1", "8.8.8.8", "100.64.0.1", "2001:4860::1"}) {
            check(!DavClient.local(java.net.InetAddress.getByName(address)), "public address " + address);
        }
        rejected(new Action() { public void run() throws Exception {
            DavClient remote = new DavClient("http://93.184.216.34/books/", "reader", "secret");
            remote.list(remote.root);
        } }, "refuse plain http to a public address");
        for (final String invalid : new String[]{"ftp://example.test/", "https://u:p@example.test/", "https://example.test/?password=x", "https://example.test/#fragment"}) {
            rejected(new Action() { public void run() throws Exception { DavClient.rootUrl(invalid); } }, "reject unsafe root " + invalid);
        }
        List<DavClient.Entry> listing = DavClient.parse(ROOT, ROOT, xml(
                item("/books/", "<d:resourcetype><d:collection/></d:resourcetype>", "200 OK")
                + item("/books/%ED%95%9C%EA%B8%80+%20%25.epub", "<d:getcontentlength>42</d:getcontentlength><d:getetag>abc</d:getetag>", "200 OK")
                + item("folder", "<d:resourcetype><d:collection/></d:resourcetype>", "200 OK")
                + item("ignored.exe", "", "200 OK")
                + item("missing.pdf", "", "404 Not Found")
                + item("folder/nested.pdf", "", "200 OK")
                + item("https://evil.test/books/secret.pdf", "", "200 OK")
                + item("../private.pdf", "", "200 OK")
                + item("/books/bad%2Fpath.pdf", "", "200 OK")
                + item("/books/%ED%95%9C%EA%B8%80+%20%25.epub", "", "200 OK")));
        check(listing.size() == 2, "filter self, nested, off-origin, parent, encoded slash, errors and duplicates");
        check(listing.get(0).directory && listing.get(0).name.equals("folder"), "folders first, normalize collection slash");
        check(DavClient.natural("Book 2.cbz", "book 10.cbz") < 0 && DavClient.natural("a10", "a9b") > 0 && DavClient.natural("a", "a1") < 0
                && DavClient.natural("vol 007", "Vol 7") == 0 && DavClient.natural("b", "a2") > 0, "numeric-aware name order");
        check(Epub.seriesIndex("<meta refines=\"#s\" property=\"group-position\">4</meta>").equals("4")
                && Epub.seriesIndex("<meta content='1.5' name=\"calibre:series_index\"/>").equals("1.5")
                && Epub.seriesIndex("<meta name=\"calibre:series\" content=\"3\"/><dc:title>group-position 9</dc:title>").isEmpty(),
                "series position from EPUB 3 and calibre metadata");
        check(Epub.coverRank("nav cover-image", "image/jpeg", "x", "a.jpg") == 0
                && Epub.coverRank(null, "image/jpeg", "my-cover-image", "images/Cover.JPG") == 1
                && Epub.coverRank(null, "image/jpeg", "cc_img1", "images/hardcover.jpg") == 2
                && Epub.coverRank(null, "image/png", "cover2", "images/a.png") == 2
                && Epub.coverRank(null, "text/css", "cover", "styles/cover.css") == -1
                && Epub.coverRank(null, "image/png", "img1", "images/a.png") == -1, "cover candidates: declared, named, guessed, none");
        check(listing.get(1).name.equals("한글+ %.epub") && listing.get(1).size == 42, "Unicode plus percent names and size");
        rejected(new Action() { public void run() throws Exception { DavClient.parse(ROOT, ROOT, "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///secret'>]><x>&x;</x>".getBytes("UTF-8")); } }, "reject DTD and external entities");
        rejected(new Action() { public void run() throws Exception { DavClient.parse(ROOT, ROOT, "<html/>".getBytes("UTF-8")); } }, "reject non-DAV response");
        rejected(new Action() { public void run() throws Exception { DavClient.parse(ROOT, ROOT, new byte[DavClient.XML_LIMIT + 1]); } }, "enforce XML size bound");
        rejected(new Action() { public void run() throws Exception {
            StringBuilder items = new StringBuilder();
            for (int i = 0; i <= DavClient.ENTRY_LIMIT; i++) items.append(item("b" + i + ".pdf", "", "200 OK"));
            DavClient.parse(ROOT, ROOT, xml(items.toString()));
        } }, "enforce entry count bound");

        KeyStore store = KeyStore.getInstance("PKCS12");
        InputStream keyInput = new FileInputStream(args[0]);
        try { store.load(keyInput, "test-password".toCharArray()); } finally { keyInput.close(); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(store);
        SSLContext ssl = SSLContext.getInstance("TLSv1.2");
        ssl.init(keys.getKeyManagers(), trusts.getTrustManagers(), null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        final AtomicReference<String> mode = new AtomicReference<String>("ok");
        final AtomicReference<String> authorization = new AtomicReference<String>();
        server.createContext("/books/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String state = mode.get();
                byte[] body;
                int code;
                if (state.equals("unauthorized")) { body = new byte[0]; code = 401; }
                else if (state.equals("redirect")) {
                    exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/stolen");
                    body = new byte[0]; code = 302;
                } else if (exchange.getRequestMethod().equals("PROPFIND")) {
                    if (!"1".equals(exchange.getRequestHeaders().getFirst("Depth"))) throw new AssertionError("Depth header");
                    try { body = xml(item("file.epub", "<d:getcontentlength>7</d:getcontentlength>", "200 OK")); }
                    catch (Exception e) { throw new IOException(e); }
                    code = 207;
                } else { body = "fixture".getBytes("UTF-8"); code = 200; }
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        HttpServer plain = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        plain.createContext("/books/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body;
                try { body = xml(item("file.epub", "<d:getcontentlength>7</d:getcontentlength>", "200 OK")); }
                catch (Exception e) { throw new IOException(e); }
                exchange.sendResponseHeaders(207, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        plain.start();
        try {
            DavClient local = new DavClient("http://localhost:" + plain.getAddress().getPort() + "/books/", "reader", "secret");
            check(local.list(local.root).size() == 1 && "Basic cmVhZGVyOnNlY3JldA==".equals(authorization.get()), "plain http works on the local network");
        } finally { plain.stop(0); }
        try {
            final String address = "https://localhost:" + server.getAddress().getPort() + "/books/";
            OkHttpClient http = new OkHttpClient.Builder().sslSocketFactory(ssl.getSocketFactory(), (X509TrustManager) trusts.getTrustManagers()[0]).build();
            final DavClient client = new DavClient(address, "reader", "secret", http);
            List<DavClient.Entry> remote = client.list(client.root);
            check(remote.size() == 1 && "Basic cmVhZGVyOnNlY3JldA==".equals(authorization.get()), "HTTPS PROPFIND Depth 1 with Basic auth");
            File temporary = java.nio.file.Files.createTempDirectory("paper-webdav-test-").toFile();
            final long[] progress = new long[2];
            File downloaded = client.download(remote.get(0), temporary, new DavClient.Progress() {
                public void update(long done, long total) { progress[0] = done; progress[1] = total; }
            });
            check(progress[0] == 7 && progress[1] == 7, "download reports progress up to the full size");
            check(new String(java.nio.file.Files.readAllBytes(downloaded.toPath()), "UTF-8").equals("fixture"), "HTTPS GET and atomic completed download");
            final DavClient.Entry entry = remote.get(0);
            final File storage = temporary;
            rejected(new Action() { public void run() throws Exception { client.download(entry, storage, null); } }, "never overwrite existing file");
            downloaded.delete(); downloaded.getParentFile().delete(); temporary.delete();
            mode.set("unauthorized");
            rejected(new Action() { public void run() throws Exception { client.list(client.root); } }, "401 produces failure");
            mode.set("redirect");
            rejected(new Action() { public void run() throws Exception { client.list(client.root); } }, "do not redirect credentials");
            mode.set("ok");
            rejected(new Action() { public void run() throws Exception {
                DavClient untrusted = new DavClient(address, "reader", "secret");
                untrusted.list(untrusted.root);
            } }, "production trust rejects self-signed server");
            client.cancel();
            rejected(new Action() { public void run() throws Exception { client.list(client.root); } }, "cancel prevents request");
        } finally { server.stop(0); }
        System.out.println("PASS " + passed + " checks");
    }
}
