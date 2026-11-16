package de.webapp.anime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimaler Google-Cast-Sender (Cast v2 Protokoll): verbindet sich mit einem Cast-Geraet im WLAN
 * (z. B. NVIDIA Shield), startet den "Default Media Receiver" und spielt eine URL ab.
 */
final class CastClient {
    static final String DEFAULT_RECEIVER = "CC1AD845";
    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECV = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String SENDER = "sender-anime";

    interface Listener {
        void onMediaStatus(JSONObject status);   // MEDIA_STATUS-Eintrag
        void onLoadFailed(String reason);
        void onClosed(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private SSLSocket socket;
    private OutputStream out;
    private volatile boolean closed;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private volatile String transportId, sessionId;
    private volatile int mediaSessionId = -1;
    private final Object launchLock = new Object();

    CastClient(String host, int port, Listener listener) { this.host = host; this.port = port; this.listener = listener; }

    // ---------- Verbindung ----------

    void connect() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        // Cast-Geraete nutzen selbstsignierte Zertifikate; Verbindung nur im lokalen Netz
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
        socket.setSoTimeout(0);
        socket.startHandshake();
        out = socket.getOutputStream();
        send(NS_CONN, "receiver-0", new JSONObject().put("type", "CONNECT"));
        Thread reader = new Thread(this::readLoop, "cast-reader");
        reader.setDaemon(true);
        reader.start();
        Thread heart = new Thread(() -> {
            while (!closed) {
                try { send(NS_HEART, "receiver-0", new JSONObject().put("type", "PING")); Thread.sleep(5000); }
                catch (Exception e) { break; }
            }
        }, "cast-heartbeat");
        heart.setDaemon(true);
        heart.start();
    }

    /** Startet den Standard-Player und wartet auf seine transportId. */
    void launch() throws Exception {
        transportId = null;
        send(NS_RECV, "receiver-0", new JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_RECEIVER).put("requestId", requestId.getAndIncrement()));
        long until = System.currentTimeMillis() + 20000;
        synchronized (launchLock) {
            while (transportId == null && !closed && System.currentTimeMillis() < until) launchLock.wait(500);
        }
        if (transportId == null) throw new IOException(closed ? "Verbindung getrennt" : "Player auf dem Fernseher startet nicht");
        send(NS_CONN, transportId, new JSONObject().put("type", "CONNECT"));
    }

    void load(String url, String contentType, String title, double startAt) throws Exception {
        JSONObject media = new JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("streamType", "BUFFERED")
            .put("contentType", contentType)
            .put("metadata", new JSONObject().put("metadataType", 0).put("title", title == null ? "" : title));
        mediaSessionId = -1;
        sendMedia(new JSONObject().put("type", "LOAD").put("autoplay", true).put("currentTime", Math.max(0, startAt)).put("media", media));
    }

    void play() { sendMediaCmd("PLAY"); }
    void pause() { sendMediaCmd("PAUSE"); }

    void seekBy(double seconds, double current) {
        if (mediaSessionId < 0) return;
        try { sendMedia(new JSONObject().put("type", "SEEK").put("mediaSessionId", mediaSessionId).put("currentTime", Math.max(0, current + seconds))); }
        catch (Exception ignored) {}
    }

    /** Beendet die Wiedergabe auf dem Fernseher (schliesst den Player). */
    void stopApp() {
        try {
            if (sessionId != null) send(NS_RECV, "receiver-0", new JSONObject().put("type", "STOP").put("sessionId", sessionId).put("requestId", requestId.getAndIncrement()));
        } catch (Exception ignored) {}
    }

    void close() {
        closed = true;
        try { socket.close(); } catch (Exception ignored) {}
    }

    boolean isClosed() { return closed; }

    private void sendMediaCmd(String type) {
        if (mediaSessionId < 0) return;
        try { sendMedia(new JSONObject().put("type", type).put("mediaSessionId", mediaSessionId)); } catch (Exception ignored) {}
    }

    private void sendMedia(JSONObject o) throws Exception {
        if (transportId == null) throw new IOException("kein Player");
        o.put("requestId", requestId.getAndIncrement());
        send(NS_MEDIA, transportId, o);
    }

    // ---------- Nachrichten ----------

