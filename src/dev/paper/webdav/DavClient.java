package dev.paper.webdav;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import okhttp3.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

final class DavClient {
    static final int XML_LIMIT = 4 * 1024 * 1024;
    static final int ENTRY_LIMIT = 5000;
    private static final String QUERY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/>"
            + "<d:getcontentlength/><d:getetag/></d:prop></d:propfind>";
    final HttpUrl root;
    private final OkHttpClient http;
    private final String authorization;
    private volatile Call active;
    private volatile boolean cancelled;

    static final class Entry {
        final HttpUrl url;
        final String name;
        final boolean directory;
        final long size;
        final String etag;
        /** Stable id of the remote file, computed once here (off the UI thread) because lists look it up often. */
        final String key;

        Entry(HttpUrl url, boolean directory, long size, String etag) {
            this.url = url;
            key = DavClient.key(url.toString());
            this.directory = directory;
            this.size = size;
            this.etag = etag;
            List<String> parts = url.pathSegments();
            name = parts.get(parts.size() - (directory ? 2 : 1));
        }
    }

    DavClient(String url, String user, String password) throws IOException {
        this(url, user, password, secureClient());
    }

    DavClient(String url, String user, String password, OkHttpClient client) throws IOException {
        root = rootUrl(url);
        if (user.contains(":")) throw new IOException("사용자 이름에는 :을 사용할 수 없습니다.");
        authorization = user.isEmpty() && password.isEmpty() ? null : Credentials.basic(user, password, java.nio.charset.Charset.forName("UTF-8"));
        http = client.newBuilder().dns(root.isHttps() ? client.dns() : LOCAL_ONLY).followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    }

    /** Plain HTTP sends the password unencrypted, so it is only allowed to servers on the local network. */
    private static final Dns LOCAL_ONLY = new Dns() {
        public List<InetAddress> lookup(String host) throws UnknownHostException {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(host);
            for (InetAddress address : addresses) {
                if (!local(address)) throw new UnknownHostException("http:// 연결은 내부망(사설 IP) 서버에서만 허용됩니다. 외부 서버는 https://를 사용하세요.");
            }
            return addresses;
        }
    };

    /** Private (RFC 1918), loopback and link-local addresses, plus IPv6 unique local addresses (fc00::/7). */
    static boolean local(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
    }

