package de.webapp.anime;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Laedt HTML-Seiten eingebetteter Fenster (iframes, z. B. der Video-Hoster) selbst und fuegt ein
 * kleines Skript ein, das die Abspielposition meldet bzw. fortsetzt. Geht etwas schief, gibt es null
 * zurueck – dann laedt die WebView die Seite ganz normal selbst.
 */
final class FrameInjector {
    /** Laeuft in jedem Fenster: meldet Position (alle 5 s + bei Pause) und setzt beim Laden fort. */
    static final String TIME_JS =
        "(function(){if(window.__watTime)return;window.__watTime=1;var done=false;"
        + "function rep(v){try{if(v.duration>120&&isFinite(v.duration))WebAppTools.reportTime(v.currentTime,v.duration)}catch(e){}}"
        + "function meta(v){try{if(done||!(v.duration>120)||!isFinite(v.duration))return;var r=WebAppTools.getResume();"
        + "if(r>15&&r<v.duration-30){done=true;v.currentTime=r;WebAppTools.resumed(r)}else{done=true}}catch(e){}}"
        + "function hook(v){if(v.__wat)return;v.__wat=1;"
        + "v.addEventListener('loadedmetadata',function(){meta(v)});v.addEventListener('durationchange',function(){meta(v)});"
        + "v.addEventListener('timeupdate',function(){var n=Date.now();if(n-(v.__last||0)<5000)return;v.__last=n;rep(v)});"
        + "v.addEventListener('pause',function(){rep(v)});if(v.readyState>=1)meta(v)}"
        + "function scan(){var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++)hook(vs[i])}"
        + "setInterval(scan,1500);document.addEventListener('DOMContentLoaded',scan);scan()})();";

    /** Nicht anfassen: Sicherheitspruefungen/CAPTCHAs und Anmeldungen. */
    private static final Pattern SKIP_HOST = Pattern.compile(
        "(^|\\.)(google\\.com|gstatic\\.com|recaptcha\\.net|hcaptcha\\.com|cloudflare\\.com|challenges\\.cloudflare\\.com|"
        + "turnstile\\.|facebook\\.com|youtube\\.com|accounts\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CSP = Pattern.compile("<meta[^>]+http-equiv=[\"']?content-security-policy[^>]*>", Pattern.CASE_INSENSITIVE);

    private FrameInjector() {}

    static boolean wants(WebResourceRequest r) {
        if (r.isForMainFrame() || !"GET".equalsIgnoreCase(r.getMethod())) return false;
        String scheme = r.getUrl().getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        Map<String, String> h = r.getRequestHeaders();
        String accept = null;
        if (h != null) for (Map.Entry<String, String> e : h.entrySet()) if ("accept".equalsIgnoreCase(e.getKey())) accept = e.getValue();
        if (accept == null || !accept.contains("text/html")) return false; // nur Dokumente (iframes), keine Skripte/Bilder
        String host = r.getUrl().getHost();
        return host != null && !SKIP_HOST.matcher(host).find();
    }

    static WebResourceResponse load(WebResourceRequest r, String userAgent) {
        HttpURLConnection c = null;
        try {
            String url = r.getUrl().toString();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); // bei Weiterleitung selbst laden lassen (sonst stimmen relative Links nicht)
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            Map<String, String> h = r.getRequestHeaders();
            if (h != null) for (Map.Entry<String, String> e : h.entrySet()) {
                if (!"cookie".equalsIgnoreCase(e.getKey())) c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                // Weiterleitung (z. B. AniWorld /redirect/… -> Hoster): das Fenster navigiert selbst neu,
                // damit auch die Zielseite hier vorbeikommt und das Skript bekommt.
                String target = new URL(new URL(url), c.getHeaderField("Location")).toString();
                List<String> sc = c.getHeaderFields().get("Set-Cookie");
                if (sc != null) for (String v : sc) CookieManager.getInstance().setCookie(url, v);
                String js = "<html><head><script>location.replace(" + org.json.JSONObject.quote(target) + ")</script></head><body></body></html>";
                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", new HashMap<String, String>(),
                    new ByteArrayInputStream(js.getBytes("UTF-8")));
            }
            if (code != 200) { c.disconnect(); return null; }
            String type = c.getContentType() == null ? "" : c.getContentType();
            if (!type.toLowerCase(Locale.ROOT).contains("text/html")) { c.disconnect(); return null; }

            // Cookies an die WebView weitergeben
            Map<String, List<String>> fields = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey())) {
                    for (String v : e.getValue()) CookieManager.getInstance().setCookie(url, v);
                }
            }

            Charset cs = Charset.forName("UTF-8");
            Matcher cm = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(type);
            if (cm.find()) try { cs = Charset.forName(cm.group(1)); } catch (Exception ignored) {}
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) { buf.write(b, 0, n); if (buf.size() > 4_000_000) return null; }
            }
            String html = new String(buf.toByteArray(), cs);
            html = META_CSP.matcher(html).replaceAll("");
            String tag = "<script>" + TIME_JS + "</script>";
            Matcher hm = HEAD.matcher(html);
            html = hm.find() ? html.substring(0, hm.end()) + tag + html.substring(hm.end()) : tag + html;

            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue().isEmpty()) continue;
                String kl = k.toLowerCase(Locale.ROOT);
                // CSP entfernen (sonst wird das Skript blockiert); Laenge/Kodierung stimmen nach dem Einfuegen nicht mehr
                if (kl.startsWith("content-security-policy") || kl.equals("content-length") || kl.equals("content-encoding")
                    || kl.equals("transfer-encoding") || kl.equals("set-cookie") || kl.equals("content-type")) continue;
                out.put(k, e.getValue().get(0));
            }
            String reason = c.getResponseMessage() == null || c.getResponseMessage().isEmpty() ? "OK" : c.getResponseMessage();
            return new WebResourceResponse("text/html", cs.name(), 200, reason, out, new ByteArrayInputStream(html.getBytes(cs)));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }
}
