package com.gathin.tvhome;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LyricsTest {
    @Test public void repeatedTagsSortAndHonorOffset() {
        List<Lyrics.Line> lines = Lyrics.parse("[offset:-100]\n[00:02.50][00:01.2]repeat\n[00:03.123]next");
        assertEquals(3, lines.size());
        assertEquals(1100, lines.get(0).time);
        assertEquals(2400, lines.get(1).time);
        assertEquals(3023, lines.get(2).time);
        assertEquals("repeat", lines.get(0).text);
    }
    @Test public void seekingChoosesCurrentLineAndHandlesPrelude() {
        List<Lyrics.Line> lines = Lyrics.parse("[00:02]first\n[00:05]second");
        assertEquals(-1, Lyrics.indexAt(lines, 0));
        assertEquals(0, Lyrics.indexAt(lines, 2000));
        assertEquals(1, Lyrics.indexAt(lines, 9000));
        assertEquals(0, Lyrics.indexAt(lines, 3000));
    }
    @Test public void metadataAndPlainTextAreNotTimedLines() {
        assertTrue(Lyrics.parse("[ar:Someone]\nplain lyrics").isEmpty());
    }
}
