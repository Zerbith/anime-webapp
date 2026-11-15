package de.webapp.anime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Sucht auf GitHub (Releases) nach einer neueren Version und installiert sie auf Wunsch. */
final class Updater {
    static final String REPO = "Zerbith/anime-webapp";
    private static final long AUTO_INTERVAL = 6L * 60 * 60 * 1000; // hoechstens alle 6 Stunden

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    Updater(Activity act, SharedPreferences prefs) { this.act = act; this.prefs = prefs; }

    String currentVersion() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** -1/0/1 wie compareTo, Versionen wie "1.10.2". */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; }
    }

    void check(boolean manual) {
        if (!manual && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < AUTO_INTERVAL) return;
        if (manual) toast("Suche nach Updates …");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "AnimeWebApp");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code == 404) { if (manual) toast("Noch keine Version auf GitHub veröffentlicht"); return; }
                if (code >= 400) throw new Exception("HTTP " + code);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                JSONObject rel = new JSONObject(out.toString("UTF-8"));
                // Nur erfolgreiche Pruefungen zaehlen (sonst z. B. nach Netzwerkfehler erst in 6 h wieder)
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
                String latest = rel.getString("tag_name").replaceFirst("^[vV]", "");
                String notes = rel.optString("body", "");
                String apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) { apk = a.getString("browser_download_url"); break; }
                }
                String current = currentVersion();
                if (apk != null && compare(latest, current) > 0) {
                    final String url = apk;
                    ui.post(() -> offer(latest, current, notes, url));
                } else if (manual) {
                    toast("Du hast die neueste Version (" + current + ")");
                }
            } catch (Exception e) {
                if (manual) toast("Update-Prüfung fehlgeschlagen: " + e.getMessage());
            }
        }).start();
    }

    private void offer(String latest, String current, String notes, String apkUrl) {
        if (act.isFinishing()) return;
        String msg = "Installiert: " + current + "\nNeu: " + latest;
        if (!notes.trim().isEmpty()) msg += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + " …" : notes.trim());
        new AlertDialog.Builder(act)
            .setTitle("Update verfügbar")
            .setMessage(msg)
            .setPositiveButton("Installieren", (d, w) -> download(latest, apkUrl))
            .setNegativeButton("Später", null)
            .show();
    }

    private void download(String version, String apkUrl) {
        try {
            DownloadManager dm = act.getSystemService(DownloadManager.class);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl));
            r.setTitle("Anime WebApp " + version);
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, "AnimeWebApp-" + version + ".apk");
            final long id = dm.enqueue(r);
            toast("Update wird geladen …");
            BroadcastReceiver done = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try { act.getApplicationContext().unregisterReceiver(this); } catch (Exception ignored) {}
                    Uri file = dm.getUriForDownloadedFile(id);
                    if (file == null) { toast("Update-Download fehlgeschlagen"); return; }
                    install(file, apkUrl);
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            Context app = act.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(done, f, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(done, f);
        } catch (Exception e) {
            openInBrowser(apkUrl);
        }
    }

    private Uri pendingInstall;
    private String pendingFallback;

    /** Aus onResume: Nach dem Erlauben "Unbekannte Apps installieren" automatisch weitermachen. */
    void resumePendingInstall() {
        if (pendingInstall == null || Build.VERSION.SDK_INT < 26) return;
        if (!act.getPackageManager().canRequestPackageInstalls()) return;
        Uri f = pendingInstall; pendingInstall = null;
        install(f, pendingFallback);
    }

    private void install(Uri file, String fallbackUrl) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = file;
            pendingFallback = fallbackUrl;
            toast("Bitte \"Aus dieser Quelle zulassen\" einschalten und zurückkehren – das Update startet dann automatisch.");
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
                return;
            } catch (Exception ignored) { pendingInstall = null; }
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(file, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.getApplicationContext().startActivity(i); }
        catch (Exception e) { openInBrowser(fallbackUrl); }
    }

    private void openInBrowser(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Bitte die neue APK auf github.com/" + REPO + "/releases laden");
        }
    }

    private void toast(String s) { ui.post(() -> Toast.makeText(act, s, Toast.LENGTH_LONG).show()); }
}
