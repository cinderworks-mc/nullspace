package dev.nullspace.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

// text fitting without minecraft types, driven by a width function (the font in game, a fake one in the unit test).
// results are no wider than the region unless a single glyph is, which is what the layout test asserts
final class TextFit {
    static final String ELLIPSIS = "...";

    private TextFit() {}

    static String clip(ToIntFunction<String> width, String text, int max) {
        if (text.isEmpty() || width.applyAsInt(text) <= max) return text;
        int room = Math.max(0, max - width.applyAsInt(ELLIPSIS));
        int end = 0;
        while (end < text.length() && width.applyAsInt(text.substring(0, end + 1)) <= room) end++;
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    // keeps both ends of a long id so the namespace and the end of the path stay readable
    static String clipMiddle(ToIntFunction<String> width, String text, int max) {
        if (text.isEmpty() || width.applyAsInt(text) <= max) return text;
        int room = Math.max(0, max - width.applyAsInt(ELLIPSIS));
        int head = 0, tail = 0;
        while (head + tail < text.length()) {
            boolean takeHead = head <= tail;
            int nh = head + (takeHead ? 1 : 0), nt = tail + (takeHead ? 0 : 1);
            if (width.applyAsInt(text.substring(0, nh)) + width.applyAsInt(text.substring(text.length() - nt)) > room) break;
            head = nh; tail = nt;
        }
        return text.substring(0, head) + ELLIPSIS + text.substring(text.length() - tail);
    }

    static List<String> wrap(ToIntFunction<String> width, String text, int max) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String joined = line.length() == 0 ? word : line + " " + word;
                if (width.applyAsInt(joined) <= max) { line.setLength(0); line.append(joined); continue; }
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                String rest = word;
                while (width.applyAsInt(rest) > max && rest.length() > 1) {
                    int cut = 1;
                    while (cut < rest.length() && width.applyAsInt(rest.substring(0, cut + 1)) <= max) cut++;
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    static List<String> wrapLimit(ToIntFunction<String> width, String text, int max, int maxLines) {
        List<String> lines = wrap(width, text, max);
        if (lines.size() <= maxLines) return lines;
        List<String> out = new ArrayList<>(lines.subList(0, maxLines));
        String last = out.get(maxLines - 1).stripTrailing();
        while (!last.isEmpty() && width.applyAsInt(last + ELLIPSIS) > max) last = last.substring(0, last.length() - 1).stripTrailing();
        out.set(maxLines - 1, last + ELLIPSIS);
        return out;
    }

    static boolean fits(ToIntFunction<String> width, String text, int max) { return width.applyAsInt(text) <= max; }
}
