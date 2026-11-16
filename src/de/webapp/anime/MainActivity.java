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
    private SiteStore store;
    private HistoryStore history;
    private SiteStore.Site currentSite;
    private FrameLayout picker;
    private LinearLayout pickerContent;
    private boolean clearHistoryPending;
    private volatile boolean pageError;
    private android.widget.ImageView castBtn, castFull;
    private FrameLayout fsWrap;
    private LinearLayout fsButtons;
    private TextView zoomBtn;
    private boolean fsControlsVisible;
    private boolean fillScreen;

    // ======================================================================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("webapp", MODE_PRIVATE);
        adblock = prefs.getBoolean("adblock", true);
        popupBlock = prefs.getBoolean("popupBlock", true);
        fillScreen = prefs.getBoolean("fillScreen", true);

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

        store = new SiteStore(this, prefs);
        history = new HistoryStore(prefs);
        updater = new Updater(this, prefs);
        buildPicker();
        String savedSite = saved != null ? saved.getString("site") : null;
        if (savedSite != null && store.byId(savedSite) != null) {
            currentSite = store.byId(savedSite);
            web.restoreState(saved);
            picker.setVisibility(View.GONE);
        } else {
            showPicker();
        }
        updater.loadBlocklist();
        if (!store.sites.isEmpty()) updater.check(false); // nicht ueber den "Seite hinzufuegen"-Dialog legen
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (picker != null && picker.getVisibility() == View.VISIBLE) fillPicker(); // Spaltenzahl anpassen
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (currentSite != null && picker.getVisibility() != View.VISIBLE) { web.saveState(out); out.putString("site", currentSite.id); }
    }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); if (updater != null) updater.resumePendingInstall(); }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (sheet != null && sheet.isShowing()) { sheet.dismiss(); return; }
        if (picker.getVisibility() == View.VISIBLE) { super.onBackPressed(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        showPicker(); // ganz zurueck -> Seitenauswahl
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
            if (adblock && AdBlocker.isAdRequest(r.getUrl().getHost(), r.getUrl().getPath(), host(currentPageUrl))) return empty();
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
        public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
            if (r.isForMainFrame()) pageError = true; // keine Vorschaubilder von Fehlerseiten
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            pageError = false;
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
            if (clearHistoryPending && !"about:blank".equals(url)) { clearHistoryPending = false; v.clearHistory(); }
            maybeCaptureThumb(url);
            // Folgenseite besucht -> in den Verlauf
            if (currentSite != null && !pageError && HistoryStore.looksLikeEpisode(url)) recordHistory(url, false);
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
            // Huelle um das Vollbild: bemerkt jedes Antippen (ohne es dem Player wegzunehmen)
            fsWrap = new FrameLayout(MainActivity.this) {
                @Override public boolean dispatchTouchEvent(MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) onFullscreenTouch();
                    return super.dispatchTouchEvent(e);
                }
            };
            fsWrap.setBackgroundColor(Color.BLACK);
            fsWrap.addView(view, new FrameLayout.LayoutParams(-1, -1));
            root.addView(fsWrap, new FrameLayout.LayoutParams(-1, -1));
            fsWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) applyZoom(false); });

            // Knoepfe oben rechts: Bild fuellen/anpassen + Cast (blenden sich wie die Player-Steuerung aus)
            fsButtons = new LinearLayout(MainActivity.this);
            fsButtons.setOrientation(LinearLayout.HORIZONTAL);
            fsButtons.setGravity(Gravity.CENTER_VERTICAL);
            zoomBtn = text("", 13, Color.WHITE, true);
            zoomBtn.setGravity(Gravity.CENTER);
            zoomBtn.setPadding(dp(14), 0, dp(14), 0);
            zoomBtn.setBackground(rounded(0x99000000, 24));
            zoomBtn.setOnClickListener(v -> {
                fillScreen = !fillScreen;
                prefs.edit().putBoolean("fillScreen", fillScreen).apply();
                updateZoomLabel();
                onFullscreenTouch();
            });
            updateZoomLabel();
            fsButtons.addView(zoomBtn, new LinearLayout.LayoutParams(-2, dp(44)));
            castFull = castIcon(48, 0x99000000);
            LinearLayout.LayoutParams cfp = new LinearLayout.LayoutParams(dp(48), dp(48));
            cfp.leftMargin = dp(10);
            fsButtons.addView(castFull, cfp);
            FrameLayout.LayoutParams fbl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            fbl.setMargins(0, dp(16), dp(16), 0);
            root.addView(fsButtons, fbl);
            onFullscreenTouch(); // Knoepfe kurz zeigen, dann ausblenden + zoomen
            fab.setVisibility(View.GONE);
            if (castBtn != null) castBtn.setVisibility(View.GONE);
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
        ui.removeCallbacks(hideFsControls);
        if (fsWrap != null) { fsWrap.removeAllViews(); root.removeView(fsWrap); fsWrap = null; }
        if (fsButtons != null) { root.removeView(fsButtons); fsButtons = null; }
        castFull = null;
        zoomBtn = null;
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setFabVisible(true);
        setFullscreenUi(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // ---------- Cast-Knopf ueber dem Hauptknopf: nach 4 s ohne Beruehrung fast ausblenden ----------
    private final Runnable dimCastBtn = () -> { if (castBtn != null) castBtn.animate().alpha(0.25f).setDuration(400).start(); };

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && castBtn != null && castBtn.getVisibility() == View.VISIBLE) {
            castBtn.animate().cancel();
            castBtn.setAlpha(1f);
            ui.removeCallbacks(dimCastBtn);
            ui.postDelayed(dimCastBtn, 4000);
        }
        return super.dispatchTouchEvent(e);
    }

    // ---------- Vollbild: Bild fuellen + automatisch ausblendende Knoepfe ----------

    private final Runnable hideFsControls = () -> {
        fsControlsVisible = false;
        if (fsButtons != null) fsButtons.animate().alpha(0f).setDuration(300)
            .withEndAction(() -> { if (fsButtons != null && !fsControlsVisible) fsButtons.setVisibility(View.INVISIBLE); }).start();
        applyZoom(true);
    };

    /** Antippen im Vollbild: Knoepfe zeigen, ganzes Bild (Player-Steuerung sichtbar), nach 3,5 s wieder ausblenden. */
    private void onFullscreenTouch() {
        fsControlsVisible = true;
        if (fsButtons != null) {
            fsButtons.animate().cancel();
            fsButtons.setVisibility(View.VISIBLE);
            fsButtons.animate().alpha(1f).setDuration(150).start();
        }
        applyZoom(true);
        ui.removeCallbacks(hideFsControls);
        ui.postDelayed(hideFsControls, 3500);
    }

    /** "Bild fuellen": 16:9-Video auf breite Handy-Bildschirme vergroessern (keine schwarzen Balken). */
    private void applyZoom(boolean animate) {
        if (customView == null || fsWrap == null) return;
        float s = 1f;
        int w = fsWrap.getWidth(), h = fsWrap.getHeight();
        if (fillScreen && !fsControlsVisible && w > h && h > 0) {
            float ar = (float) w / h, video = 16f / 9f;
            if (ar > video + 0.02f) s = ar / video;
        }
        customView.animate().scaleX(s).scaleY(s).setDuration(animate ? 220 : 0).start();
    }

    private void updateZoomLabel() {
        if (zoomBtn != null) zoomBtn.setText(fillScreen ? "⤡  Ganzes Bild" : "⤢  Bild füllen");
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
        // Video laeuft auf dieser Seite -> als angesehen im Verlauf merken
        final String page = currentPageUrl;
        ui.post(() -> { if (currentSite != null && page.startsWith("http") && page.equals(currentPageUrl)) recordHistory(page, true); });
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
                            followFab();
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
        // Kleiner Cast-Knopf ueber dem Hauptknopf (sichtbar, sobald ein Video laeuft)
        castBtn = castIcon(46, 0xE62C2A36);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM | Gravity.END);
        cl.setMargins(0, 0, dp(14 + 9), dp(18 + 64 + 10));
        root.addView(castBtn, cl);
        castBtn.setVisibility(View.GONE);

        fab.post(() -> {
            fab.setTranslationX(prefs.getFloat("fabX", 0));
            fab.setTranslationY(prefs.getFloat("fabY", 0));
            followFab();
        });
    }

    private void followFab() {
        if (castBtn == null) return;
        castBtn.setTranslationX(fab.getTranslationX());
        castBtn.setTranslationY(Math.max(-castBtn.getTop(), fab.getTranslationY()));
    }

    /** Runder Knopf mit Cast-Symbol, oeffnet die Bildschirmuebertragung. */
    private android.widget.ImageView castIcon(int sizeDp, int bgColor) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setImageResource(R.drawable.ic_cast);
        int pad = dp(sizeDp * 0.24f);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(bgColor);
        b.setBackground(circle);
        b.setElevation(dp(4));
        b.setContentDescription("Auf Fernseher übertragen");
        b.setOnClickListener(v -> startCast());
        return b;
    }

    private void setFabVisible(boolean on) {
        fab.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { fab.bringToFront(); castBtn.bringToFront(); }
        updateBadge();
    }

    private void updateBadge() {
        int n = streams.size();
        badge.setText(String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        fab.setAlpha(n > 0 ? 1f : 0.6f);
        if (castBtn != null) {
            boolean show = n > 0 && fab.getVisibility() == View.VISIBLE;
            if (show && castBtn.getVisibility() != View.VISIBLE) {
                castBtn.setAlpha(1f);
                ui.removeCallbacks(dimCastBtn);
                ui.postDelayed(dimCastBtn, 4000);
            }
            castBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ======================================================================
    // Auf Fernseher uebertragen (Androids Bildschirmuebertragung / Smart View)
    /** Bester Stream fuer den Fernseher: HLS-Master (zuerst erkannt) > DASH > Videodatei. */
    private Stream bestStream() {
        for (Stream s : streams) if ("hls".equals(s.type)) return s;
        for (Stream s : streams) if ("dash".equals(s.type)) return s;
        Stream last = null;
        for (Stream s : streams) if ("video".equals(s.type)) last = s;
        return last;
    }

    /** Geraeteauswahl: Cast-Geraete im WLAN (Shield, Chromecast, ...) + Bildschirm spiegeln. */
    private void startCast() {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        final Stream stream = bestStream();

        final List<String[]> devices = new ArrayList<>(); // {Name, Host, Port}
        final List<String> labels = new ArrayList<>();
        final String mirrorLabel = "📱  Bildschirm spiegeln (ganzes Handy)";
        labels.add(mirrorLabel);
        final android.widget.ArrayAdapter<String> adapter =
            new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView status = text(stream == null
            ? "Hinweis: Noch kein Stream erkannt – starte zuerst das Video auf der Seite."
            : "Suche Geräte im WLAN …", 14, stream == null ? 0xFFF0B429 : MUTED, false);
        status.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(status);
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));

        final android.net.nsd.NsdManager nsd = getSystemService(android.net.nsd.NsdManager.class);
        final java.util.ArrayDeque<android.net.nsd.NsdServiceInfo> toResolve = new java.util.ArrayDeque<>();
        final boolean[] resolving = {false}, active = {true};
        final Runnable[] resolveNext = new Runnable[1];
        resolveNext[0] = () -> {
            if (!active[0] || resolving[0] || toResolve.isEmpty()) return;
            resolving[0] = true;
            nsd.resolveService(toResolve.poll(), new android.net.nsd.NsdManager.ResolveListener() {
                @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo si, int err) { ui.post(() -> { resolving[0] = false; resolveNext[0].run(); }); }
                @Override public void onServiceResolved(android.net.nsd.NsdServiceInfo si) {
                    String name = si.getServiceName();
                    boolean videoCapable = true;
                    try {
                        byte[] fn = si.getAttributes().get("fn");
                        if (fn != null) name = new String(fn, "UTF-8");
                        byte[] ca = si.getAttributes().get("ca"); // Cast-Faehigkeiten, Bit 0 = Videoausgabe
                        if (ca != null) videoCapable = (Integer.parseInt(new String(ca, "UTF-8").trim()) & 1) != 0;
                    } catch (Exception ignored) {}
                    if (!videoCapable) { ui.post(() -> { resolving[0] = false; resolveNext[0].run(); }); return; }
                    final String n = name, host = si.getHost().getHostAddress();
                    final int port = si.getPort();
                    ui.post(() -> {
                        resolving[0] = false;
                        boolean known = false;
                        for (String[] d : devices) if (d[1].equals(host)) known = true;
                        if (!known && active[0]) {
                            devices.add(new String[]{n, host, String.valueOf(port)});
                            labels.add(labels.size() - 1, "📺  " + n);
                            adapter.notifyDataSetChanged();
                            if (stream != null) status.setText("Gerät auswählen:");
                        }
                        resolveNext[0].run();
                    });
                }
            });
        };
        final android.net.nsd.NsdManager.DiscoveryListener disc = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { ui.post(() -> status.setText("Gerätesuche nicht möglich (WLAN an?)")); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(android.net.nsd.NsdServiceInfo si) { ui.post(() -> { toResolve.add(si); resolveNext[0].run(); }); }
            @Override public void onServiceLost(android.net.nsd.NsdServiceInfo si) {}
        };
        try { nsd.discoverServices("_googlecast._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, disc); } catch (Exception ignored) {}
        ui.postDelayed(() -> { if (active[0] && devices.isEmpty() && stream != null)
            status.setText("Kein Cast-Gerät gefunden. Ist die Shield an und im selben WLAN?"); }, 8000);

        final AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("Auf Fernseher abspielen")
            .setView(box)
            .setNegativeButton("Abbrechen", null)
            .create();
        d.setOnDismissListener(x -> {
            active[0] = false;
            try { nsd.stopServiceDiscovery(disc); } catch (Exception ignored) {}
        });
        list.setOnItemClickListener((parent, view, pos, id) -> {
            String label = labels.get(pos);
            d.dismiss();
            if (mirrorLabel.equals(label)) { mirrorScreen(); return; }
            String[] dev = devices.get(pos);
            if (stream == null) {
                Toast.makeText(this, "Starte zuerst das Video auf der Seite, dann erneut übertragen", Toast.LENGTH_LONG).show();
                return;
            }
            castTo(dev[0], dev[1], Integer.parseInt(dev[2]), stream);
        });
        d.show();
    }

    private void castTo(String name, String host, int port, Stream s) {
        askNotificationPermission();
        Intent i = new Intent(this, CastService.class)
            .putExtra(CastService.EXTRA_DEVICE, name)
            .putExtra(CastService.EXTRA_HOST, host)
            .putExtra(CastService.EXTRA_PORT, port)
            .putExtra(CastService.EXTRA_URL, s.url)
            .putExtra(CastService.EXTRA_TYPE, s.type)
            .putExtra(CastService.EXTRA_REFERER, s.referer)
            .putExtra(CastService.EXTRA_UA, web.getSettings().getUserAgentString())
            .putExtra(CastService.EXTRA_TITLE, title());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        // Wiedergabe am Handy anhalten
        if (customView != null) exitFullscreen();
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null);
        Toast.makeText(this, "Wird an " + name + " gesendet – Steuerung in der Benachrichtigung", Toast.LENGTH_LONG).show();
    }

    private void stopCast() {
        startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
    }

    private void mirrorScreen() {
        if (!prefs.getBoolean("castTipShown", false)) {
            prefs.edit().putBoolean("castTipShown", true).apply();
            new AlertDialog.Builder(this)
                .setTitle("Bildschirm spiegeln")
                .setMessage("Gleich öffnet sich die Bildschirmübertragung von Android. Wähle dort deine Shield aus.\n\n"
                    + "Achtung: Dabei wird das ganze Handy gespiegelt – das Handy muss an und in der App bleiben. "
                    + "Besser ist, die Shield direkt in der Liste auszuwählen.")
                .setPositiveButton("Weiter", (d, w) -> openCastSettings())
                .setNegativeButton("Abbrechen", null)
                .show();
            return;
        }
        openCastSettings();
    }

    private void openCastSettings() {
        // 1. Samsung Smart View
        Intent samsung = getPackageManager().getLaunchIntentForPackage("com.samsung.android.smartmirroring");
        if (samsung != null) {
            try { startActivity(samsung); return; } catch (Exception ignored) {}
        }
        // 2. Android "Bildschirm uebertragen" (Pixel, Xiaomi, Motorola, ...)
        for (String action : new String[]{"android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS"}) {
            try { startActivity(new Intent(action)); return; } catch (Exception ignored) {}
        }
        // 3. Hilfe, falls das Geraet keine dieser Seiten hat
        new AlertDialog.Builder(this)
            .setTitle("Bildschirm übertragen")
            .setMessage("Ziehe die Schnelleinstellungen von oben herunter und tippe auf „Smart View“, „Bildschirm übertragen“, "
                + "„Cast“ oder „Screen Mirroring“. Wähle dann deine Shield aus.")
            .setPositiveButton("OK", null)
            .show();
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

        // Auf Fernseher abspielen (bzw. laufende Uebertragung beenden)
        final String castDev = CastService.activeDevice;
        TextView castB = button(castDev != null ? "📺  Läuft auf " + castDev + " – beenden" : "📺  Auf Fernseher abspielen", castDev == null,
            v -> { if (castDev != null) { sheet.dismiss(); stopCast(); } else startCast(); });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, -2);
        cbp.bottomMargin = dp(14);
        c.addView(castB, cbp);
        TextView histB = button("🕘  Verlauf / Weiterschauen", false, v -> showHistory());
        LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(-1, -2);
        hbp.bottomMargin = dp(14);
        c.addView(histB, hbp);

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
        row.addView(button("Startseite", false, v -> { sheet.dismiss(); if (currentSite != null) web.loadUrl(currentSite.url); }), weighted(8));
        row.addView(button("Seitenauswahl", false, v -> { sheet.dismiss(); showPicker(); }), weighted(8));
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
    // Startbildschirm: gespeicherte Seiten als Kacheln
    private static final int[] TILE_COLORS = {0xFF5B4BDB, 0xFF2F80ED, 0xFF27AE60, 0xFFE2711D, 0xFFD6336C, 0xFF0CA678, 0xFF7048E8};

    private void buildPicker() {
        picker = new FrameLayout(this);
        picker.setBackgroundColor(BG);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        pickerContent = new LinearLayout(this);
        pickerContent.setOrientation(LinearLayout.VERTICAL);
        pickerContent.setPadding(dp(16), dp(20), dp(16), dp(24));
        sv.addView(pickerContent);
        picker.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(picker, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showPicker() {
        if (customView != null) exitFullscreen();
        currentSite = null;
        web.stopLoading();
        web.loadUrl("about:blank"); // Wiedergabe/Ton der alten Seite beenden
        streams.clear();
        updateBadge();
        fillPicker();
        picker.setVisibility(View.VISIBLE);
        picker.bringToFront();
        setFabVisible(false);
    }

    private void recordHistory(String url, boolean played) {
        // Nur Folgen/Filme merken – Startseiten mit Trailern usw. nicht
        if (!HistoryStore.looksLikeEpisode(url)) return;
        history.record(url, web.getTitle(), currentSite != null ? currentSite.id : null, played);
    }

    /** Eine Folge aus dem Verlauf oeffnen (passende gespeicherte Seite suchen). */
    private void openHistory(String url, String siteId) {
        SiteStore.Site s = siteId != null ? store.byId(siteId) : null;
        if (s == null) for (SiteStore.Site x : store.sites) if (sameSite(x.url, url)) { s = x; break; }
        if (s == null) s = new SiteStore.Site("tmp", SiteStore.nameFromUrl(url), url, 0);
        openSite(s, url);
    }

    private void openSite(SiteStore.Site s) { openSite(s, null); }

    private void openSite(SiteStore.Site s, String startUrl) {
        currentSite = s;
        prefs.edit().putString("lastSite", s.id).apply();
        streams.clear();
        updateBadge();
        currentPageUrl = "";
        clearHistoryPending = true; // "Zurueck" soll nicht in die vorige Seite fuehren
        picker.setVisibility(View.GONE);
        setFabVisible(true);
        web.loadUrl(startUrl != null ? startUrl : s.url);
    }

    private void fillPicker() {
        LinearLayout c = pickerContent;
        c.removeAllViews();

        // ---- Weiterschauen (zuletzt gesehene Folge je Serie) ----
        List<HistoryStore.Entry> cont = history.continueList(15);
        if (!cont.isEmpty()) {
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text("Weiterschauen", 22, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView all = text("Verlauf ›", 14, 0xFFA9A4FF, true);
            all.setPadding(dp(12), dp(8), 0, dp(8));
            all.setOnClickListener(v -> showHistory());
            head.addView(all);
            c.addView(head);

            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, dp(4));
            for (int i = 0; i < cont.size(); i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(250), -2);
                if (i > 0) p.leftMargin = dp(12);
                row.addView(continueCard(cont.get(i)), p);
            }
            hs.addView(row);
            c.addView(hs);
            View sp = new View(this);
            c.addView(sp, new LinearLayout.LayoutParams(-1, dp(22)));
        }

        TextView title = text("Meine Seiten", 26, TEXT, true);
        c.addView(title);
        TextView sub = text(store.sites.isEmpty()
            ? "Füge deine erste Webseite hinzu."
            : "Antippen zum Öffnen · gedrückt halten zum Bearbeiten", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(18));
        c.addView(sub);

        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 32;
        int cols = Math.max(2, Math.min(5, widthDp / 170));
        int gap = dp(12);
        int tileW = (getResources().getDisplayMetrics().widthPixels - dp(32) - gap * (cols - 1)) / cols;

        List<View> tiles = new ArrayList<>();
        for (SiteStore.Site s : store.sites) tiles.add(siteTile(s, tileW));
        tiles.add(addTile(tileW));

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = gap;
                c.addView(row, rp);
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tileW, -2);
            if (i % cols != 0) tp.leftMargin = gap;
            row.addView(tiles.get(i), tp);
        }

        TextView ver = text("Version " + (updater != null ? updater.currentVersion() : ""), 12, MUTED, false);
        ver.setPadding(0, dp(28), 0, 0);
        ver.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(ver);
    }

    /** "gerade eben", "vor 5 Min.", "vor 2 Std.", "gestern", "vor 3 Tagen", sonst Datum. */
    private String ago(long time) {
        long min = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (min < 1) return "gerade eben";
        if (min < 60) return "vor " + min + " Min.";
        long h = min / 60;
        if (h < 24) return "vor " + h + " Std.";
        long d = h / 24;
        if (d == 1) return "gestern";
        if (d < 7) return "vor " + d + " Tagen";
        return new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new java.util.Date(time));
    }

    private String siteName(HistoryStore.Entry e) {
        SiteStore.Site s = e.siteId != null ? store.byId(e.siteId) : null;
        if (s == null) for (SiteStore.Site x : store.sites) if (sameSite(x.url, e.url)) { s = x; break; }
        return s != null ? s.name : SiteStore.nameFromUrl(e.url);
    }

    /** Karte "Weiterschauen": Serie, Staffel/Folge, wann, Weiter-/Naechste-Knoepfe. */
    private View continueCard(HistoryStore.Entry e) {
        LinearLayout t = tileFrame();
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        // farbiger Streifen mit Anfangsbuchstaben als "Bild"
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badgeL = text(e.series.isEmpty() ? "?" : e.series.substring(0, 1).toUpperCase(Locale.ROOT), 20, Color.WHITE, true);
        badgeL.setGravity(Gravity.CENTER);
        badgeL.setBackground(rounded(TILE_COLORS[Math.abs(e.seriesKey.hashCode()) % TILE_COLORS.length], 10));
        top.addView(badgeL, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, 0, 0);
        TextView name = text(e.series, 15, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(text(e.label(), 13, 0xFFA9A4FF, true));
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        t.addView(top);
        TextView meta = text(ago(e.time) + " · " + siteName(e) + (e.played ? "" : " · nur geöffnet"), 12, MUTED, false);
        meta.setPadding(0, dp(8), 0, dp(10));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("▶  Weiter", true, v -> openHistory(e.url, e.siteId)), weighted(0));
        final String next = HistoryStore.nextUrl(e);
        if (next != null) btns.addView(button("Nächste ›", false, v -> openHistory(next, e.siteId)), weighted(8));
        t.addView(btns);

        t.setOnClickListener(v -> openHistory(e.url, e.siteId));
        t.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(e.series)
                .setMessage("Diese Serie aus „Weiterschauen“ und dem Verlauf entfernen?")
                .setPositiveButton("Entfernen", (d, w) -> { history.removeSeries(e.seriesKey); fillPicker(); })
                .setNegativeButton("Abbrechen", null)
                .show();
            return true;
        });
        return t;
    }

    /** Kompletter Verlauf (neueste zuerst). */
    private void showHistory() {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        if (history.entries.isEmpty()) {
            Toast.makeText(this, "Noch nichts angesehen", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<HistoryStore.Entry> list = new ArrayList<>(history.entries);
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            HistoryStore.Entry e = list.get(i);
            items[i] = e.series + " – " + e.label() + "\n" + ago(e.time) + " · " + siteName(e);
        }
        new AlertDialog.Builder(this)
            .setTitle("Verlauf")
            .setItems(items, (d, which) -> openHistory(list.get(which).url, list.get(which).siteId))
            .setNeutralButton("Verlauf löschen", (d, w) -> new AlertDialog.Builder(this)
                .setTitle("Verlauf löschen?")
                .setMessage("Alle gemerkten Folgen werden entfernt.")
                .setPositiveButton("Löschen", (d2, w2) -> { history.clear(); if (picker.getVisibility() == View.VISIBLE) fillPicker(); })
                .setNegativeButton("Abbrechen", null)
                .show())
            .setNegativeButton("Schließen", null)
            .show();
    }

    private LinearLayout tileFrame() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(CARD, 14);
        bg.setStroke(dp(1), BORDER);
        t.setBackground(bg);
        t.setClipToOutline(true);
        GradientDrawable mask = rounded(Color.WHITE, 14);
        t.setForeground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, mask));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private View siteTile(SiteStore.Site s, int tileW) {
        LinearLayout t = tileFrame();
        int imgH = tileW * 10 / 16;
        Bitmap thumb = store.thumb(s);
        if (thumb != null) {
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(thumb);
            t.addView(img, new LinearLayout.LayoutParams(-1, imgH));
        } else {
            TextView ph = text(s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(Locale.ROOT), 40, Color.WHITE, true);
            ph.setGravity(Gravity.CENTER);
            ph.setBackgroundColor(TILE_COLORS[Math.abs(s.id.hashCode()) % TILE_COLORS.length]);
            t.addView(ph, new LinearLayout.LayoutParams(-1, imgH));
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(10), dp(12), dp(12));
        TextView name = text(s.name, 16, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        TextView h = text(host(s.url) == null ? s.url : host(s.url).replaceFirst("^www\\.", ""), 12, MUTED, false);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(h);
        t.addView(info);
        t.setOnClickListener(v -> openSite(s));
        t.setOnLongClickListener(v -> { siteMenu(s); return true; });
        return t;
    }

    private View addTile(int tileW) {
        LinearLayout t = tileFrame();
        GradientDrawable bg = rounded(BG, 14);
        bg.setStroke(dp(2), BORDER, dp(8), dp(6));
        t.setBackground(bg);
        t.setGravity(Gravity.CENTER);
        int imgH = tileW * 10 / 16;
        TextView plus = text("+", 44, 0xFFA9A4FF, false);
        plus.setGravity(Gravity.CENTER);
        t.addView(plus, new LinearLayout.LayoutParams(-1, imgH));
        TextView label = text("Webseite hinzufügen", 15, TEXT, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), 0, dp(8), dp(22));
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> editSite(null));
        return t;
    }

    private void siteMenu(SiteStore.Site s) {
        String[] items = {"Öffnen", "Bearbeiten", "Vorschaubild neu aufnehmen", "Nach vorne verschieben", "Löschen"};
        new AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: openSite(s); break;
                    case 1: editSite(s); break;
                    case 2:
                        s.thumbTime = 0;
                        //noinspection ResultOfMethodCallIgnored
                        store.thumbFile(s).delete();
                        store.save();
                        Toast.makeText(this, "Wird beim nächsten Öffnen neu aufgenommen", Toast.LENGTH_SHORT).show();
                        fillPicker();
                        break;
                    case 3:
                        store.sites.remove(s);
                        store.sites.add(0, s);
                        store.save();
                        fillPicker();
                        break;
                    default:
                        new AlertDialog.Builder(this)
                            .setTitle("Löschen?")
                            .setMessage("\"" + s.name + "\" aus der Liste entfernen?")
                            .setPositiveButton("Löschen", (d2, w2) -> { store.remove(s); fillPicker(); })
                            .setNegativeButton("Abbrechen", null)
                            .show();
                }
            })
            .show();
    }

    /** Neue Seite hinzufuegen (s == null) oder bestehende bearbeiten. */
    private void editSite(SiteStore.Site s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText url = new EditText(this);
        url.setHint("Adresse, z. B. aniworld.to");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setSingleLine(true);
        url.setText(s != null ? s.url : "");
        url.setSelection(url.getText().length());
        url.requestFocus();
        final EditText name = new EditText(this);
        name.setHint("Name (optional)");
        name.setSingleLine(true);
        name.setText(s != null ? s.name : "");
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        box.addView(url);
        box.addView(name);
        final AlertDialog d = new AlertDialog.Builder(this)
            .setTitle(s == null ? "Webseite hinzufügen" : "Webseite bearbeiten")
            .setView(box)
            .setPositiveButton(s == null ? "Hinzufügen" : "Speichern", null)
            .setNegativeButton("Abbrechen", null)
            .create();
        Runnable save = () -> {
            String u = url.getText().toString().trim();
            if (u.isEmpty() || u.equals("https://") || u.equals("http://")) { url.setError("Bitte eine Adresse eingeben"); return; }
            u = SiteStore.normalizeUrl(u);
            String n = name.getText().toString().trim();
            if (n.isEmpty()) n = SiteStore.nameFromUrl(u);
            d.dismiss();
            if (s == null) {
                openSite(store.add(n, u)); // neue Seite gleich oeffnen
            } else {
                boolean urlChanged = !u.equals(s.url);
                s.name = n;
                s.url = u;
                if (urlChanged) { s.thumbTime = 0; store.thumbFile(s).delete(); }
                store.save();
                fillPicker();
            }
        };
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.run()));
        name.setOnEditorActionListener((v, actionId, ev) -> { save.run(); return true; });
        d.show();
    }

    /** Vorschaubild der Seite aufnehmen (einmal pro Tag), sobald sie geladen ist. */
    private void maybeCaptureThumb(String url) {
        final SiteStore.Site s = currentSite;
        if (s == null || url == null || pageError || !url.startsWith("http") || !sameSite(url, s.url)) return;
        if (System.currentTimeMillis() - s.thumbTime < 24L * 60 * 60 * 1000 && store.thumbFile(s).exists()) return;
        ui.postDelayed(() -> {
            if (currentSite != s || picker.getVisibility() == View.VISIBLE || customView != null) return;
            int w = web.getWidth(), h = web.getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = 480f / w;
            int bh = Math.min(h, w * 10 / 16);
            try {
                Bitmap b = Bitmap.createBitmap(480, Math.round(bh * scale), Bitmap.Config.RGB_565);
                android.graphics.Canvas cv = new android.graphics.Canvas(b);
                cv.scale(scale, scale);
                cv.translate(-web.getScrollX(), -web.getScrollY());
                web.draw(cv);
                store.saveThumb(s, b);
            } catch (Throwable ignored) {}
        }, 3500);
    }
}
