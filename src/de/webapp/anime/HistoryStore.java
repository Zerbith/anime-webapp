package de.webapp.anime;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verlauf der angesehenen Folgen ("Weiterschauen"). */
final class HistoryStore {
    static final class Entry {
        String url, title, series, seriesKey, siteId;
        int season = -1, episode = -1;
        boolean movie, played;
        long time;

        String label() {
            if (movie) return episode >= 0 ? "Film " + episode : "Film";
            if (season >= 0 && episode >= 0) return "Staffel " + season + " · Folge " + episode;
            if (episode >= 0) return "Folge " + episode;
            return title == null || title.isEmpty() ? url : title;
        }
    }

    private static final int MAX = 300;
    private static final Pattern SEASON = Pattern.compile("(?:staffel|season)-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE = Pattern.compile("(?:episode|folge|ep)-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVIE = Pattern.compile("/filme?/(?:film-(\\d+))?", Pattern.CASE_INSENSITIVE);

    private final SharedPreferences prefs;
    final List<Entry> entries = new ArrayList<>(); // neueste zuerst

    HistoryStore(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            JSONArray a = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.url = o.getString("url");
                e.title = o.optString("title");
                e.series = o.optString("series");
                e.seriesKey = o.optString("key");
                e.siteId = o.optString("site", null);
                e.season = o.optInt("s", -1);
                e.episode = o.optInt("e", -1);
                e.movie = o.optBoolean("movie");
                e.played = o.optBoolean("played");
                e.time = o.optLong("time");
                entries.add(e);
            }
        } catch (Exception ignored) {}
    }

    void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) {
                a.put(new JSONObject().put("url", e.url).put("title", e.title).put("series", e.series).put("key", e.seriesKey)
                    .put("site", e.siteId).put("s", e.season).put("e", e.episode).put("movie", e.movie)
                    .put("played", e.played).put("time", e.time));
            }
        } catch (Exception ignored) {}
        prefs.edit().putString("history", a.toString()).apply();
    }

    /** Sieht die Adresse nach einer einzelnen Folge / einem Film aus? */
    static boolean looksLikeEpisode(String url) {
        if (url == null) return false;
        String p = Uri.parse(url).getPath();
        if (p == null) return false;
        return EPISODE.matcher(p).find() || p.matches("(?i).*/filme?/film-\\d+.*");
    }

    /** Folge speichern bzw. aktualisieren (gleiche Adresse wird nach oben geschoben). */
    Entry record(String url, String pageTitle, String siteId, boolean played) {
        Entry old = null;
        for (Entry e : entries) if (e.url.equals(url)) { old = e; break; }
        Entry e = parse(url, pageTitle);
        e.siteId = siteId;
        e.played = played || (old != null && old.played);
        if (old != null) {
            entries.remove(old);
            if ((pageTitle == null || pageTitle.startsWith("http")) && old.series != null) { e.series = old.series; e.title = old.title; }
        }
        entries.add(0, e);
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        save();
        return e;
    }

    static Entry parse(String url, String pageTitle) {
        Entry e = new Entry();
        e.url = url;
        e.time = System.currentTimeMillis();
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "");
        String path = u.getPath() == null ? "" : u.getPath();
        Matcher m;
        if ((m = SEASON.matcher(path)).find()) e.season = Integer.parseInt(m.group(1));
        if ((m = EPISODE.matcher(path)).find()) e.episode = Integer.parseInt(m.group(1));
        if ((m = MOVIE.matcher(path)).find()) { e.movie = true; if (m.group(1) != null) e.episode = Integer.parseInt(m.group(1)); }

        // Serie = Pfad bis vor "/staffel-…" bzw. "/filme…"
        String seriesPath = path.replaceFirst("(?i)/(staffel|season)-\\d+.*$", "").replaceFirst("(?i)/filme?(/.*)?$", "")
            .replaceFirst("(?i)/(episode|folge|ep)-\\d+.*$", "");
        e.seriesKey = host + seriesPath;
        e.title = pageTitle == null ? "" : pageTitle;

        String name = null;
        if (pageTitle != null && !pageTitle.startsWith("http")) {
            // z. B. "Episode 1 Staffel 2 von Skeleton Knight in Another World | AniWorld.to - …"
            Matcher t = Pattern.compile("\\bvon (.+?)\\s*(\\||$)").matcher(pageTitle);
            if (t.find()) name = t.group(1).trim();
        }
        if (name == null || name.isEmpty()) {
            String slug = seriesPath.replaceAll("/+$", "");
            slug = slug.substring(slug.lastIndexOf('/') + 1);
            name = prettySlug(slug.isEmpty() ? host : slug);
        }
        e.series = name;
        return e;
    }

    static String prettySlug(String slug) {
        StringBuilder sb = new StringBuilder();
        for (String w : slug.replace('_', '-').split("-")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Zuletzt gesehene Folge je Serie (fuer "Weiterschauen"). */
    List<Entry> continueList(int max) {
        List<Entry> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry e : entries) {
            if (e.episode < 0 && !e.movie) continue;
            if (seen.add(e.seriesKey.toLowerCase(Locale.ROOT))) res.add(e);
            if (res.size() >= max) break;
        }
        return res;
    }

    /** Adresse der naechsten Folge (episode-N -> episode-N+1), sonst null. */
    static String nextUrl(Entry e) {
        if (e.movie || e.episode < 0) return null;
        Matcher m = Pattern.compile("((?:episode|folge|ep)-)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(e.url);
        if (!m.find()) return null;
        return e.url.substring(0, m.start()) + m.group(1) + (Integer.parseInt(m.group(2)) + 1) + e.url.substring(m.end());
    }

    void removeSeries(String key) {
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).seriesKey.equalsIgnoreCase(key)) entries.remove(i);
        save();
    }

    void clear() { entries.clear(); save(); }
}
