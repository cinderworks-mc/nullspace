package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.nullspace.core.WindowPlane.Corner;
import dev.nullspace.core.WindowPlane.Side;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WindowPlaneTest {
    private static Set<List<Integer>> shape(int axis, String... rows) {
        Set<List<Integer>> cells = new HashSet<>();
        int[] right = WindowPlane.offset(axis, Side.RIGHT), down = WindowPlane.offset(axis, Side.BOTTOM);
        for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length(); c++) {
            if (rows[r].charAt(c) != '#') continue;
            cells.add(List.of(right[0] * c + down[0] * r, right[1] * c + down[1] * r, right[2] * c + down[2] * r));
        }
        return cells;
    }

    private static boolean has(Set<List<Integer>> cells, List<Integer> at, int[] off) {
        return cells.contains(List.of(at.get(0) + off[0], at.get(1) + off[1], at.get(2) + off[2]));
    }

    private static int[] pieces(int axis, Set<List<Integer>> cells) {
        int bars = 0, outer = 0, posts = 0;
        for (List<Integer> at : cells) {
            boolean[] conn = new boolean[4];
            for (Side s : Side.values()) { conn[s.ordinal()] = has(cells, at, WindowPlane.offset(axis, s)); if (WindowPlane.bar(conn[s.ordinal()])) bars++; }
            for (Corner c : Corner.values()) {
                boolean v = conn[c.vertical.ordinal()], h = conn[c.horizontal.ordinal()];
                if (!v && !h) outer++;
                if (WindowPlane.post(v, h, has(cells, at, WindowPlane.diagonal(axis, c)))) posts++;
            }
        }
        return new int[] {bars, outer, posts};
    }

    @Test void sidesLieInThePlaneOfThePane() {
        for (int axis = 0; axis < 3; axis++) {
            Set<List<Integer>> seen = new HashSet<>();
            for (Side s : Side.values()) {
                int[] o = WindowPlane.offset(axis, s);
                assertEquals(0, o[axis], "a side never points along the view axis (axis " + axis + ", " + s + ")");
                assertEquals(1, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]));
                assertTrue(seen.add(List.of(o[0], o[1], o[2])), "four different sides");
            }
            int[] top = WindowPlane.offset(axis, Side.TOP), bottom = WindowPlane.offset(axis, Side.BOTTOM), left = WindowPlane.offset(axis, Side.LEFT), right = WindowPlane.offset(axis, Side.RIGHT);
            for (int i = 0; i < 3; i++) { assertEquals(0, top[i] + bottom[i]); assertEquals(0, left[i] + right[i]); }
            for (Corner c : Corner.values()) {
                int[] d = WindowPlane.diagonal(axis, c);
                assertEquals(0, d[axis]);
                assertEquals(2, Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]));
            }
        }
    }

    @Test void aSingleWindowHasTheFullRing() {
        for (int axis = 0; axis < 3; axis++) assertArrayEquals(new int[] {4, 4, 0}, pieces(axis, shape(axis, "#")));
    }

    @Test void aWallOfWindowsIsOnePaneWithABorderAroundTheGroup() {
        for (int axis = 0; axis < 3; axis++) {
            // 6 wide, 3 tall: the border is the perimeter, with no bar between two windows
            assertArrayEquals(new int[] {2 * (6 + 3), 4, 0}, pieces(axis, shape(axis, "######", "######", "######")));
        }
    }

    @Test void insideCornersGetOnePostEach() {
        for (int axis = 0; axis < 3; axis++) {
            int[] l = pieces(axis, shape(axis, "##", "#."));       // L: one inside corner
            assertEquals(1, l[2]); assertEquals(5, l[1]);
            int[] ring = pieces(axis, shape(axis, "###", "#.#", "###")); // a hole: four inside corners
            assertEquals(4, ring[2]);
            int[] plus = pieces(axis, shape(axis, ".#.", "###", ".#."));  // plus: four inside corners
            assertEquals(4, plus[2]);
        }
    }

    @Test void turningNumberHoldsForEveryShape() {
        // outer corners minus inside corners is 4 per piece of the pane minus 4 per hole, whatever the shape: the border is a closed loop
        String[][] shapes = {{"#"}, {"##", "#."}, {"###", "#.#", "###"}, {"#.#", "###"}, {".#.", "###", ".#."}, {"####", "#..#", "####"}, {"#####", "#.#.#", "#####"}};
        int[] turning = {4, 4, 0, 4, 4, 0, -4};
        for (int axis = 0; axis < 3; axis++) for (int i = 0; i < shapes.length; i++) {
            int[] p = pieces(axis, shape(axis, shapes[i]));
            assertEquals(turning[i], p[1] - p[2], "shape " + i + " axis " + axis);
        }
    }

    @Test void barsAndPostsOnlyWhereTheFrameIs() {
        assertTrue(WindowPlane.bar(false));
        assertFalse(WindowPlane.bar(true));
        assertTrue(WindowPlane.post(true, true, false));
        assertFalse(WindowPlane.post(true, true, true));
        assertFalse(WindowPlane.post(true, false, false));
        assertFalse(WindowPlane.post(false, false, false));
    }
}
