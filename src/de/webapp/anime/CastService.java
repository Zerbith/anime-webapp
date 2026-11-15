package de.webapp.anime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Haelt die Cast-Sitzung zum Fernseher (auch bei gesperrtem Handy), zeigt die Steuerung als
 * Benachrichtigung und leitet den Stream bei Bedarf ueber das Handy (StreamProxy).
 */
public class CastService extends Service {
    static final String EXTRA_HOST = "host", EXTRA_PORT = "port", EXTRA_DEVICE = "device", EXTRA_URL = "url",
        EXTRA_TYPE = "type", EXTRA_REFERER = "referer", EXTRA_UA = "ua", EXTRA_TITLE = "title";
    static final String ACTION_TOGGLE = "de.webapp.anime.CAST_TOGGLE", ACTION_STOP = "de.webapp.anime.CAST_STOP",
        ACTION_FWD = "de.webapp.anime.CAST_FWD", ACTION_BACK = "de.webapp.anime.CAST_BACK";
    private static final String CHANNEL = "cast";
    private static final int NOTIF_ID = 3001;

    /** Fuer die Anzeige in der App: auf welchem Geraet gerade abgespielt wird (oder null). */
    static volatile String activeDevice;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private CastClient client;
    private StreamProxy proxy;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager nm;
    private String device = "Fernseher", title = "", state = "Verbinde …";
    private volatile String playerState = "";
    private volatile double currentTime;
    private volatile boolean viaPhone;
    private volatile int attempt; // 0 = direkt, 1 = ueber Handy
    private final Object waitLock = new Object();
    private volatile String waitResult;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Auf Fernseher", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) { if (client != null) { if ("PLAYING".equals(playerState) || "BUFFERING".equals(playerState)) client.pause(); else client.play(); } return START_NOT_STICKY; }
        if (ACTION_FWD.equals(action)) { if (client != null) client.seekBy(30, currentTime); return START_NOT_STICKY; }
        if (ACTION_BACK.equals(action)) { if (client != null) client.seekBy(-10, currentTime); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> { if (client != null) client.stopApp(); ui.post(this::shutdown); }).start();
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        // Neue Uebertragung (eine evtl. laufende wird ersetzt)
        teardown();
        device = intent.getStringExtra(EXTRA_DEVICE);
        title = intent.getStringExtra(EXTRA_TITLE);
        state = "Verbinde …";
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
        activeDevice = device;