    private synchronized void send(String ns, String dest, JSONObject payload) throws IOException {
        byte[] msg = encode(SENDER, dest, ns, payload.toString());
        byte[] len = {(byte) (msg.length >>> 24), (byte) (msg.length >>> 16), (byte) (msg.length >>> 8), (byte) msg.length};
        out.write(len);
        out.write(msg);
        out.flush();
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (!closed) {
                int len = in.readInt();
                if (len <= 0 || len > 1 << 20) throw new IOException("ungueltige Nachricht");
                byte[] buf = new byte[len];
                in.readFully(buf);
                String[] f = decode(buf); // [source, dest, namespace, payload]
                if (f[3] == null) continue;
                handle(f[0], f[2], new JSONObject(f[3]));
            }
        } catch (Exception e) {
            if (!closed) { closed = true; listener.onClosed(e.getMessage()); }
        }
    }

    private void handle(String source, String ns, JSONObject p) throws Exception {
        String type = p.optString("type");
        if (NS_HEART.equals(ns) && "PING".equals(type)) {
            send(NS_HEART, source, new JSONObject().put("type", "PONG"));
        } else if (NS_CONN.equals(ns) && "CLOSE".equals(type) && source.equals(transportId)) {
            closed = true;
            listener.onClosed("Player auf dem Fernseher wurde beendet");
            close();
        } else if (NS_RECV.equals(ns) && "RECEIVER_STATUS".equals(type)) {
            JSONArray apps = p.optJSONObject("status") != null ? p.getJSONObject("status").optJSONArray("applications") : null;
            String tid = null, sid = null;
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                if (DEFAULT_RECEIVER.equals(a.optString("appId"))) { tid = a.optString("transportId", null); sid = a.optString("sessionId", null); }
            }
            if (tid == null && transportId != null && !closed) {
                // Unser Player laeuft nicht mehr (z. B. am Fernseher beendet)
                listener.onClosed("Wiedergabe auf dem Fernseher beendet");
            }
            synchronized (launchLock) {
                if (tid != null) { transportId = tid; sessionId = sid; }
                launchLock.notifyAll();
            }
        } else if (NS_MEDIA.equals(ns)) {
            if ("MEDIA_STATUS".equals(type)) {
                JSONArray st = p.optJSONArray("status");
                if (st != null && st.length() > 0) {
                    JSONObject s = st.getJSONObject(0);
                    if (s.has("mediaSessionId")) mediaSessionId = s.getInt("mediaSessionId");
                    listener.onMediaStatus(s);
                }
            } else if ("LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type) || "INVALID_REQUEST".equals(type)) {
                listener.onLoadFailed(type + (p.has("reason") ? " (" + p.optString("reason") + ")" : ""));
            }
        }
    }

    // ---------- Protobuf "CastMessage" (von Hand, nur die benoetigten Felder) ----------

    static byte[] encode(String src, String dst, String ns, String payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08); o.write(0x00);          // 1: protocol_version = CASTV2_1_0
        writeString(o, 0x12, src);             // 2: source_id
        writeString(o, 0x1a, dst);             // 3: destination_id
        writeString(o, 0x22, ns);              // 4: namespace
        o.write(0x28); o.write(0x00);          // 5: payload_type = STRING
        writeString(o, 0x32, payload);         // 6: payload_utf8
        return o.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream o, int tag, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(tag);
        int v = b.length;
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
        o.write(b, 0, b.length);
    }

    static String[] decode(byte[] b) {
        String[] r = new String[4];
        int i = 0;
        while (i < b.length) {
            int key = b[i++] & 0xFF;
            int field = key >>> 3, wire = key & 7;
            if (wire == 0) { while ((b[i++] & 0x80) != 0) { /* varint ueberspringen */ } continue; }
            if (wire != 2) break;
            int len = 0, shift = 0, x;
            do { x = b[i++] & 0xFF; len |= (x & 0x7F) << shift; shift += 7; } while ((x & 0x80) != 0);
            String s = new String(b, i, len, StandardCharsets.UTF_8);
            i += len;
            if (field == 2) r[0] = s; else if (field == 3) r[1] = s; else if (field == 4) r[2] = s; else if (field == 6) r[3] = s;
        }
        return r;
    }
}
