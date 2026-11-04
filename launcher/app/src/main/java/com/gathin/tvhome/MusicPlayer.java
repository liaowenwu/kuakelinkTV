package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import com.gathin.tvhome.cloud.quark.QuarkTvClient;
import com.gathin.tvhome.player.QuarkChunkedDataSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Background audio player for the 听歌 tab. Wraps ExoPlayer with the same Quark chunked data source
 * the video player uses, holds audio focus, and reports progress to the UI via {@link Listener}.
 * The activity owns the lifecycle: call {@link #release()} in onDestroy and {@link #pause()} in onPause.
 */
final class MusicPlayer {

    interface Listener {
        void onSongChanged(int index, QuarkTvClient.MusicSong song);

        void onProgress(long positionMs, long durationMs);

        void onPlayingChanged(boolean playing);

        void onError(String message);

        void onEmbeddedMetadata(String fid, String lyrics, byte[] artwork);
    }

    private static final String TAG = "MusicPlayer";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<QuarkTvClient.MusicSong> queue = new ArrayList<>();
    private ExoPlayer player;
    private Listener listener;
    private int currentIndex = -1;
    private boolean released;
    private int requestId;
    private boolean playRequested;

    ExoPlayer player() { ensurePlayer(); return player; }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) return;
            if (listener != null) {
                listener.onProgress(player.getCurrentPosition(), player.getDuration());
            }
            handler.postDelayed(this, 500);
        }
    };

    MusicPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    List<QuarkTvClient.MusicSong> queue() {
        return queue;
    }

    int currentIndex() {
        return currentIndex;
    }

    QuarkTvClient.MusicSong current() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
    }

    boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    boolean isReady() {
        return player != null;
    }

    void setQueue(List<QuarkTvClient.MusicSong> songs, int startIndex) {
        queue.clear();
        if (songs != null) queue.addAll(songs);
        if (queue.isEmpty()) {
            stop();
            return;
        }
        play(Math.max(0, Math.min(startIndex, queue.size() - 1)));
    }

    void play(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentIndex = index;
        final int request = ++requestId;
        playRequested = true;
        QuarkTvClient.MusicSong song = queue.get(index);
        ensurePlayer();
        player.stop();
        player.clearMediaItems();
        if (listener != null) listener.onSongChanged(index, song);
        QuarkTvClient.getInstance().getPlaybackUrl(song.fid, new QuarkTvClient.Callback<String>() {
            @Override
            public void onSuccess(String url) {
                if (released) return;
                handler.post(() -> {
                    if (released || player == null || request != requestId) return;
                    player.setMediaItem(new MediaItem.Builder().setUri(url).setMediaId(song.fid).build());
                    player.prepare();
                    player.setPlayWhenReady(playRequested);
                });
            }

            @Override
            public void onError(String message) {
                if (released) return;
                handler.post(() -> {
                    if (!released && request == requestId && listener != null) listener.onError(message);
                });
            }
        });
    }

    void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            pause();
        } else {
            playRequested = true;
            player.play();
        }
    }

    void pause() {
        playRequested = false;
        if (player != null) player.pause();
    }

    void resume() {
        if (player != null && currentIndex >= 0) player.play();
    }

    void next() {
        if (queue.isEmpty()) return;
        play((currentIndex + 1) % queue.size());
    }

    void previous() {
        if (queue.isEmpty()) return;
        play((currentIndex - 1 + queue.size()) % queue.size());
    }

    void seekTo(long positionMs) {
        if (player != null) player.seekTo(positionMs);
    }

    void stop() {
        requestId++;
        playRequested = false;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentIndex = -1;
        handler.removeCallbacks(progressTick);
        if (listener != null) listener.onPlayingChanged(false);
    }

    void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        listener = null;
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void ensurePlayer() {
        if (player != null || released) return;
        QuarkChunkedDataSource.Factory dataSourceFactory =
                new QuarkChunkedDataSource.Factory(QuarkTvClient.USER_AGENT);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setAllocator(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setBackBuffer(10_000, true)
                .build();
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus = */ true)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                MediaItem item = player.getCurrentMediaItem();
                if (item == null || current() == null || !current().fid.equals(item.mediaId)) return;
                String text = null;
                byte[] artwork = null;
                for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                    for (int t = 0; t < group.length; t++) {
                        androidx.media3.common.Metadata metadata = group.getTrackFormat(t).metadata;
                        if (metadata == null) continue;
                        for (int i = 0; i < metadata.length(); i++) {
                            androidx.media3.common.Metadata.Entry entry = metadata.get(i);
                            if (entry instanceof androidx.media3.extractor.metadata.vorbis.VorbisComment) {
                                androidx.media3.extractor.metadata.vorbis.VorbisComment tag =
                                        (androidx.media3.extractor.metadata.vorbis.VorbisComment) entry;
                                String key = tag.key.toUpperCase(java.util.Locale.ROOT);
                                if (key.equals("LYRICS") || key.equals("UNSYNCEDLYRICS") || key.equals("SYNCEDLYRICS")) {
                                    if (text == null || !Lyrics.parse(tag.value).isEmpty()) text = tag.value;
                                }
                            } else if (entry instanceof androidx.media3.extractor.metadata.flac.PictureFrame) {
                                androidx.media3.extractor.metadata.flac.PictureFrame picture =
                                        (androidx.media3.extractor.metadata.flac.PictureFrame) entry;
                                if (artwork == null || picture.pictureType == 3) artwork = picture.pictureData;
                            }
                        }
                    }
                }
                if (listener != null) listener.onEmbeddedMetadata(item.mediaId, text, artwork);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (listener != null) listener.onPlayingChanged(isPlaying);
                if (isPlaying) {
                    handler.removeCallbacks(progressTick);
                    handler.post(progressTick);
                } else {
                    handler.removeCallbacks(progressTick);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && !released) next();
                if (state == Player.STATE_READY && listener != null && player != null) {
                    listener.onProgress(player.getCurrentPosition(), player.getDuration());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "playback error", error);
                if (listener != null) {
                    listener.onError(error.getErrorCodeName());
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && listener != null) {
                    QuarkTvClient.MusicSong song = current();
                    if (song != null) listener.onSongChanged(currentIndex, song);
                }
            }
        });
    }
}
