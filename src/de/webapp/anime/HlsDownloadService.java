package de.webapp.anime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Laedt HLS-Streams im Hintergrund (Vordergrund-Dienst mit Fortschritts-Benachrichtigung). */
public class HlsDownloadService extends Service {
    static final String EXTRA_URL = "url", EXTRA_REFERER = "referer", EXTRA_TITLE = "title", EXTRA_UA = "ua";
    static final String ACTION_CANCEL = "de.webapp.anime.CANCEL";
    private static final String CHANNEL = "downloads";
    private static final int NOTIF_ID = 1001;
    private static final String SUBDIR = "AnimeWebApp";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NotificationManager nm;
    private volatile boolean cancelled;
    private int pending;
    private int doneNotifId = 2000;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        Notification n = progressNotification("Download wird vorbereitet …", 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
        if (intent == null) return START_NOT_STICKY;
        final String url = intent.getStringExtra(EXTRA_URL);
        final String ref = intent.getStringExtra(EXTRA_REFERER);
        final String title = Hls.safeName(intent.getStringExtra(EXTRA_TITLE));
        final String ua = intent.getStringExtra(EXTRA_UA);
        synchronized (this) { pending++; }
        worker.execute(() -> {
            cancelled = false;
            try {
                run(url, ref, title, ua);
            } catch (Exception e) {
                finished(cancelled ? "Download abgebrochen" : "Download fehlgeschlagen", cancelled ? title : (title + ": " + e.getMessage()), null);
            } finally {
                boolean last;
                synchronized (this) { last = --pending == 0; }
                if (last) {
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void run(String url, String ref, String title, String ua) throws Exception {
        String text = Hls.fetchText(url, ref, ua);
        // Falls doch eine Master-Playlist uebergeben wurde: beste Qualitaet nehmen
        java.util.List<Hls.Variant> vs = Hls.parseMaster(text, url);
        if (!vs.isEmpty()) { url = vs.get(0).url; text = Hls.fetchText(url, ref, ua); }
        Hls.Media m = Hls.parseMedia(text, url);
        if (m.encrypted) throw new IOException("Stream ist verschlüsselt – nicht unterstützt");
        if (m.segments.isEmpty()) throw new IOException("Keine Segmente gefunden");

        String ext = m.init != null ? "mp4" : "ts";
        String fileName = title + "." + ext;
        // Bei "video/mp2t" haengt Android ".m2ts" an den Namen; daher neutraler Typ fuer .ts
        Output out = openOutput(fileName, m.init != null ? "video/mp4" : "application/octet-stream");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        boolean ok = false;
        try {
            if (m.init != null) out.stream.write(Hls.fetch(m.init, ref, ua));
            final String fRef = ref, fUa = ua;
            ArrayDeque<Future<byte[]>> window = new ArrayDeque<>();
            int next = 0, done = 0, total = m.segments.size();
            long bytes = 0, lastUpdate = 0;
            while (done < total) {
                while (next < total && window.size() < 8) {
                    final String seg = m.segments.get(next++);
                    window.add(pool.submit(() -> Hls.fetch(seg, fRef, fUa)));
                }
                if (cancelled) throw new IOException("abgebrochen");
                byte[] data = window.poll().get();
                out.stream.write(data);
                bytes += data.length;
                done++;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 700 || done == total) {
                    lastUpdate = now;
                    nm.notify(NOTIF_ID, progressNotification(
                        String.format(java.util.Locale.GERMANY, "%s – %d/%d (%.1f MB)", title, done, total, bytes / 1048576.0),
                        done * 100 / total, false));
                }
            }
            ok = true;
        } finally {
            pool.shutdownNow();
            out.close(ok);
        }
        finished("Download fertig", fileName, out.uri);
    }

    // ---------- Ausgabe in den Download-Ordner ----------

    private final class Output {
        OutputStream stream; Uri uri; File file;
        void close(boolean ok) {
            try { stream.close(); } catch (IOException ignored) {}
            ContentResolver cr = getContentResolver();
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, v, null, null);
                } else {
                    cr.delete(uri, null, null);
                    uri = null;
                }
            } else if (file != null && !ok) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private Output openOutput(String name, String mime) throws IOException {
        Output o = new Output();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            o.uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (o.uri == null) throw new IOException("Datei konnte nicht angelegt werden");
            o.stream = getContentResolver().openOutputStream(o.uri);
            if (o.stream == null) throw new IOException("Datei konnte nicht geöffnet werden");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            int i = 1;
            while (f.exists()) f = new File(dir, name.replaceFirst("(\\.[^.]+)$", " (" + (i++) + ")$1"));
            o.file = f;
            o.stream = new FileOutputStream(f);
        }
        return o;
    }

    // ---------- Benachrichtigungen ----------

    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    }

    private Notification progressNotification(String text, int percent, boolean indeterminate) {
        Intent cancel = new Intent(this, HlsDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent pi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stream-Download")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(new Notification.Action.Builder(null, "Abbrechen", pi).build());
        return b.build();
    }

    private void finished(String title, String text, Uri uri) {
        Notification.Builder b = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true);
        if (uri != null) {
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(this, doneNotifId, open, PendingIntent.FLAG_IMMUTABLE));
        }
        nm.notify(doneNotifId++, b.build());
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
