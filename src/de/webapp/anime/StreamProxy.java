package de.webapp.anime;

import android.util.Base64;
import android.webkit.CookieManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kleiner HTTP-Proxy auf dem Handy: Der Fernseher laedt den Stream ueber das Handy, das dabei
 * Referer/Cookies mitschickt und CORS erlaubt. HLS-Playlists werden umgeschrieben, damit auch
 * alle Segmente ueber den Proxy laufen.
 */
final class StreamProxy {
    private final String referer, userAgent;
    private ServerSocket server;
    private String base;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;

    StreamProxy(String referer, String userAgent) { this.referer = referer; this.userAgent = userAgent; }

    void start() throws Exception {
        String ip = localIp();
        if (ip == null) throw new Exception("Keine WLAN-Adresse gefunden");
        server = new ServerSocket(0, 50);
        base = "http://" + ip + ":" + server.getLocalPort();
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try { Socket s = server.accept(); pool.execute(() -> handle(s)); }
                catch (Exception e) { if (!running) break; }
            }
        }, "stream-proxy");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    String wrap(String url) {
        return base + "/p?u=" + Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) { return null; }
    }

    // ---------- Anfrage bearbeiten ----------

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            String[] parts = req.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            if ("OPTIONS".equals(method)) {
                out.write(("HTTP/1.1 204 No Content\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return;
            }
            int q = path.indexOf("?u=");
            if (q < 0) { error(out, 404); return; }
            String target = new String(Base64.decode(path.substring(q + 3), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            proxy(target, method, headers.get("range"), out);
        } catch (Exception ignored) {}
    }

    private String cors() {
        return "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
            + "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n";
    }

    private void error(OutputStream out, int code) throws Exception {
        out.write(("HTTP/1.1 " + code + " Error\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private void proxy(String target, String method, String range, OutputStream out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
            try { URL r = new URL(referer); c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority()); } catch (Exception ignored) {}
        }
        try { String ck = CookieManager.getInstance().getCookie(target); if (ck != null) c.setRequestProperty("Cookie", ck); } catch (Exception ignored) {}
        if (range != null) c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        if (code >= 400) { error(out, code); c.disconnect(); return; }
        String type = c.getContentType() == null ? "" : c.getContentType();
        boolean playlist = target.toLowerCase(Locale.ROOT).contains(".m3u8") || type.toLowerCase(Locale.ROOT).contains("mpegurl");
        try (InputStream body = c.getInputStream()) {
            if (playlist) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384]; int n;
                while ((n = body.read(b)) > 0) buf.write(b, 0, n);
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                byte[] res = rewrite(text, c.getURL().toString()).getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: " + res.length
                    + "\r\nCache-Control: no-cache\r\n" + cors() + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (!"HEAD".equals(method)) out.write(res);
            } else {
                StringBuilder h = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
                if (!type.isEmpty()) h.append("Content-Type: ").append(type).append("\r\n");
                String len = c.getHeaderField("Content-Length");
                if (len != null) h.append("Content-Length: ").append(len).append("\r\n");
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
                h.append("Accept-Ranges: bytes\r\n").append(cors()).append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
                if (!"HEAD".equals(method)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = body.read(b)) > 0) out.write(b, 0, n);
                }
            }
            out.flush();
        } finally {
            c.disconnect();
        }
    }

    /** Alle URIs einer m3u8-Playlist auf den Proxy umbiegen. */
    String rewrite(String text, String base) {
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.isEmpty()) { sb.append('\n'); continue; }
            if (l.startsWith("#")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(l);
                StringBuffer rep = new StringBuffer();
                while (m.find()) m.appendReplacement(rep, java.util.regex.Matcher.quoteReplacement("URI=\"" + wrap(Hls.resolve(base, m.group(1))) + "\""));
                m.appendTail(rep);
                sb.append(rep).append('\n');
            } else {
                sb.append(wrap(Hls.resolve(base, l))).append('\n');
            }
        }
        return sb.toString();
    }
}
