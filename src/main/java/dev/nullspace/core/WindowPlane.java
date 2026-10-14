package dev.nullspace.core;

// geometry of a connected window pane, pure so it can be unit tested. a window looks along one axis (0 = x, 1 = y, 2
// = z) and the other two span the pane. sides and corners are named by their role in the model (make_window_model.py
// draws every model looking along z, the blockstate rotates it)
public final class WindowPlane {
    private WindowPlane() {}

    public enum Side { TOP, BOTTOM, LEFT, RIGHT }
    public enum Corner {
        TOP_LEFT(Side.TOP, Side.LEFT), TOP_RIGHT(Side.TOP, Side.RIGHT), BOTTOM_LEFT(Side.BOTTOM, Side.LEFT), BOTTOM_RIGHT(Side.BOTTOM, Side.RIGHT);
        public final Side vertical, horizontal;
        Corner(Side vertical, Side horizontal) { this.vertical = vertical; this.horizontal = horizontal; }
    }

    private static final int[][][] SIDES = {
        /* x */ {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}},
        /* y */ {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}},
        /* z */ {{0, 1, 0}, {0, -1, 0}, {-1, 0, 0}, {1, 0, 0}},
    };

    public static int[] offset(int axis, Side side) { return SIDES[axis][side.ordinal()].clone(); }

    public static int[] diagonal(int axis, Corner corner) {
        int[] a = SIDES[axis][corner.vertical.ordinal()], b = SIDES[axis][corner.horizontal.ordinal()];
        return new int[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static boolean post(boolean verticalConnected, boolean horizontalConnected, boolean diagonalConnected) {
        return verticalConnected && horizontalConnected && !diagonalConnected;
    }

    public static boolean bar(boolean connected) { return !connected; }
}
