package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.gathin.tvhome.cloud.quark.ImageLoader;
import com.gathin.tvhome.cloud.quark.QuarkTvClient;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.MusicSong;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.QuarkFile;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Music directory browser and persistent transport, sharing one player with its detail view. */
final class MusicScreen implements MusicPlayer.Listener {
    private final Activity activity;
    private final View root;
    private final MusicPlayer player;
    private final List<MusicSong> searchable;
    private final Runnable focusTabs;
    private final RecyclerView grid;
    private final TextView status, pathLabel, title, artist, position, duration, play, lyric;
    private final SeekBar seek;
    private final ImageView cover;
    private final View detail;
    private final PlayerView video;
    private final Map<String, List<QuarkFile>> cache = new HashMap<>();
    private final List<String> ids = new ArrayList<>(), names = new ArrayList<>();
    private final List<MusicSong> tracks = new ArrayList<>();
    private List<Lyrics.Line> lyrics = new ArrayList<>();
    private String activeFid = "";
    private int generation;
    private boolean loading;
    private boolean focusAfterLoad;

    MusicScreen(Activity activity, View root, MusicPlayer player, List<MusicSong> searchable, Runnable focusTabs) {
        this.activity = activity; this.root = root; this.player = player;
        this.searchable = searchable; this.focusTabs = focusTabs;
        grid = root.findViewById(R.id.musicPlaylist);
        status = root.findViewById(R.id.musicStatus);
        pathLabel = root.findViewById(R.id.musicPath);
        title = root.findViewById(R.id.musicNowTitle);
        artist = root.findViewById(R.id.musicNowArtist);
        position = root.findViewById(R.id.musicPos);
        duration = root.findViewById(R.id.musicDur);
        seek = root.findViewById(R.id.musicSeek);
        cover = root.findViewById(R.id.musicCover);
        play = root.findViewById(R.id.musicPlayPause);
        detail = root.findViewById(R.id.musicDetail);
        video = root.findViewById(R.id.musicVideo);
        lyric = root.findViewById(R.id.musicLyrics);
        player.setListener(this);
        grid.setLayoutManager(new GridLayoutManager(activity, 3));
        root.findViewById(R.id.musicBack).setOnClickListener(v -> goBack());
        root.findViewById(R.id.musicRefresh).setOnClickListener(v -> refresh());
        root.findViewById(R.id.musicPrev).setOnClickListener(v -> player.previous());
        root.findViewById(R.id.musicNext).setOnClickListener(v -> player.next());
        play.setOnClickListener(v -> {
            if (player.current() == null && !tracks.isEmpty()) player.setQueue(tracks, 0);
            else player.togglePlayPause();
        });
        root.findViewById(R.id.musicOpenDetail).setOnClickListener(v -> openDetail());
        root.findViewById(R.id.musicCloseDetail).setOnClickListener(v -> goBack());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) player.seekTo(value); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density); }

    void open() {
        if (!ids.isEmpty() || loading) return;
        loading = true;
        status.setText(R.string.music_loading);
        resolve("0", new String[]{"资源库", "音乐"}, 0, ++generation);
    }

    private void resolve(String fid, String[] path, int index, int request) {
        QuarkTvClient.getInstance().listFiles(fid, new QuarkTvClient.Callback<List<QuarkFile>>() {
            public void onSuccess(List<QuarkFile> files) {
                if (activity.isDestroyed() || request != generation) return;
                for (QuarkFile file : files) if (file.isDir && file.filename.equals(path[index])) {
                    if (index + 1 < path.length) resolve(file.fid, path, index + 1, request);
                    else { loading = false; enter(file.fid, activity.getString(R.string.music_library)); }
                    return;
                }
                loading = false; status.setText(R.string.music_empty);
            }
            public void onError(String error) { if (request == generation) { loading = false; status.setText(R.string.music_load_error); } }
        });
    }

    private void enter(String fid, String name) { ids.add(fid); names.add(name); load(); }

    void refresh() {
        cache.clear();
        if (ids.isEmpty()) { loading = false; open(); } else load();
    }

    private void load() {
        int request = ++generation;
        focusAfterLoad = grid.hasFocus();
        if (focusAfterLoad) root.findViewById(R.id.musicRefresh).requestFocus();
        String fid = ids.get(ids.size() - 1);
        pathLabel.setText(String.join("  /  ", names));
        status.setText(R.string.music_loading);
        root.findViewById(R.id.musicBack).setEnabled(ids.size() > 1);
        grid.setAdapter(null);
        if (cache.containsKey(fid)) { render(cache.get(fid)); return; }
        QuarkTvClient.getInstance().listFiles(fid, new QuarkTvClient.Callback<List<QuarkFile>>() {
            public void onSuccess(List<QuarkFile> files) {
                if (activity.isDestroyed() || request != generation) return;
                cache.put(fid, files); render(files);
            }
            public void onError(String error) { if (request == generation) status.setText(R.string.music_load_error); }
        });
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).trim();
    }

    private void render(List<QuarkFile> files) {
        List<QuarkFile> entries = new ArrayList<>();
        tracks.clear();
        Map<String, String> lrc = new HashMap<>();
        String artwork = null;
        for (QuarkFile file : files) {
            if (file.filename.toLowerCase(java.util.Locale.ROOT).endsWith(".lrc")) lrc.put(stem(file.filename).toLowerCase(java.util.Locale.ROOT), file.fid);
            if (file.filename.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpg|png|jpeg|webp)$") && artwork == null) artwork = file.thumbnailUrl;
        }
        String folderPath = "/资源库/音乐" + (names.size() > 1 ? "/" + String.join("/", names.subList(1, names.size())) : "");
        String singer = names.contains("歌手分类") && names.indexOf("歌手分类") + 1 < names.size() ? names.get(names.indexOf("歌手分类") + 1) : "";
        for (QuarkFile file : files) {
            if (file.isDir) entries.add(file);
            else if (QuarkTvClient.isAudioFile(file.filename) || QuarkTvClient.isVideoFile(file.filename)) {
                entries.add(file);
                String name = stem(file.filename);
                MusicSong song = new MusicSong(name, singer, folderPath + "/" + file.filename, file.fid,
                        artwork, lrc.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), ""));
                tracks.add(song);
                boolean known = false;
                for (MusicSong old : searchable) if (old.fid.equals(song.fid)) { known = true; break; }
                if (!known) searchable.add(song);
            }
        }
        status.setText(entries.isEmpty() ? activity.getString(R.string.music_folder_empty)
                : activity.getString(R.string.music_item_count, entries.size()));
        final boolean landing = ids.size() == 1;
        final int columns = landing ? 2 : 3;
        ((GridLayoutManager) grid.getLayoutManager()).setSpanCount(columns);
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(12), dp(18), dp(12));
                row.setBackgroundResource(R.drawable.music_row_bg); row.setFocusable(true); row.setClickable(true);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(landing ? 160 : 92));
                lp.setMargins(dp(4), dp(4), dp(4), dp(4)); row.setLayoutParams(lp);
                TextView name = new TextView(activity); name.setTextSize(landing ? 28 : 18); name.setTextColor(0xFFE9F1F6);
                name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name);
                TextView meta = new TextView(activity); meta.setTextSize(12); meta.setTextColor(0xFFAAC1CF);
                meta.setPadding(0, dp(6), 0, 0); row.addView(meta);
                return new RecyclerView.ViewHolder(row) { };
            }
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int index) {
                QuarkFile file = entries.get(index);
                LinearLayout row = (LinearLayout) holder.itemView;
                ((TextView) row.getChildAt(0)).setText(file.isDir ? file.filename : stem(file.filename));
                ((TextView) row.getChildAt(1)).setText(file.isDir ? activity.getString(R.string.music_browse_folder) : (QuarkTvClient.isVideoFile(file.filename) ? "MV  ·  VIDEO" : "♪  ·  " + file.filename.substring(file.filename.lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT)));
                row.setSelected(file.fid.equals(activeFid));
                row.setOnClickListener(v -> {
                    if (file.isDir) enter(file.fid, file.filename);
                    else for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).fid.equals(file.fid)) { player.setQueue(tracks, i); break; }
                });
                row.setOnKeyListener((v, key, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (key == KeyEvent.KEYCODE_DPAD_UP && holder.getBindingAdapterPosition() < columns) { focusTabs.run(); return true; }
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN && holder.getBindingAdapterPosition() / columns == (entries.size() - 1) / columns) { play.requestFocus(); return true; }
                    return false;
                });
            }
            public int getItemCount() { return entries.size(); }
        });
        if (focusAfterLoad) {
            focusAfterLoad = false;
            grid.post(() -> {
                if (root.isShown() && root.findViewById(R.id.musicRefresh).hasFocus()) focusContent();
            });
        }
    }

    void focusContent() { if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus(); else root.findViewById(R.id.musicRefresh).requestFocus(); }
    boolean canGoBack() { return detail.getVisibility() == View.VISIBLE || ids.size() > 1; }
    void goBack() {
        if (detail.getVisibility() == View.VISIBLE) {
            detail.setVisibility(View.GONE); video.setPlayer(null);
            grid.setVisibility(View.VISIBLE);
            root.findViewById(R.id.musicBack).setFocusable(true);
            root.findViewById(R.id.musicRefresh).setFocusable(true); root.findViewById(R.id.musicOpenDetail).requestFocus();
        } else if (ids.size() > 1) { ids.remove(ids.size() - 1); names.remove(names.size() - 1); load(); }
    }
    private void openDetail() {
        if (player.current() == null) { Toast.makeText(activity, R.string.music_select_song, Toast.LENGTH_SHORT).show(); return; }
        detail.setVisibility(View.VISIBLE);
        grid.setVisibility(View.INVISIBLE);
        root.findViewById(R.id.musicBack).setFocusable(false);
        root.findViewById(R.id.musicRefresh).setFocusable(false);
        video.setPlayer(player.player()); video.setVisibility(player.current().video ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.musicDetailArt).setVisibility(player.current().video ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.musicCloseDetail).requestFocus();
    }
    void onPause() { root.setKeepScreenOn(false); }

    public void onSongChanged(int index, MusicSong song) {
        activeFid = song.fid;
        title.setText(song.title); artist.setText(song.artist.isEmpty() ? (song.video ? "MV" : activity.getString(R.string.music_mode)) : song.artist);
        cover.setImageResource(R.drawable.music_record);
        ImageView detailArt = root.findViewById(R.id.musicDetailArt);
        detailArt.setImageResource(R.drawable.music_record);
        if (song.backgroundUrl != null && !song.backgroundUrl.isEmpty()) { ImageLoader.load(song.backgroundUrl, cover); ImageLoader.load(song.backgroundUrl, detailArt); }
        ((TextView) root.findViewById(R.id.musicDetailTitle)).setText(song.title);
        lyrics.clear(); lyric.setText(R.string.music_no_lyrics);
        seek.setProgress(0); position.setText("0:00"); duration.setText("0:00");
        if (grid.getAdapter() != null) grid.getAdapter().notifyDataSetChanged();
        if (song.video || detail.getVisibility() == View.VISIBLE) openDetail();
        if (song.lyricsFid == null || song.lyricsFid.isEmpty()) return;
        lyric.setText(R.string.music_lyrics_loading);
        QuarkTvClient.getInstance().getPlaybackUrl(song.lyricsFid, new QuarkTvClient.Callback<String>() {
            public void onSuccess(String url) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    String text;
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(url).openConnection();
                        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                        connection.setRequestProperty("User-Agent", QuarkTvClient.USER_AGENT);
                        try (InputStream input = connection.getInputStream()) {
                            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                            byte[] chunk = new byte[4096];
                            int count;
                            while ((count = input.read(chunk)) != -1) {
                                if (buffer.size() + count > 1024 * 1024) throw new java.io.IOException("Lyrics too large");
                                buffer.write(chunk, 0, count);
                            }
                            byte[] bytes = buffer.toByteArray();
                            if (bytes.length > 1024 * 1024) throw new java.io.IOException("Lyrics too large");
                            text = new String(bytes, StandardCharsets.UTF_8);
                            if (text.indexOf('\uFFFD') >= 0) text = new String(bytes, java.nio.charset.Charset.forName("GB18030"));
                        }
                        List<Lyrics.Line> parsed = Lyrics.parse(text);
                        final String plain = text;
                        activity.runOnUiThread(() -> {
                            if (activity.isDestroyed() || !song.fid.equals(activeFid)) return;
                            lyrics = parsed;
                            lyric.setText(parsed.isEmpty() ? plain : parsed.get(0).text);
                        });
                    } catch (Exception e) { activity.runOnUiThread(() -> onError("")); }
                    finally { if (connection != null) connection.disconnect(); }
                });
            }
            public void onError(String error) { if (!activity.isDestroyed() && song.fid.equals(activeFid)) lyric.setText(R.string.music_lyrics_error); }
        });
    }
    public void onProgress(long value, long total) {
        seek.setMax((int) Math.max(1, Math.min(Integer.MAX_VALUE, total))); seek.setProgress((int) Math.max(0, value));
        position.setText(time(value)); duration.setText(time(total));
        if (!lyrics.isEmpty()) {
            int at = Lyrics.indexAt(lyrics, value);
            String previous = at > 0 ? lyrics.get(at - 1).text : "";
            String current = at >= 0 ? lyrics.get(at).text : "♪";
            String next = at + 1 < lyrics.size() ? lyrics.get(at + 1).text : "";
            android.text.SpannableString text = new android.text.SpannableString(previous + "\n\n" + current + "\n\n" + next);
            text.setSpan(new android.text.style.ForegroundColorSpan(0xFFF1C28F), previous.length() + 2, previous.length() + 2 + current.length(), 0);
            lyric.setText(text);
        }
    }
    private String time(long ms) { long seconds = Math.max(0, ms) / 1000; return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60); }
    public void onPlayingChanged(boolean playing) {
        play.setCompoundDrawablesRelativeWithIntrinsicBounds(playing ? R.drawable.ic_pause : R.drawable.ic_play, 0, 0, 0);
        root.setKeepScreenOn(playing);
    }
    public void onError(String error) { Toast.makeText(activity, R.string.music_error, Toast.LENGTH_LONG).show(); }
}
