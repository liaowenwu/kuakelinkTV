package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC timestamps, including repeated tags and the file's millisecond offset. */
final class Lyrics {
    static final class Line {
        final long time;
        final String text;
        Line(long time, String text) { this.time = time; this.text = text; }
    }
    static List<Line> parse(String source) {
        List<Line> lines = new ArrayList<>();
        Matcher offsetTag = Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE).matcher(source);
        long offset = offsetTag.find() ? Long.parseLong(offsetTag.group(1)) : 0;
        Pattern stamp = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        for (String row : source.split("\\r?\\n")) {
            Matcher m = stamp.matcher(row);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                String fraction = m.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                times.add(Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000 + millis + offset);
                end = m.end();
            }
            String text = row.substring(end).trim();
            if (!text.isEmpty()) for (long time : times) lines.add(new Line(time, text));
        }
        lines.sort(Comparator.comparingLong(line -> line.time));
        return lines;
    }
    static int indexAt(List<Line> lines, long position) {
        int index = -1;
        for (int i = 0; i < lines.size() && lines.get(i).time <= position; i++) index = i;
        return index;
    }
}