    static HttpUrl rootUrl(String value) throws IOException {
        HttpUrl url = HttpUrl.parse(value.trim()); // null unless http or https
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IOException("https:// 또는 http://로 시작하는 폴더 주소를 입력하세요. 계정은 별도 칸에 입력합니다.");
        }
        if (!url.encodedPath().endsWith("/")) url = url.newBuilder().addPathSegment("").build();
        return url;
    }

    void cancel() {
        cancelled = true;
        Call call = active;
        if (call != null) call.cancel();
    }

    private Response request(HttpUrl url, String method) throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("취소되었습니다.");
        if (!inside(root, url)) throw new IOException("설정한 서버 폴더 밖의 요청입니다.");
        Request.Builder request = new Request.Builder().url(url);
        if (authorization != null) request.header("Authorization", authorization);
        if (method.equals("PROPFIND")) request.header("Depth", "1").method(method,
                RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), QUERY));
        else request.get();
        active = http.newCall(request.build());
        if (cancelled) active.cancel();
        Response response = active.execute();
        int expected = method.equals("PROPFIND") ? 207 : 200;
        if (response.code() != expected || response.body() == null) {
            int code = response.code();
            response.close();
            if (code == 401 || code == 403) throw new IOException("접근이 거부되었습니다. 계정과 폴더 권한을 확인하세요. (" + code + ")");
            if (code >= 300 && code < 400) throw new IOException("서버가 다른 주소로 이동시킵니다. 최종 폴더 주소를 설정하세요.");
            throw new IOException("WebDAV 서버 응답 오류 (HTTP " + code + ")");
        }
        return response;
    }

    List<Entry> list(HttpUrl folder) throws IOException {
        if (!folder.encodedPath().endsWith("/")) throw new IOException("폴더 주소가 아닙니다.");
        Response response = request(folder, "PROPFIND");
        try {
            InputStream input = response.body().byteStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled) throw new InterruptedIOException("취소되었습니다.");
                if (bytes.size() + count > XML_LIMIT) throw new IOException("폴더 목록이 너무 큽니다. 하위 폴더로 나눠 주세요. (최대 4 MiB)");
                bytes.write(buffer, 0, count);
            }
            return parse(root, folder, bytes.toByteArray());
        } finally {
            response.close();
            active = null;
        }
    }

    static List<Entry> parse(HttpUrl root, HttpUrl folder, byte[] xml) throws IOException {
        if (xml.length > XML_LIMIT) throw new IOException("폴더 목록이 너무 큽니다.");
        List<Entry> result = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new ByteArrayInputStream(xml), null);
            String href = null, etag = null, status = null;
            long size = -1, propSize = -1;
            boolean directory = false, propDirectory = false, valid = false, multi = false;
            String propEtag = null;
            int responseDepth = -1, propDepth = -1, responses = 0;
            for (int token = parser.nextToken(); token != XmlPullParser.END_DOCUMENT; token = parser.nextToken()) {
                if (token == XmlPullParser.DOCDECL || token == XmlPullParser.ENTITY_REF) throw new IOException("허용되지 않는 XML 선언입니다.");
                if (!"DAV:".equals(parser.getNamespace())) continue;
                String tag = parser.getName();
                if (token == XmlPullParser.START_TAG) {
                    if (parser.getDepth() == 1 && tag.equals("multistatus")) multi = true;
                    if (tag.equals("response") && parser.getDepth() == 2) {
                        if (++responses > ENTRY_LIMIT) throw new IOException("한 폴더에 최대 5000개 항목까지 표시합니다.");
                        responseDepth = parser.getDepth();
                        href = null; etag = null; size = -1; directory = false; valid = false;
                    } else if (responseDepth > 0 && tag.equals("href") && parser.getDepth() == responseDepth + 1) {
                        href = parser.nextText();
                    } else if (responseDepth > 0 && tag.equals("propstat")) {
                        propDepth = parser.getDepth();
                        status = null; propSize = -1; propDirectory = false; propEtag = null;
                    } else if (propDepth > 0 && tag.equals("status")) status = parser.nextText();
                    else if (propDepth > 0 && tag.equals("collection")) propDirectory = true;
                    else if (propDepth > 0 && tag.equals("getcontentlength")) {
                        try { propSize = Long.parseLong(parser.nextText().trim()); }
                        catch (NumberFormatException ignored) { propSize = -1; }
                    } else if (propDepth > 0 && tag.equals("getetag")) propEtag = parser.nextText();
                } else if (token == XmlPullParser.END_TAG) {
                    if (tag.equals("propstat")) {
                        if (status != null && status.matches("HTTP/\\S+ 2\\d\\d(?: .*|$)")) {
                            valid = true;
                            directory |= propDirectory;
                            if (propSize >= 0) size = propSize;
                            if (propEtag != null) etag = propEtag;
                        }
                        propDepth = -1;
                    } else if (tag.equals("response")) {
                        HttpUrl url = href == null ? null : folder.resolve(href);
                        if (url != null && directory && !url.encodedPath().endsWith("/")) url = url.newBuilder().addPathSegment("").build();
                        if (valid && url != null && immediateChild(root, folder, url, directory)
                                && (directory || supported(url)) && seen.add(url.toString())) result.add(new Entry(url, directory, size, etag));
                        responseDepth = -1;
                    }
                }
            }
            if (!multi) throw new IOException("WebDAV 폴더 목록이 아닙니다.");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("WebDAV 폴더 목록을 해석할 수 없습니다.", e); }
        Collections.sort(result, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return natural(a.name, b.name);
            }
        });
        return result;
    }

    /** Case-insensitive name order that compares digit runs as numbers, so volume 2 sorts before volume 10. */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = i, y = j;
            while (x < a.length() && a.charAt(x) >= '0' && a.charAt(x) <= '9') x++;
            while (y < b.length() && b.charAt(y) >= '0' && b.charAt(y) <= '9') y++;
            int difference;
            if (x > i && y > j) {
                difference = new java.math.BigInteger(a.substring(i, x)).compareTo(new java.math.BigInteger(b.substring(j, y)));
                i = x;
                j = y;
            } else difference = Character.toLowerCase(a.charAt(i++)) - Character.toLowerCase(b.charAt(j++));
            if (difference != 0) return difference;
        }
        return (a.length() - i) - (b.length() - j);
    }

    static boolean inside(HttpUrl root, HttpUrl url) {
        return root.scheme().equals(url.scheme()) && root.host().equals(url.host()) && root.port() == url.port()
                && url.username().isEmpty() && url.password().isEmpty() && url.query() == null && url.fragment() == null
                && url.encodedPath().startsWith(root.encodedPath());
    }

    private static boolean immediateChild(HttpUrl root, HttpUrl folder, HttpUrl url, boolean directory) {
        if (!inside(root, url) || !url.encodedPath().startsWith(folder.encodedPath())) return false;
        String tail = url.encodedPath().substring(folder.encodedPath().length());
        if (directory && tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
        if (tail.isEmpty() || tail.contains("/")) return false;
        for (String segment : url.pathSegments()) {
            if (segment.contains("/") || segment.contains("\\") || segment.indexOf('\0') >= 0) return false;
        }
        return true;
    }

    private static boolean supported(HttpUrl url) {
        String path = url.encodedPath().toLowerCase(Locale.US);
        return path.endsWith(".epub") || path.endsWith(".pdf") || path.endsWith(".cbz")
                || path.endsWith(".zip") || path.endsWith(".txt");
    }

    interface Progress { void update(long done, long total); }

    File download(Entry entry, File storage, Progress progress) throws IOException {
        File folder = new File(storage, entry.key);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("다운로드 폴더를 만들 수 없습니다.");
        String name = entry.name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_");
        if (name.length() > 150) name = name.substring(0, 120) + name.substring(name.lastIndexOf('.'));
        File destination = new File(folder, name);
        File temporary = new File(folder, name + ".part");
        if (destination.exists()) throw new IOException("이전 임시 다운로드가 남아 있습니다. 다시 시도하기 전에 정리해 주세요.");
        Response response = request(entry.url, "GET");
        boolean done = false;
        try {
            long expected = response.body().contentLength();
            long reserve = 32L * 1024 * 1024;
            if (expected >= 0 && expected > (folder.getUsableSpace() - reserve) / 2) throw new IOException("책을 다운로드하고 가져올 저장 공간이 부족합니다.");
            InputStream input = response.body().byteStream();
            FileOutputStream output = new FileOutputStream(temporary);
            long total = 0;
            try {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("취소되었습니다.");
                    total += count;
                    if ((total & 0xfffff) < count && folder.getUsableSpace() < total + reserve) throw new IOException("책을 가져올 저장 공간이 부족합니다.");
                    output.write(buffer, 0, count);
                    if (progress != null) progress.update(total, expected >= 0 ? expected : entry.size);
                }
            } finally { output.close(); }
            if (total == 0 || (expected >= 0 && total != expected)) throw new IOException("파일 다운로드가 완전하지 않습니다.");
            if (!temporary.renameTo(destination)) throw new IOException("다운로드 파일을 저장할 수 없습니다.");
            done = true;
            return destination;
        } finally {
            response.close();
            active = null;
            if (!done && temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    /** Downloads a public file, following redirects; used to install rclone. */
    static void fetch(String url, File target, Progress progress) throws IOException {
        Response response = secureClient().newBuilder().followRedirects(true).followSslRedirects(true).build()
                .newCall(new Request.Builder().url(url).build()).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("내려받을 수 없습니다. (HTTP " + response.code() + ")");
            long total = response.body().contentLength(), done = 0;
            InputStream input = response.body().byteStream();
            OutputStream output = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, count);
                    done += count;
                    if (progress != null) progress.update(done, total);
                }
            } finally { output.close(); }
        } finally { response.close(); }
    }

    static String key(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = "0123456789abcdef".charAt((bytes[i] >> 4) & 15);
                hex[i * 2 + 1] = "0123456789abcdef".charAt(bytes[i] & 15);
            }
            return new String(hex);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static OkHttpClient shared;

    /** One client for every request, so connections and TLS sessions are reused instead of renegotiated per folder. */
    private static synchronized OkHttpClient secureClient() throws IOException {
        if (shared != null) return shared;
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509TrustManager trust = (X509TrustManager) factory.getTrustManagers()[0];
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{trust}, null);
            return shared = new OkHttpClient.Builder().sslSocketFactory(new Tls12(ssl.getSocketFactory()), trust).build();
        } catch (GeneralSecurityException e) { throw new IOException("HTTPS 초기화에 실패했습니다.", e); }
    }

    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket enable(Socket socket) {
            ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }
        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        public Socket createSocket() throws IOException { return enable(delegate.createSocket()); }
        public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { return enable(delegate.createSocket(s, h, p, close)); }
        public Socket createSocket(String h, int p) throws IOException { return enable(delegate.createSocket(h, p)); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return enable(delegate.createSocket(h, p, l, lp)); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return enable(delegate.createSocket(h, p)); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return enable(delegate.createSocket(h, p, l, lp)); }
    }
}
