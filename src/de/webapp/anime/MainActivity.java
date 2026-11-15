package de.webapp.anime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // ---------- Farben / Masse ----------
    private static final int BG = 0xFF16151B, CARD = 0xFF1C1B22, BORDER = 0xFF34323F, ACCENT = 0xFF5B4BDB,
        TEXT = 0xFFE8E8EE, MUTED = 0xFF9A97A8, BTN = 0xFF2C2A36, RED = 0xFFE5484D;

    private static final Pattern HLS = Pattern.compile("\\.m3u8(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH = Pattern.compile("\\.mpd(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO = Pattern.compile("\\.(mp4|webm|mkv|m4v)(\\?|#|$)", Pattern.CASE_INSENSITIVE);

    static final class Stream {
        final String url, type, referer;
        Stream(String url, String type, String referer) { this.url = url; this.type = type; this.referer = referer; }
        String label() { return "hls".equals(type) ? "HLS (m3u8)" : "dash".equals(type) ? "DASH (mpd)" : "Video-Datei"; }
    }

    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean adblock, popupBlock;
    private volatile String currentPageUrl = "";

    private FrameLayout root;
    private WebView web;
    private FrameLayout fab;
    private TextView badge;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private Dialog sheet;
    private int blockedPopups;
    private long lastPopupToast;
    private Runnable pendingAfterPermission;
    private Updater updater;

    // ======================================================================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("webapp", MODE_PRIVATE);
        adblock = prefs.getBoolean("adblock", true);
        popupBlock = prefs.getBoolean("popupBlock", true);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        if (Build.VERSION.SDK_INT < 35) { w.setStatusBarColor(BG); w.setNavigationBarColor(BG); }

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        setupWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        buildFab();
        setContentView(root);

        if (saved != null) {
            web.restoreState(saved);
        } else {
            String url = prefs.getString("url", null);
            if (url == null) askUrl(true); else web.loadUrl(url);
        }
        updater = new Updater(this, prefs);
        updater.check(false);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); if (updater != null) updater.resumePendingInstall(); }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (sheet != null && sheet.isShowing()) { sheet.dismiss(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    // ======================================================================
    // WebView
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // "; wv" entfernen, damit Seiten den normalen Chrome-Player ausliefern
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/\\S+ ", ""));
        applyPopupSetting();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "WebAppTools");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, contentDisposition, mime, length) ->
            downloadFile(url, web.getUrl(), URLUtil.guessFileName(url, contentDisposition, mime)));
    }

    private void applyPopupSetting() {
        WebSettings s = web.getSettings();
        // Mit Popup-Blocker: neue Fenster laufen ueber onCreateWindow und werden dort geprueft.
        s.setSupportMultipleWindows(popupBlock);
        s.setJavaScriptCanOpenWindowsAutomatically(!popupBlock);
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private static String host(String url) {
        try { return Uri.parse(url).getHost(); } catch (Exception e) { return null; }
    }

    private boolean sameSite(String a, String b) {
        String ha = host(a), hb = host(b);
        return ha != null && hb != null && AdBlocker.baseDomain(ha).equals(AdBlocker.baseDomain(hb));
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            String url = r.getUrl().toString();
            if (adblock && AdBlocker.isAd(r.getUrl().getHost())) return empty();
            String ref = r.getRequestHeaders() != null ? r.getRequestHeaders().get("Referer") : null;
            detect(url, ref, false);
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return true; // intent://, market:// usw. (Werbe-Apps)
            if (!r.isForMainFrame()) return false;
            if (adblock && AdBlocker.isAd(u.getHost())) { popupBlocked(); return true; }
            // Typische Werbe-Umleitung: Skript schickt die Seite ohne Tippen auf eine fremde Domain
            if (popupBlock && !r.hasGesture() && !r.isRedirect() && !currentPageUrl.isEmpty()
                && !sameSite(u.toString(), currentPageUrl)) { popupBlocked(); return true; }
            return false;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            if (!url.equals(currentPageUrl)) { streams.clear(); updateBadge(); }
            currentPageUrl = url;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            String js = "(function(){if(window.__wat)return;window.__wat=1;"
                + (adblock ? "var s=document.createElement('style');s.textContent=" + jsString(AdBlocker.COSMETIC_CSS)
                    + ";(document.head||document.documentElement).appendChild(s);" : "")
                + "var seen={};function scan(){document.querySelectorAll('video,video source').forEach(function(e){"
                + "var u=e.currentSrc||e.src;if(u&&/^https?:/.test(u)&&!seen[u]){seen[u]=1;"
                + "try{WebAppTools.report(u,location.href)}catch(x){}}})}"
                + "setInterval(scan,3000);scan();})();";
            v.evaluateJavascript(js, null);
        }
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = view;
            customCallback = cb;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fab.setVisibility(View.GONE);
            setFullscreenUi(true);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        @Override public void onHideCustomView() { exitFullscreen(); }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // Ziel-URL des Popups abfangen und selbst entscheiden
            final boolean[] handled = {false};
            WebView probe = new WebView(MainActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                private void decide(WebView p, String url) {
                    if (handled[0] || url == null || url.equals("about:blank")) return;
                    handled[0] = true;
                    if (isUserGesture && sameSite(url, currentPageUrl) && !AdBlocker.isAd(host(url))) web.loadUrl(url);
                    else popupBlocked();
                    p.stopLoading();
                    ui.post(p::destroy);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView p, WebResourceRequest r) { decide(p, r.getUrl().toString()); return true; }
                @Override public void onPageStarted(WebView p, String url, Bitmap f) { decide(p, url); }
            });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private void popupBlocked() {
        ui.post(() -> {
            blockedPopups++;
            long now = System.currentTimeMillis();
            if (now - lastPopupToast > 6000) {
                lastPopupToast = now;
                Toast.makeText(this, "Popup/Werbung blockiert", Toast.LENGTH_SHORT).show();
            }
            if (sheet != null && sheet.isShowing()) fillSheet();
        });
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        fab.setVisibility(View.VISIBLE);
        setFullscreenUi(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenUi(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ======================================================================
    // Stream-Erkennung
    private final class Bridge {
        @android.webkit.JavascriptInterface
        public void report(String url, String pageUrl) { detect(url, pageUrl, true); }
    }

    private static String classify(String url) {
        String path = url;
        if (HLS.matcher(path).find()) return "hls";
        if (DASH.matcher(path).find()) return "dash";
        if (VIDEO.matcher(path).find()) return "video";
        return null;
    }

    private void detect(String url, String referer, boolean fromVideoTag) {
        if (url == null || !url.startsWith("http")) return;
        String type = classify(url);
        if (type == null) {
            if (!fromVideoTag) return;
            type = "video";
        }
        for (Stream s : streams) if (s.url.equals(url)) return;
        if (streams.size() >= 50) streams.remove(0);
        streams.add(new Stream(url, type, referer != null ? referer : currentPageUrl));
        ui.post(() -> {
            updateBadge();
            if (sheet != null && sheet.isShowing()) fillSheet();
        });
    }

    // ======================================================================
    // Schwebender Knopf (verschiebbar)
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildFab() {
        fab = new FrameLayout(this);
        TextView icon = new TextView(this);
        icon.setText("⬇");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(22);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ACCENT);
        icon.setBackground(circle);
        fab.addView(icon, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        // Schatten am Container (runde Kontur), sonst schneidet die Transparenz ihn eckig ab
        fab.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(dp(4), dp(4), dp(60), dp(60)); }
        });
        fab.setElevation(dp(6));

        badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(RED, 10));
        badge.setPadding(dp(5), 0, dp(5), 0);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, dp(20), Gravity.TOP | Gravity.END);
        badge.setMinWidth(dp(20));
        fab.addView(badge, bl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(18));
        root.addView(fab, lp);
        fab.setAlpha(0.6f);

        // Verschieben per Ziehen, Tippen oeffnet das Feld
        fab.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startTx, startTy; boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startTx = v.getTranslationX(); startTy = v.getTranslationY();
                        dragging = false;
                        v.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > dp(10)) dragging = true;
                        if (dragging) {
                            View p = (View) v.getParent();
                            float minX = -(v.getLeft()), maxX = p.getWidth() - v.getRight();
                            float minY = -(v.getTop()), maxY = p.getHeight() - v.getBottom();
                            v.setTranslationX(Math.max(minX, Math.min(maxX, startTx + dx)));
                            v.setTranslationY(Math.max(minY, Math.min(maxY, startTy + dy)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            prefs.edit().putFloat("fabX", v.getTranslationX()).putFloat("fabY", v.getTranslationY()).apply();
                        } else {
                            v.performClick();
                            openSheet();
                        }
                        updateBadge();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        updateBadge();
                        return true;
                }
                return false;
            }
        });
        fab.post(() -> {
            fab.setTranslationX(prefs.getFloat("fabX", 0));
            fab.setTranslationY(prefs.getFloat("fabY", 0));
        });
    }

    private void updateBadge() {
        int n = streams.size();
        badge.setText(String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        fab.setAlpha(n > 0 ? 1f : 0.6f);
    }

    // ======================================================================
    // Bedienfeld (Bottom-Sheet)
    private LinearLayout sheetContent;

    private void openSheet() {
        if (sheet == null) {
            sheet = new Dialog(this);
            sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
            ScrollView sv = new ScrollView(this);
            sheetContent = new LinearLayout(this);
            sheetContent.setOrientation(LinearLayout.VERTICAL);
            sheetContent.setPadding(dp(16), dp(10), dp(16), dp(20));
            sv.addView(sheetContent);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            float r = dp(18);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            sv.setBackground(bg);
            sheet.setContentView(sv);
            Window w = sheet.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setGravity(Gravity.BOTTOM);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setWindowAnimations(android.R.style.Animation_InputMethod);
            }
        }
        fillSheet();
        sheet.show();
        Window w = sheet.getWindow();
        if (w != null) {
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
            sheetContent.post(() -> {
                View sv = (View) sheetContent.getParent();
                if (sv.getHeight() > maxH) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
            });
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView b = text(label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(rounded(primary ? ACCENT : BTN, 10));
        b.setOnClickListener(l);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams weighted(int leftMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.leftMargin = dp(leftMarginDp);
        return p;
    }

    private void fillSheet() {
        LinearLayout c = sheetContent;
        c.removeAllViews();

        View handle = new View(this);
        handle.setBackground(rounded(BORDER, 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(12);
        c.addView(handle, hp);

        c.addView(text("Gefundene Streams (" + streams.size() + ")", 18, TEXT, true));

        if (streams.isEmpty()) {
            TextView e = text("Noch kein Stream erkannt – Video starten.", 14, MUTED, false);
            e.setPadding(0, dp(10), 0, dp(10));
            c.addView(e);
        }
        List<Stream> list = new ArrayList<>(streams);
        for (int i = list.size() - 1; i >= 0; i--) {
            final Stream s = list.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cb = rounded(BG, 12);
            cb.setStroke(dp(1), BORDER);
            card.setBackground(cb);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = dp(10);

            card.addView(text(s.label(), 12, 0xFFA9A4FF, true));
            TextView u = text(s.url, 12, MUTED, false);
            u.setMaxLines(2);
            u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            u.setPadding(0, dp(4), 0, dp(10));
            card.addView(u);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(button("Download", true, v -> { sheet.dismiss(); startDownload(s); }), weighted(0));
            row.addView(button("Kopieren", false, v -> copy(s.url)), weighted(8));
            row.addView(button("Öffnen mit", false, v -> openWith(s)), weighted(8));
            card.addView(row);
            c.addView(card, cp);
        }

        // Einstellungen
        View div = new View(this);
        div.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dpar = new LinearLayout.LayoutParams(-1, dp(1));
        dpar.topMargin = dp(16);
        dpar.bottomMargin = dp(6);
        c.addView(div, dpar);

        c.addView(toggle("Werbeblocker", adblock, on -> {
            adblock = on;
            prefs.edit().putBoolean("adblock", on).apply();
            web.reload();
        }));
        c.addView(toggle("Popup-Blocker" + (blockedPopups > 0 ? "  (" + blockedPopups + " blockiert)" : ""), popupBlock, on -> {
            popupBlock = on;
            prefs.edit().putBoolean("popupBlock", on).apply();
            applyPopupSetting();
        }));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(button("Neu laden", false, v -> { sheet.dismiss(); web.reload(); }), weighted(0));
        row.addView(button("Startseite", false, v -> { sheet.dismiss(); web.loadUrl(prefs.getString("url", "about:blank")); }), weighted(8));
        row.addView(button("Seite ändern", false, v -> { sheet.dismiss(); askUrl(false); }), weighted(8));
        c.addView(row);

        LinearLayout upd = new LinearLayout(this);
        upd.setOrientation(LinearLayout.HORIZONTAL);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        upd.setPadding(0, dp(10), 0, 0);
        TextView ver = text("Version " + updater.currentVersion(), 13, MUTED, false);
        upd.addView(ver, new LinearLayout.LayoutParams(0, -2, 1f));
        upd.addView(button("Nach Updates suchen", false, v -> { sheet.dismiss(); updater.check(true); }),
            new LinearLayout.LayoutParams(-2, -2));
        c.addView(upd);
    }

    private interface OnToggle { void set(boolean on); }

    private View toggle(String label, boolean value, OnToggle cb) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(TEXT);
        sw.setTextSize(15);
        sw.setMinHeight(dp(52));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> cb.set(on));
        return sw;
    }

    // ======================================================================
    // Aktionen
    private void copy(String url) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Stream", url));
        Toast.makeText(this, "URL kopiert", Toast.LENGTH_SHORT).show();
    }

    private void openWith(Stream s) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(s.url), "hls".equals(s.type) ? "application/x-mpegURL" : "video/*");
        try {
            startActivity(Intent.createChooser(i, "Öffnen mit"));
        } catch (Exception e) {
            Toast.makeText(this, "Keine passende App (z. B. VLC) gefunden", Toast.LENGTH_LONG).show();
        }
    }

    private String title() {
        String t = web.getTitle();
        return Hls.safeName(t == null || t.startsWith("http") ? "video" : t);
    }

    private void startDownload(Stream s) {
        switch (s.type) {
            case "video":
                String ext = ".mp4";
                java.util.regex.Matcher m = VIDEO.matcher(s.url);
                if (m.find()) ext = "." + m.group(1).toLowerCase(Locale.ROOT);
                downloadFile(s.url, s.referer, title() + ext);
                break;
            case "hls":
                downloadHls(s);
                break;
            default:
                new AlertDialog.Builder(this)
                    .setTitle("DASH-Stream")
                    .setMessage("DASH-Streams (.mpd) können nicht direkt geladen werden. Die URL kann kopiert und z. B. mit yt-dlp geladen werden.")
                    .setPositiveButton("URL kopieren", (d, w) -> copy(s.url))
                    .setNegativeButton("Schließen", null)
                    .show();
        }
    }

    /** Einzelne Datei ueber den Android-Download-Manager (mit Referer + Cookies). */
    private void downloadFile(String url, String referer, String fileName) {
        if (needsStoragePermission(() -> downloadFile(url, referer, fileName))) return;
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (referer != null && !referer.isEmpty()) r.addRequestHeader("Referer", referer);
            r.addRequestHeader("User-Agent", web.getSettings().getUserAgentString());
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.setTitle(fileName);
            r.setDescription("Anime WebApp");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AnimeWebApp/" + Hls.safeName(fileName));
            getSystemService(DownloadManager.class).enqueue(r);
            Toast.makeText(this, "Download gestartet: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** HLS: Qualitaet waehlen (falls Master-Playlist), dann Hintergrund-Dienst starten. */
    private void downloadHls(Stream s) {
        if (needsStoragePermission(() -> downloadHls(s))) return;
        askNotificationPermission();
        final String ua = web.getSettings().getUserAgentString();
        final String title = title();
        Toast.makeText(this, "Lade Playlist …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String text = Hls.fetchText(s.url, s.referer, ua);
                if (!text.contains("#EXTM3U")) throw new Exception("Keine gültige m3u8-Playlist");
                List<Hls.Variant> vs = Hls.parseMaster(text, s.url);
                ui.post(() -> {
                    if (vs.size() <= 1) {
                        startHlsService(vs.isEmpty() ? s.url : vs.get(0).url, s.referer, title, ua);
                        return;
                    }
                    String[] labels = new String[vs.size()];
                    for (int i = 0; i < vs.size(); i++) labels[i] = vs.get(i).label();
                    new AlertDialog.Builder(this)
                        .setTitle("Qualität wählen")
                        .setItems(labels, (d, which) -> startHlsService(vs.get(which).url, s.referer, title, ua))
                        .setNegativeButton("Abbrechen", null)
                        .show();
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void startHlsService(String url, String referer, String title, String ua) {
        Intent i = new Intent(this, HlsDownloadService.class)
            .putExtra(HlsDownloadService.EXTRA_URL, url)
            .putExtra(HlsDownloadService.EXTRA_REFERER, referer)
            .putExtra(HlsDownloadService.EXTRA_TITLE, title)
            .putExtra(HlsDownloadService.EXTRA_UA, ua);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Download gestartet – Fortschritt in der Benachrichtigung", Toast.LENGTH_LONG).show();
    }

    // ======================================================================
    // Berechtigungen
    private boolean needsStoragePermission(Runnable then) {
        if (Build.VERSION.SDK_INT >= 29) return false;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;
        pendingAfterPermission = then;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        return true;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1) {
            Runnable r = pendingAfterPermission;
            pendingAfterPermission = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && r != null) r.run();
            else Toast.makeText(this, "Ohne Speicher-Berechtigung kein Download möglich", Toast.LENGTH_LONG).show();
        }
    }

    // ======================================================================
    // Webseite festlegen
    private void askUrl(boolean firstStart) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString("url", "https://"));
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        final AlertDialog d = new AlertDialog.Builder(this)
            .setTitle(firstStart ? "Welche Webseite soll die App öffnen?" : "Webseite ändern")
            .setView(box)
            .setCancelable(!firstStart)
            .setPositiveButton("Öffnen", (dlg, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty() || url.equals("https://")) { if (firstStart) finish(); return; }
                if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
                prefs.edit().putString("url", url).apply();
                web.loadUrl(url);
            })
            .setNegativeButton(firstStart ? "Beenden" : "Abbrechen", (dlg, w) -> { if (firstStart) finish(); })
            .create();
        // Enter/"Los" auf der Tastatur bestaetigt wie "Öffnen"
        input.setOnEditorActionListener((v, actionId, ev) -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        d.show();
    }
}
