package com.gathin.tvhome;

import com.gathin.tvhome.cloud.quark.QuarkTvClient.MusicSong;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.QuarkFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MusicLibraryTest {
    private QuarkFile file(String name, String fid) { return new QuarkFile(fid, "folder", name, 1, false, "", ""); }
    @Test public void associatesLyricsBeyondFirstHundredEntriesAndIgnoresPromoArtwork() {
        List<QuarkFile> files = new ArrayList<>();
        files.add(file("安静 - 周杰伦.flac", "song"));
        for (int i = 0; i < 110; i++) files.add(file("other" + i + ".txt", "other" + i));
        files.add(file("安静 - 周杰伦.LRC", "lyric"));
        files.add(file("promo.jpg", "image"));
        List<MusicSong> songs = MusicLibrary.tracks("/资源库/音乐/歌手分类/周杰伦/范特西", files);
        assertEquals(1, songs.size());
        assertEquals("lyric", songs.get(0).lyricsFid);
        assertEquals("周杰伦", songs.get(0).artist);
        assertEquals("安静", songs.get(0).title);
        assertNull(songs.get(0).backgroundUrl);
    }
    @Test public void keepsMusicAndVideoAndAssociatesExactStem() {
        List<MusicSong> songs = MusicLibrary.tracks("/资源库/音乐/类型分类", List.of(
                file("Fade.mp4", "video"), file("Fade remix.lrc", "wrong"), file("Song.mp3", "audio")));
        assertEquals(2, songs.size()); assertTrue(songs.get(0).video); assertFalse(songs.get(1).video);
        assertEquals("", songs.get(0).lyricsFid);
    }
}
