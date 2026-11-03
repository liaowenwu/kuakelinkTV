package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted cinema shelf: favourites (starred from a card) and recently watched items, so the
 * launcher can show them as virtual categories at the top of the cinema row without a network round
 * trip. Stored as JSON in the same prefs file style as the cinema library cache.
 */
final class CinemaShelf {

    static final int MAX_RECENTS = 24;
    static final int MAX_FAVORITES = 99;

    private static final String PREFS = "cinema_shelf";
    private static final String KEY_RECENTS = "recents";
    private static final String KEY_FAVORITES = "favorites";

    private CinemaShelf() {}

    /** One watched/starred entry. Serialized with the same keys as a cached cinema item. */
    static final class Entry {
        final String title;
        final String meta;
        final String path;
        final String fid;
        final boolean isDir;
        final int itemType;
        final long timestamp;

        Entry(String title, String meta, String path, String fid, boolean isDir, int itemType, long timestamp) {
            this.title = title;
            this.meta = meta == null ? "" : meta;
            this.path = path == null ? "" : path;
            this.fid = fid == null ? "" : fid;
            this.isDir = isDir;
            this.itemType = itemType;
            this.timestamp = timestamp;
        }

        String key() {
            return fid != null && !fid.isEmpty() ? fid : path + "|" + title;
        }
    }

    static List<Entry> recents(Context c) {
        return read(c, KEY_RECENTS);
    }

    static List<Entry> favorites(Context c) {
        return read(c, KEY_FAVORITES);
    }

    static boolean isFavorite(Context c, String key) {
        if (key == null) return false;
        for (Entry e : read(c, KEY_FAVORITES)) {
            if (key.equals(e.key())) return true;
        }
        return false;
    }

    /** Record a launch in the recents list (most-recent first, de-duplicated by fid/path). */
    static void recordWatched(Context c, String title, String meta, String path, String fid, boolean isDir, int itemType) {
        List<Entry> recents = read(c, KEY_RECENTS);
        String key = new Entry(title, meta, path, fid, isDir, itemType, 0).key();
        List<Entry> out = new ArrayList<>(MAX_RECENTS);
        out.add(new Entry(title, meta, path, fid, isDir, itemType, System.currentTimeMillis()));
        for (Entry e : recents) {
            if (!e.key().equals(key)) out.add(e);
            if (out.size() >= MAX_RECENTS) break;
        }
        write(c, KEY_RECENTS, out);
    }

    /** Toggle the favourite flag for one entry; returns the new state. */
    static boolean toggleFavorite(Context c, String title, String meta, String path, String fid, boolean isDir, int itemType) {
        List<Entry> favorites = read(c, KEY_FAVORITES);
        String key = new Entry(title, meta, path, fid, isDir, itemType, 0).key();
        List<Entry> out = new ArrayList<>(favorites.size() + 1);
        boolean removed = false;
        for (Entry e : favorites) {
            if (e.key().equals(key)) {
                removed = true;
            } else {
                out.add(e);
            }
        }
        if (!removed) {
            out.add(new Entry(title, meta, path, fid, isDir, itemType, System.currentTimeMillis()));
            while (out.size() > MAX_FAVORITES) out.remove(out.size() - 1);
        }
        write(c, KEY_FAVORITES, out);
        return !removed;
    }

    private static List<Entry> read(Context c, String key) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(key, "[]"));
            List<Entry> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Entry(
                        o.optString("title"),
                        o.optString("meta"),
                        o.optString("path"),
                        o.optString("fid"),
                        o.optBoolean("isDir", false),
                        o.optInt("itemType", 0),
                        o.optLong("ts", 0)));
            }
            return out;
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    private static void write(Context c, String key, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", e.title);
                o.put("meta", e.meta);
                o.put("path", e.path);
                o.put("fid", e.fid);
                o.put("isDir", e.isDir);
                o.put("itemType", e.itemType);
                o.put("ts", e.timestamp);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        prefs(c).edit().putString(key, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
