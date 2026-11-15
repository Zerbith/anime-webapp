package de.webapp.anime;

import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP-Hilfen und m3u8-Parser fuer den HLS-Download. */
final class Hls {
    private Hls() {}

    static final class Variant {
        final String url; final long bandwidth; final String resolution;
        Variant(String url, long bandwidth, String resolution) { this.url = url; this.bandwidth = bandwidth; this.resolution = resolution; }
        String label() {
            String q = resolution != null && !resolution.isEmpty() ? resolution : "Qualität";
            return bandwidth > 0 ? q + " – " + String.format(java.util.Locale.GERMANY, "%.1f Mbit/s", bandwidth / 1e6) : q;
        }
    }

    static final class Media {
        final List<String> segments = new ArrayList<>();
        String init;
        boolean encrypted;
    }

    /** Laedt eine URL mit Referer/Origin/User-Agent/Cookies, mit Wiederholungen. */
    static byte[] fetch(String url, String referer, String userAgent) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
                if (referer != null && !referer.isEmpty()) {
                    c.setRequestProperty("Referer", referer);
                    try {
                        URL r = new URL(referer);
                        c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority());
                    } catch (Exception ignored) {}
                }
                try {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) c.setRequestProperty("Cookie", cookie);
                } catch (Exception ignored) {}
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            } catch (IOException e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
                try { Thread.sleep(700L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    static String fetchText(String url, String referer, String ua) throws IOException {
        return new String(fetch(url, referer, ua), "UTF-8");
    }

    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel.trim()).toString(); } catch (Exception e) { return rel.trim(); }
    }

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile(name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static List<Variant> parseMaster(String text, String base) {
        List<Variant> list = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            int j = i + 1;
            while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].startsWith("#"))) j++;
            if (j >= lines.length) break;
            long bw = 0;
            try { String b = attr(lines[i], "BANDWIDTH"); if (b != null) bw = Long.parseLong(b.trim()); } catch (Exception ignored) {}
            list.add(new Variant(resolve(base, lines[j]), bw, attr(lines[i], "RESOLUTION")));
        }
        Collections.sort(list, (a, b) -> Long.compare(b.bandwidth, a.bandwidth));
        return list;
    }

    static Media parseMedia(String text, String base) {
        Media m = new Media();
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.startsWith("#EXT-X-KEY")) {
                String method = attr(l, "METHOD");
                if (method != null && !"NONE".equals(method)) m.encrypted = true;
            } else if (l.startsWith("#EXT-X-MAP")) {
                String uri = attr(l, "URI");
                if (uri != null) m.init = resolve(base, uri);
            } else if (!l.isEmpty() && !l.startsWith("#")) {
                m.segments.add(resolve(base, l));
            }
        }
        return m;
    }

    static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s.isEmpty() ? "video" : s;
    }
}
