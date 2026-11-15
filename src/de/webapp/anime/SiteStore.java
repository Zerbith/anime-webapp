package de.webapp.anime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Gespeicherte Webseiten (Name, Adresse, Vorschaubild) fuer den Startbildschirm. */
final class SiteStore {
    static final class Site {
        String id, name, url;
        long thumbTime;
        Site(String id, String name, String url, long thumbTime) { this.id = id; this.name = name; this.url = url; this.thumbTime = thumbTime; }
    }

    private final SharedPreferences prefs;
    private final File thumbDir;
    final List<Site> sites = new ArrayList<>();

    SiteStore(Context ctx, SharedPreferences prefs) {
        this.prefs = prefs;
        this.thumbDir = new File(ctx.getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        thumbDir.mkdirs();
        load();
    }

    private void load() {
        sites.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("sites", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                sites.add(new Site(o.getString("id"), o.optString("name"), o.getString("url"), o.optLong("thumbTime")));
            }
        } catch (Exception ignored) {}
        // Uebernahme aus Version <= 1.4 (nur eine Seite unter "url")
        String old = prefs.getString("url", null);
        if (sites.isEmpty() && old != null) {
            add(nameFromUrl(old), old);
            prefs.edit().remove("url").apply();
        }
    }

    void save() {
        JSONArray a = new JSONArray();
        try {
            for (Site s : sites) {
                a.put(new JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("thumbTime", s.thumbTime));
            }
        } catch (Exception ignored) {}
        prefs.edit().putString("sites", a.toString()).apply();
    }

    Site add(String name, String url) {
        Site s = new Site(UUID.randomUUID().toString(), name, url, 0);
        sites.add(s);
        save();
        return s;
    }

    void remove(Site s) {
        sites.remove(s);
        //noinspection ResultOfMethodCallIgnored
        thumbFile(s).delete();
        save();
    }

    Site byId(String id) {
        for (Site s : sites) if (s.id.equals(id)) return s;
        return null;
    }

    File thumbFile(Site s) { return new File(thumbDir, s.id + ".jpg"); }

    Bitmap thumb(Site s) {
        File f = thumbFile(s);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    void saveThumb(Site s, Bitmap b) {
        try (FileOutputStream out = new FileOutputStream(thumbFile(s))) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            s.thumbTime = System.currentTimeMillis();
            save();
        } catch (Exception ignored) {}
    }

    static String normalizeUrl(String url) {
        url = url.trim().replaceAll("\\s+", "");
        // doppelte Angaben wie "https://http://seite.de" bereinigen
        while (url.matches("(?i)^https?:/*https?:/*.*")) url = url.replaceFirst("(?i)^https?:/*", "");
        url = url.replaceFirst("(?i)^(https?):/*", "$1://");
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        return url;
    }

    static String nameFromUrl(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return url;
            h = h.replaceFirst("^www\\.", "");
            int dot = h.lastIndexOf('.');
            String base = dot > 0 ? h.substring(0, dot) : h;
            return base.isEmpty() ? h : Character.toUpperCase(base.charAt(0)) + base.substring(1);
        } catch (Exception e) { return url; }
    }
}
