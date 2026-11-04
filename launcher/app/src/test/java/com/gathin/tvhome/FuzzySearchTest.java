package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FuzzySearchTest {
    @Test public void vowelInitialsFindAnJing() {
        assertTrue(FuzzySearch.matches("aj", "安静"));
        assertTrue(FuzzySearch.matches("ajzjl", "安静 - 周杰伦"));
    }

    @Test
    public void substringMatches() {
        assertTrue(FuzzySearch.matches("鬼灭", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("灭之", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("之刃", "鬼灭之刃 无限列车"));
        assertTrue(FuzzySearch.matches("dune", "Dune Part Two"));
    }

    @Test
    public void pinyinInitialsMatch() {
        assertTrue(FuzzySearch.matches("gmzr", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("gm", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("zr", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("gmzr", "鬼灭之刃 无限列车篇"));
    }

    @Test
    public void polyphoneInitialsMatch() {
        // 乐 is both yue (音乐) and le (快乐) — the table stores both.
        assertTrue(FuzzySearch.matches("yy", "音乐之声"));
        assertTrue(FuzzySearch.matches("kl", "快乐星球"));
    }

    @Test
    public void zhChShCollapseOnQuery() {
        // Typing the digraph "zh"/"ch"/"sh" as an initial matches the table's single letters z/c/s.
        assertTrue(FuzzySearch.matches("zh", "鬼灭之刃"));
        assertTrue(FuzzySearch.matches("ch", "超级战舰"));
        assertTrue(FuzzySearch.matches("sh", "生化危机"));
    }

    @Test
    public void exactBeatsPrefixBeatsContains() {
        int exact = FuzzySearch.score("dune", "Dune");
        int prefix = FuzzySearch.score("dune", "Dune Part Two");
        int contains = FuzzySearch.score("dune", "The Dune Saga");
        assertTrue(exact >= 0 && prefix >= 0 && contains >= 0);
        assertTrue(exact < prefix);
        assertTrue(prefix < contains);
    }

    @Test
    public void substringBeatsPinyin() {
        int substring = FuzzySearch.score("刃", "鬼灭之刃");
        int initials = FuzzySearch.score("zr", "鬼灭之刃");
        assertTrue(substring >= 0 && initials >= 0);
        assertTrue(substring < initials);
    }

    @Test
    public void nonMatches() {
        assertFalse(FuzzySearch.matches("", "鬼灭之刃"));
        assertFalse(FuzzySearch.matches("xyz", "鬼灭之刃"));
        assertFalse(FuzzySearch.matches("abc", "鬼灭之刃"));
        assertFalse(FuzzySearch.matches("gmzrq", "鬼灭之刃"));
    }

    @Test
    public void normalizeStripsPunctuation() {
        assertEquals("thebatman2022", FuzzySearch.normalize("The Batman (2022)"));
        assertEquals("鬼灭之刃", FuzzySearch.normalize("鬼灭之刃!"));
    }

    @Test
    public void scoreIsDeterministic() {
        assertEquals(FuzzySearch.score("gmzr", "鬼灭之刃"), FuzzySearch.score("gmzr", "鬼灭之刃"));
    }
}