        final String host = intent.getStringExtra(EXTRA_HOST);
        final int port = intent.getIntExtra(EXTRA_PORT, 8009);
        final String url = intent.getStringExtra(EXTRA_URL);
        final String type = intent.getStringExtra(EXTRA_TYPE);
        final String ref = intent.getStringExtra(EXTRA_REFERER);
        final String ua = intent.getStringExtra(EXTRA_UA);
        new Thread(() -> run(host, port, url, type, ref, ua), "cast-start").start();
        return START_NOT_STICKY;
    }

    private void run(String host, int port, String url, String type, String ref, String ua) {
        String contentType = "hls".equals(type) ? "application/x-mpegurl" : "dash".equals(type) ? "application/dash+xml" : "video/mp4";
        try {
            client = new CastClient(host, port, new CastClient.Listener() {
                @Override public void onMediaStatus(JSONObject s) {
                    playerState = s.optString("playerState", playerState);
                    if (s.has("currentTime")) currentTime = s.optDouble("currentTime", currentTime);
                    String idle = s.optString("idleReason", "");
                    if ("PLAYING".equals(playerState)) signal("ok");
                    if ("IDLE".equals(playerState) && "ERROR".equals(idle)) signal("error");
                    if ("IDLE".equals(playerState) && "FINISHED".equals(idle)) { ui.post(() -> shutdownWithToast("Folge zu Ende")); return; }
                    update(label());
                }
                @Override public void onLoadFailed(String reason) { signal("error"); }
                @Override public void onClosed(String reason) { signal("closed"); ui.post(() -> shutdownWithToast(reason == null ? "Verbindung zum Fernseher getrennt" : reason)); }
            });
            update("Verbinde mit " + device + " …");
            client.connect();
            update("Starte Player auf " + device + " …");
            client.launch();

            // 1. Versuch: Fernseher laedt den Stream direkt (Handy danach komplett frei)
            attempt = 0;
            update("Lade Video …");
            String r = loadAndWait(url, contentType);
            if ("ok".equals(r)) { update(label()); return; }
            if ("closed".equals(r)) return;

            // 2. Versuch: ueber das Handy weiterleiten (mit Referer/Cookies, CORS erlaubt)
            attempt = 1;
            update("Direkt nicht möglich – leite über das Handy weiter …");
            proxy = new StreamProxy(ref, ua);
            proxy.start();
            viaPhone = true;
            acquireLocks();
            r = loadAndWait(proxy.wrap(url), contentType);
            if ("ok".equals(r)) { update(label()); return; }
            if (!"closed".equals(r)) ui.post(() -> shutdownWithToast("Dieser Stream lässt sich nicht auf dem Fernseher abspielen"));
        } catch (Exception e) {
            ui.post(() -> shutdownWithToast("Übertragung fehlgeschlagen: " + e.getMessage()));
        }
    }

    private String loadAndWait(String url, String contentType) throws Exception {
        waitResult = null;
        client.load(url, contentType, title);
        long until = System.currentTimeMillis() + 30000;
        synchronized (waitLock) {
            while (waitResult == null && System.currentTimeMillis() < until) waitLock.wait(500);
        }
        return waitResult == null ? "timeout" : waitResult;
    }

    private void signal(String r) {
        synchronized (waitLock) { if (waitResult == null) { waitResult = r; waitLock.notifyAll(); } }
    }

    private String label() {
        String s;
        switch (playerState) {
            case "PLAYING": s = "Läuft"; break;
            case "PAUSED": s = "Pausiert"; break;
            case "BUFFERING": s = "Lädt …"; break;
            default: s = playerState.isEmpty() ? "Verbunden" : playerState;
        }
        return viaPhone ? s + " · über Handy weitergeleitet (im WLAN lassen)" : s + " · Handy kann gesperrt werden";
    }

    private void acquireLocks() {
        // Weiterleitung muss auch bei gesperrtem Bildschirm weiterlaufen
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnimeWebApp:cast");
        wakeLock.acquire(6L * 60 * 60 * 1000);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AnimeWebApp:cast");
        wifiLock.acquire();
    }

    // ---------- Benachrichtigung ----------

    private void update(String s) {
        state = s;
        ui.post(() -> { if (activeDevice != null) nm.notify(NOTIF_ID, notification()); });
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, CastService.class).setAction(a),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean playing = "PLAYING".equals(playerState) || "BUFFERING".equals(playerState);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_cast)
            .setContentTitle((title == null || title.isEmpty() ? "Video" : title) + " → " + device)
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .addAction(new Notification.Action.Builder(null, "−10 s", action(ACTION_BACK, 11)).build())
            .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Abspielen", action(ACTION_TOGGLE, 12)).build())
            .addAction(new Notification.Action.Builder(null, "+30 s", action(ACTION_FWD, 13)).build())
            .addAction(new Notification.Action.Builder(null, "Beenden", action(ACTION_STOP, 14)).build())
            .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(1, 3));
        return b.build();
    }

    // ---------- Aufraeumen ----------

    private void teardown() {
        if (client != null) { client.close(); client = null; }
        if (proxy != null) { proxy.stop(); proxy = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null; wifiLock = null;
        viaPhone = false;
        playerState = "";
    }

    private void shutdownWithToast(String msg) {
        if (activeDevice == null) return;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        shutdown();
    }

    private void shutdown() {
        activeDevice = null;
        teardown();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        activeDevice = null;
        teardown();
        super.onDestroy();
    }
}
