package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.gathin.tvhome.cloud.quark.QuarkTvClient;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.MusicSong;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One searchable song list and a remote-friendly full-screen player. */
final class MusicScreen implements MusicPlayer.Listener {
    private final Activity activity;
    private final View root, detail, playerOverlay;
    private final android.os.Handler controlsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int revealedKey = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            if (canGoBack() && player.current() != null && player.current().video && player.isPlaying()) {
                detail.requestFocus();
                playerOverlay.setVisibility(View.INVISIBLE);
            }
        }
    };
    private final MusicPlayer player;
    private final List<MusicSong> songs;
    private final Runnable focusTabs;
    private final RecyclerView grid;
    private final TextView status, title, artist, position, duration, lyric;
    private final ImageButton play, miniPlay;
    private final SeekBar seek;
    private final ImageView cover;
    private final PlayerView video;
    private final VinylView record;
    private final MusicLibrary library;
    private com.gathin.tvhome.cloud.ui.QuarkAuthDialog authDialog;
    private final SongAdapter adapter = new SongAdapter();
    private List<Lyrics.Line> lyrics = new ArrayList<>();
    private String activeFid = "";
    private boolean externalLyrics, embeddedLyrics, closed;
    private int lyricIndex = Integer.MIN_VALUE, selected;
    private long lastPosition;

    MusicScreen(Activity activity, View root, MusicPlayer player, List<MusicSong> songs, Runnable focusTabs) {
        this.activity = activity; this.root = root; this.player = player; this.songs = songs; this.focusTabs = focusTabs;
        grid = root.findViewById(R.id.musicPlaylist);
        status = root.findViewById(R.id.musicStatus);
        title = root.findViewById(R.id.musicNowTitle); artist = root.findViewById(R.id.musicNowArtist);
        cover = root.findViewById(R.id.musicCover); miniPlay = root.findViewById(R.id.musicMiniPlay);
        detail = activity.findViewById(R.id.musicDetail);
        playerOverlay = detail.findViewById(R.id.musicPlayerOverlay);
        position = detail.findViewById(R.id.musicPos); duration = detail.findViewById(R.id.musicDur);
        seek = detail.findViewById(R.id.musicSeek); play = detail.findViewById(R.id.musicPlayPause);
        play.setFocusableInTouchMode(true);
        video = detail.findViewById(R.id.musicVideo); record = detail.findViewById(R.id.musicDetailArt);
        lyric = detail.findViewById(R.id.musicLyrics);
        video.setOnClickListener(v -> showControls());
        player.setListener(this);
        grid.setLayoutManager(new LinearLayoutManager(activity)); grid.setAdapter(adapter);
        library = new MusicLibrary(activity, (loaded, scanning, errors) -> {
            if (closed) return;
            int old = songs.size();
            boolean append = old <= loaded.size() && (old == 0 || songs.get(0).fid.equals(loaded.get(0).fid));
            songs.clear(); songs.addAll(loaded);
            if (append) adapter.notifyItemRangeInserted(old, songs.size() - old); else adapter.notifyDataSetChanged();
            status.setText(activity.getString(errors > 0 ? R.string.music_partial_count : scanning ? R.string.music_scan_count : R.string.music_ready_count, songs.size()));
            if (songs.isEmpty() && errors > 0) status.setText(R.string.music_load_error);
            if (!QuarkTvClient.getInstance().isLoggedIn()) status.setText(R.string.music_login_hint);
            if (!scanning && songs.isEmpty() && errors == 0) status.setText(R.string.music_empty);
        });
        root.findViewById(R.id.musicRefresh).setOnClickListener(v -> refresh());
        root.findViewById(R.id.musicSearch).setOnClickListener(v -> activity.findViewById(R.id.btnSearch).performClick());
        detail.findViewById(R.id.musicPrev).setOnClickListener(v -> player.previous());
        detail.findViewById(R.id.musicNext).setOnClickListener(v -> player.next());
        View.OnClickListener toggle = v -> { if (player.current() == null && !songs.isEmpty()) player.setQueue(songs, 0); else player.togglePlayPause(); };
        play.setOnClickListener(toggle); miniPlay.setOnClickListener(toggle);
        root.findViewById(R.id.musicOpenDetail).setOnClickListener(v -> openDetail());
        detail.findViewById(R.id.musicCloseDetail).setOnClickListener(v -> goBack());
        seek.setKeyProgressIncrement(10000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) { player.seekTo(value); renderLyrics(value); } }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        seek.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) play.requestFocus(); return true;
            }
            return false;
        });
    }
    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density); }
    void open() { library.open(false); }
    void refresh() {
        root.findViewById(R.id.musicRefresh).requestFocus();
        if (!QuarkTvClient.getInstance().isLoggedIn()) {
            if (authDialog != null && authDialog.isShowing()) return;
            authDialog = new com.gathin.tvhome.cloud.ui.QuarkAuthDialog(activity, () -> library.open(true));
            authDialog.show();
        } else library.open(true);
    }
    void focusContent() {
        if (!songs.isEmpty()) {
            int index = Math.min(selected, songs.size() - 1); grid.scrollToPosition(index);
            grid.post(() -> { RecyclerView.ViewHolder row = grid.findViewHolderForAdapterPosition(index); if (row != null) row.itemView.requestFocus(); });
        } else root.findViewById(R.id.musicRefresh).requestFocus();
    }
    boolean canGoBack() { return detail.getVisibility() == View.VISIBLE; }
    void goBack() {
        controlsHandler.removeCallbacks(hideControls);
        detail.setVisibility(View.GONE); video.setPlayer(null); record.setPlaying(false);
        ((View) activity.findViewById(R.id.modeTabs).getParent()).setVisibility(View.VISIBLE);
        ((ViewGroup) activity.findViewById(R.id.modeTabs).getParent()).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focusContent();
    }
    private void openDetail() {
        if (player.current() == null) { Toast.makeText(activity, R.string.music_select_song, Toast.LENGTH_SHORT).show(); return; }
        detail.setVisibility(View.VISIBLE);
        ((View) activity.findViewById(R.id.modeTabs).getParent()).setVisibility(View.INVISIBLE);
        ((ViewGroup) activity.findViewById(R.id.modeTabs).getParent()).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        showVideo(); play.requestFocus(); record.setPlaying(player.isPlaying());
    }
    private void showVideo() {
        boolean mv = player.current() != null && player.current().video;
        detail.setBackgroundColor(mv ? 0xFF000000 : android.graphics.Color.TRANSPARENT);
        video.setPlayer(canGoBack() ? player.player() : null); video.setVisibility(mv ? View.VISIBLE : View.GONE);
        record.setVisibility(mv ? View.GONE : View.VISIBLE);
        detail.findViewById(R.id.musicAudioBody).setVisibility(mv ? View.INVISIBLE : View.VISIBLE);
        playerOverlay.setBackgroundResource(mv ? R.drawable.music_video_controls_bg : R.drawable.music_player_bg);
        ((TextView) detail.findViewById(R.id.musicPlayerHeading)).setText(mv ? player.current().title : activity.getString(R.string.music_kicker));
        showControls();
    }
    private void showControls() {
        boolean hidden = playerOverlay.getVisibility() != View.VISIBLE;
        playerOverlay.setVisibility(View.VISIBLE);
        if (hidden && canGoBack()) play.requestFocus();
        controlsHandler.removeCallbacks(hideControls);
        if (canGoBack() && player.current() != null && player.current().video && player.isPlaying()) controlsHandler.postDelayed(hideControls, 4000);
    }
    boolean handlePlayerKey(KeyEvent event) {
        if (!canGoBack() || player.current() == null || !player.current().video) return false;
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN
                && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT
                && key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && key == revealedKey) { revealedKey = KeyEvent.KEYCODE_UNKNOWN; return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0 && key == revealedKey) return true;
            boolean hidden = playerOverlay.getVisibility() != View.VISIBLE;
            showControls();
            if (hidden) {
                revealedKey = key;
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) player.togglePlayPause();
                return true;
            }
        }
        return false;
    }
    void onPause() { controlsHandler.removeCallbacks(hideControls); root.setKeepScreenOn(false); detail.setKeepScreenOn(false); record.setPlaying(false); }
    void close() { closed = true; controlsHandler.removeCallbacks(hideControls); library.close(); record.setPlaying(false); if (authDialog != null) authDialog.dismiss(); }

    private final class SongAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0); row.setFocusable(true); row.setClickable(true); row.setBackgroundResource(R.drawable.music_row_bg);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(62)));
            TextView number = new TextView(activity); number.setTextColor(0xFF9AB7C5); number.setTextSize(13); row.addView(number, new LinearLayout.LayoutParams(dp(48), -2));
            LinearLayout text = new LinearLayout(activity); text.setOrientation(LinearLayout.VERTICAL); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView name = new TextView(activity); name.setTextSize(19); name.setTextColor(0xFFEDF3F1); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); text.addView(name);
            TextView meta = new TextView(activity); meta.setTextSize(11); meta.setTextColor(0xFF9FBAC8); meta.setSingleLine(true); meta.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); text.addView(meta);
            TextView format = new TextView(activity); format.setTextColor(0xFFB9CFD3); format.setTextSize(11); format.setGravity(Gravity.END); row.addView(format, new LinearLayout.LayoutParams(dp(66), -2));
            return new RecyclerView.ViewHolder(row) { };
        }
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int index) {
            MusicSong song = songs.get(index); LinearLayout row = (LinearLayout) holder.itemView;
            ((TextView) row.getChildAt(0)).setText(song.fid.equals(activeFid) ? "♫" : String.format(java.util.Locale.ROOT, "%02d", index + 1));
            LinearLayout text = (LinearLayout) row.getChildAt(1);
            ((TextView) text.getChildAt(0)).setText(song.title);
            String album = song.path.substring(0, song.path.lastIndexOf('/')); album = album.substring(album.lastIndexOf('/') + 1);
            ((TextView) text.getChildAt(1)).setText(song.artist.isEmpty() ? album : song.artist + "  ·  " + album);
            ((TextView) row.getChildAt(2)).setText(song.video ? "MV" : song.path.substring(song.path.lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT));
            row.setSelected(song.fid.equals(activeFid));
            row.setOnFocusChangeListener((v, focus) -> { if (focus && holder.getBindingAdapterPosition() >= 0) selected = holder.getBindingAdapterPosition(); });
            row.setOnClickListener(v -> { int at = holder.getBindingAdapterPosition(); if (at >= 0) player.setQueue(songs, at); });
            row.setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (event.getAction() == KeyEvent.ACTION_DOWN) openDetail(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || (key == KeyEvent.KEYCODE_DPAD_UP && holder.getBindingAdapterPosition() == 0)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) root.findViewById(R.id.musicSearch).requestFocus(); return true;
                }
                return false;
            });
        }
        public int getItemCount() { return songs.size(); }
    }

    public void onSongChanged(int index, MusicSong song) {
        String old = activeFid; activeFid = song.fid;
        title.setText(song.title); artist.setText(song.artist.isEmpty() ? (song.video ? "MV" : activity.getString(R.string.music_mode)) : song.artist);
        cover.setImageResource(R.drawable.music_record); record.setArtwork(null);
        ((TextView) detail.findViewById(R.id.musicDetailTitle)).setText(song.title);
        ((TextView) detail.findViewById(R.id.musicDetailArtist)).setText(artist.getText());
        lyrics.clear(); externalLyrics = false; embeddedLyrics = false; lyricIndex = Integer.MIN_VALUE; lastPosition = 0;
        lyric.setText(R.string.music_lyrics_loading); seek.setProgress(0); position.setText("0:00"); duration.setText("0:00");
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).fid.equals(old) || songs.get(i).fid.equals(activeFid)) adapter.notifyItemChanged(i);
            if (songs.get(i).fid.equals(activeFid)) selected = i;
        }
        if (song.video) openDetail(); else if (canGoBack()) showVideo();
        if (song.lyricsFid == null || song.lyricsFid.isEmpty()) return;
        QuarkTvClient.getInstance().getPlaybackUrl(song.lyricsFid, new QuarkTvClient.Callback<String>() {
            public void onSuccess(String url) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(url).openConnection();
                        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                        connection.setRequestProperty("User-Agent", QuarkTvClient.USER_AGENT);
                        String text;
                        try (InputStream input = connection.getInputStream()) {
                            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int count;
                            while ((count = input.read(chunk)) != -1) { if (buffer.size() + count > 1024 * 1024) throw new java.io.IOException("Lyrics too large"); buffer.write(chunk, 0, count); }
                            byte[] bytes = buffer.toByteArray(); text = new String(bytes, StandardCharsets.UTF_8);
                            if (text.indexOf('\uFFFD') >= 0) text = new String(bytes, java.nio.charset.Charset.forName("GB18030"));
                        }
                        final String loaded = text;
                        activity.runOnUiThread(() -> { if (!closed && song.fid.equals(activeFid) && !loaded.trim().isEmpty()) { externalLyrics = true; setLyrics(loaded); } });
                    } catch (Exception e) { activity.runOnUiThread(() -> lyricFailure(song.fid)); }
                    finally { if (connection != null) connection.disconnect(); }
                });
            }
            public void onError(String error) { lyricFailure(song.fid); }
        });
    }
    private void lyricFailure(String fid) { if (!closed && fid.equals(activeFid) && !embeddedLyrics && !externalLyrics) lyric.setText(R.string.music_no_lyrics); }
    private void setLyrics(String text) {
        lyrics = Lyrics.parse(text); lyricIndex = Integer.MIN_VALUE;
        lyric.setText(lyrics.isEmpty() ? text.trim() : "");
        ((ScrollView) detail.findViewById(R.id.musicLyricScroll)).scrollTo(0, 0);
        renderLyrics(lastPosition);
    }
    public void onEmbeddedMetadata(String fid, String text, byte[] artwork) {
        if (closed || !fid.equals(activeFid)) return;
        if (!externalLyrics && text != null && !text.trim().isEmpty()) { embeddedLyrics = true; setLyrics(text); }
        else if (!externalLyrics && !embeddedLyrics && (player.current().lyricsFid == null || player.current().lyricsFid.isEmpty())) lyric.setText(R.string.music_no_lyrics);
        if (artwork != null) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(artwork, 0, artwork.length, options); options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false; Bitmap image = BitmapFactory.decodeByteArray(artwork, 0, artwork.length, options);
            if (image != null) { record.setArtwork(image); cover.setImageBitmap(image); }
        }
    }
    public void onProgress(long value, long total) {
        lastPosition = value; seek.setMax((int) Math.max(1, Math.min(Integer.MAX_VALUE, total))); seek.setProgress((int) Math.max(0, value)); seek.setKeyProgressIncrement(10000);
        position.setText(time(value)); duration.setText(time(total)); renderLyrics(value);
    }
    private void renderLyrics(long value) {
        if (lyrics.isEmpty()) return;
        int at = Lyrics.indexAt(lyrics, value); if (at == lyricIndex) return; lyricIndex = at;
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        for (int i = at - 2; i <= at + 2; i++) {
            if (text.length() > 0) text.append("\n\n");
            int start = text.length(); text.append(i == -1 && at == -1 ? "♪" : i >= 0 && i < lyrics.size() ? lyrics.get(i).text : " ");
            text.setSpan(new android.text.style.ForegroundColorSpan(i == at ? 0xFFF1F6ED : 0xFF9CBBC5), start, text.length(), 0);
            if (i == at) text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, text.length(), 0);
        }
        lyric.setText(text);
    }
    private String time(long ms) { long seconds = Math.max(0, ms) / 1000; return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60); }
    public void onPlayingChanged(boolean playing) {
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play); miniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        root.setKeepScreenOn(playing); detail.setKeepScreenOn(playing); record.setPlaying(playing && canGoBack());
        if (canGoBack()) showControls();
    }
    public void onError(String error) { Toast.makeText(activity, R.string.music_error, Toast.LENGTH_LONG).show(); }
}
