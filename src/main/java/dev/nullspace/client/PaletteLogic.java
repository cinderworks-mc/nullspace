package dev.nullspace.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class PaletteLogic {
    private PaletteLogic() {}

    record Cell(int index, int col, int row, boolean glass) {}

    static boolean matches(String finishId, String query, Collection<String> aliases) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return true;
        String name = finishId.substring(finishId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (name.contains(q)) return true;
        for (String a : aliases) if (a.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    static List<Cell> layout(List<Boolean> glass, int cols) {
        List<Cell> out = new ArrayList<>();
        int p = 0, g = 0;
        for (int i = 0; i < glass.size(); i++) {
            if (glass.get(i)) { out.add(new Cell(i, g % cols, g / cols, true)); g++; }
            else { out.add(new Cell(i, p % cols, p / cols, false)); p++; }
        }
        return out;
    }

    static int paintRows(List<Cell> cells) { int r = 0; for (Cell c : cells) if (!c.glass()) r = Math.max(r, c.row() + 1); return r; }
    static int glassRows(List<Cell> cells) { int r = 0; for (Cell c : cells) if (c.glass()) r = Math.max(r, c.row() + 1); return r; }

    private static int absRow(Cell c, int paintRows) { return c.glass() ? paintRows + c.row() : c.row(); }

    static int move(List<Cell> cells, int cur, int dx, int dy) {
        if (cells.isEmpty()) return -1;
        if (cur < 0 || cur >= cells.size()) return 0;
        if (dx != 0) return Math.max(0, Math.min(cells.size() - 1, cur + dx));
        int pr = paintRows(cells);
        Cell c = cells.get(cur);
        int targetRow = absRow(c, pr) + dy, best = cur, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            Cell o = cells.get(i);
            if (absRow(o, pr) != targetRow) continue;
            int d = Math.abs(o.col() - c.col());
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    static float fitScale(int ww, int wh, int w, int h) { return Math.min(1f, Math.min(ww / (w + 4f), wh / (h + 4f))); }
}
