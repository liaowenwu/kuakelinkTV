package com.gathin.tvhome;

import android.content.Context;
import com.gathin.tvhome.cloud.quark.QuarkTvClient;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.MusicSong;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.QuarkFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Incremental, paginated library scan. All callbacks and queue changes run on the main thread. */
final class MusicLibrary {
    interface Listener { void onLibrary(List<MusicSong> songs, boolean scanning, int errors); }
    private final Context context;
    private final Listener listener;
    private final List<MusicSong> songs = new ArrayList<>();
    private final ArrayDeque<String[]> pending = new ArrayDeque<>();
    private final Set<String> visited = new HashSet<>();
    private int active, errors, generation;
    private boolean opened, closed;

    MusicLibrary(Context context, Listener listener) { this.context = context; this.listener = listener; }

    void open(boolean refresh) {
        if (closed || (opened && !refresh)) return;
        opened = true;
        int request = ++generation;
        active = 0; errors = 0; pending.clear(); visited.clear(); songs.clear();
        if (!refresh) {
            try {
                JSONArray saved = new JSONArray(context.getSharedPreferences("music_index", 0).getString("songs", "[]"));
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject s = saved.getJSONObject(i);
                    songs.add(new MusicSong(s.getString("title"), s.getString("artist"), s.getString("path"),
                            s.getString("fid"), null, s.optString("lrc")));
                }
                if (!songs.isEmpty()) { listener.onLibrary(songs, false, 0); return; }
            } catch (Exception ignored) { songs.clear(); }
        }
        listener.onLibrary(songs, true, 0);
        resolve("0", 0, request);
    }

    private void resolve(String fid, int level, int request) {
        QuarkTvClient.getInstance().listFiles(fid, new QuarkTvClient.Callback<List<QuarkFile>>() {
            public void onSuccess(List<QuarkFile> files) {
                if (closed || request != generation) return;
                String name = level == 0 ? "资源库" : "音乐";
                for (QuarkFile f : files) if (f.isDir && f.filename.equals(name)) {
                    if (level == 0) resolve(f.fid, 1, request);
                    else { pending.add(new String[]{f.fid, "/资源库/音乐"}); pump(request); }
                    return;
                }
                listener.onLibrary(songs, false, 1);
            }
            public void onError(String error) { if (!closed && request == generation) listener.onLibrary(songs, false, 1); }
        });
    }

    private void pump(int request) {
        while (active < 3 && !pending.isEmpty()) {
            String[] folder = pending.removeFirst();
            if (!visited.add(folder[0])) continue;
            active++;
            QuarkTvClient.getInstance().listFiles(folder[0], new QuarkTvClient.Callback<List<QuarkFile>>() {
                public void onSuccess(List<QuarkFile> files) {
                    if (closed || request != generation) return;
                    songs.addAll(tracks(folder[1], files));
                    for (QuarkFile f : files) if (f.isDir) pending.add(new String[]{f.fid, folder[1] + "/" + f.filename});
                    finish(request);
                }
                public void onError(String error) { if (!closed && request == generation) { errors++; finish(request); } }
            });
        }
        if (active == 0 && pending.isEmpty()) {
            listener.onLibrary(songs, false, errors);
            if (errors == 0) {
                JSONArray saved = new JSONArray();
                try {
                    for (MusicSong s : songs) saved.put(new JSONObject().put("title", s.title).put("artist", s.artist)
                            .put("path", s.path).put("fid", s.fid).put("lrc", s.lyricsFid));
                    context.getSharedPreferences("music_index", 0).edit().putString("songs", saved.toString()).apply();
                } catch (Exception ignored) { }
            }
        }
    }

    private void finish(int request) {
        active--;
        listener.onLibrary(songs, true, errors);
        pump(request);
    }

    static List<MusicSong> tracks(String path, List<QuarkFile> files) {
        Map<String, String> lyrics = new HashMap<>();
        for (QuarkFile f : files) if (!f.isDir && f.filename.toLowerCase(Locale.ROOT).endsWith(".lrc"))
            lyrics.put(stem(f.filename).toLowerCase(Locale.ROOT), f.fid);
        String artist = "";
        String marker = "/歌手分类/";
        if (path.contains(marker)) artist = path.substring(path.indexOf(marker) + marker.length()).split("/")[0];
        List<MusicSong> result = new ArrayList<>();
        for (QuarkFile f : files) if (!f.isDir && (QuarkTvClient.isAudioFile(f.filename) || QuarkTvClient.isVideoFile(f.filename))) {
            String name = stem(f.filename);
            String title = name;
            if (!artist.isEmpty() && title.endsWith(" - " + artist)) title = title.substring(0, title.length() - artist.length() - 3);
            else if (!artist.isEmpty() && title.startsWith(artist + " - ")) title = title.substring(artist.length() + 3);
            result.add(new MusicSong(title, artist, path + "/" + f.filename, f.fid, null,
                    lyrics.getOrDefault(name.toLowerCase(Locale.ROOT), "")));
        }
        return result;
    }
    private static String stem(String name) { int dot = name.lastIndexOf('.'); return (dot < 0 ? name : name.substring(0, dot)).trim(); }
    void close() { closed = true; generation++; pending.clear(); }
}
