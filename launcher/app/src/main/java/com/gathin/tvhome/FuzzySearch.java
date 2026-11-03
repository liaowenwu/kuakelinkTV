package com.gathin.tvhome;

/**
 * Fuzzy matching for the cinema and music searches: a plain substring match (case, spaces and
 * punctuation ignored) plus a pinyin-initials match, so a remote can find 鬼灭之刃 by typing "gmzr".
 * Pure Java so it runs in the JVM unit tests ({@code FuzzySearchTest}).
 */
final class FuzzySearch {

    /** Match tiers, lower is better; {@link #score} multiplies them so a plain substring wins. */
    static final int TIER_EXACT = 0;
    static final int TIER_PREFIX = 1;
    static final int TIER_CONTAINS = 2;
    static final int TIER_INITIALS_PREFIX = 3;
    static final int TIER_INITIALS_CONTAINS = 4;
    static final int TIER_INITIALS_SUBSEQUENCE = 5;

    private FuzzySearch() {}

    /** Lower is better, or -1 when the query does not match at all. */
    static int score(String query, String title) {
        String q = normalize(query);
        if (q.isEmpty()) return -1;
        String t = normalize(title);
        if (t.isEmpty()) return -1;

        if (t.equals(q)) return tier(TIER_EXACT, t);
        if (t.startsWith(q)) return tier(TIER_PREFIX, t);
        if (t.contains(q)) return tier(TIER_CONTAINS, t);

        String[] sets = initialsSets(t);
        String iq = initialsQuery(q);
        if (iq.isEmpty() || sets.length == 0) return -1;
        if (spansAt(sets, iq, 0)) return tier(TIER_INITIALS_PREFIX, t);
        if (containsSets(sets, iq)) return tier(TIER_INITIALS_CONTAINS, t);
        if (subsequence(sets, iq)) return tier(TIER_INITIALS_SUBSEQUENCE, t);
        return -1;
    }

    static boolean matches(String query, String title) {
        return score(query, title) >= 0;
    }

    private static int tier(int tier, String normalizedTitle) {
        return tier * 1000 + Math.min(normalizedTitle.length(), 999);
    }

    /** Lower-cased title with spaces, punctuation and symbols stripped; letters, digits and hanzi stay. */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Query for the initials path: normalized, with zh/ch/sh collapsed to single letters so "zhir"
     * still matches 之刃 ("zr") the way the table stores it.
     */
    static String initialsQuery(String normalized) {
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == 'z' && i + 1 < normalized.length() && normalized.charAt(i + 1) == 'h') { sb.append('z'); i++; }
            else if (c == 'c' && i + 1 < normalized.length() && normalized.charAt(i + 1) == 'h') { sb.append('c'); i++; }
            else if (c == 's' && i + 1 < normalized.length() && normalized.charAt(i + 1) == 'h') { sb.append('s'); i++; }
            else if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * One entry per title character: the letters a query character may match there. Latin letters and
     * digits match themselves; hanzi carry their pinyin initials (polyphones carry several). Hanzi the
     * table does not know are dropped, so they neither match nor break the chain.
     */
    static String[] initialsSets(String normalizedTitle) {
        java.util.ArrayList<String> sets = new java.util.ArrayList<>(normalizedTitle.length());
        for (int i = 0; i < normalizedTitle.length(); i++) {
            char c = normalizedTitle.charAt(i);
            if (c < 128) {
                sets.add(String.valueOf(c));
            } else {
                String ini = Pinyin.initials(c);
                if (!ini.isEmpty()) sets.add(ini);
            }
        }
        return sets.toArray(new String[0]);
    }

    /** True when the query matches the sets starting exactly at {@code from}. */
    private static boolean spansAt(String[] sets, String query, int from) {
        if (from + query.length() > sets.length) return false;
        for (int j = 0; j < query.length(); j++) {
            if (sets[from + j].indexOf(query.charAt(j)) < 0) return false;
        }
        return true;
    }

    private static boolean containsSets(String[] sets, String query) {
        for (int i = 0; i + query.length() <= sets.length; i++) {
            if (spansAt(sets, query, i)) return true;
        }
        return false;
    }

    private static boolean subsequence(String[] sets, String query) {
        int j = 0;
        for (int i = 0; i < sets.length && j < query.length(); i++) {
            if (sets[i].indexOf(query.charAt(j)) >= 0) j++;
        }
        return j == query.length();
    }
}
