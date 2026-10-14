package dev.nullspace.content;

// solid parts of the hall block models in 1/16 block units, used for collision and selection shapes. no minecraft
// types, so HallModelsTest can compare them with the model json
public final class HallGeometry {
    private HallGeometry() {}

    public static final double[][] PAD_PARTS = {
        {1, 0, 1, 15, 2, 15}, {2, 2, 2, 14, 3, 4}, {2, 2, 4, 4, 3, 10}, {12, 2, 4, 14, 3, 10}, {4, 2, 4, 12, 3, 10}, {2, 3, 2, 4, 6, 4}, {12, 3, 2, 14, 6, 4},
        {2, 2, 10, 14, 5, 14}, {4, 5, 11, 12, 9, 14}, {4, 9, 10, 12, 11, 14}};

    public static final double[][] PEDESTAL_PARTS = {
        {0, 0, 0, 16, 3, 16}, {1, 3, 1, 15, 11, 15}, {0, 11, 0, 16, 16, 16},
        {2, 16, 0, 14, 19, 2}, {2, 16, 14, 14, 19, 16}, {0, 16, 2, 2, 19, 14}, {14, 16, 2, 16, 19, 14},
        {0, 16, 0, 2, 20, 2}, {14, 16, 0, 16, 20, 2}, {0, 16, 14, 2, 20, 16}, {14, 16, 14, 16, 20, 16}};
    public static final double[][] LIFT_DOWN_PARTS = {{0, 0, 0, 16, 16, 16}, {0, 16, 0, 16, 19, 2}, {0, 16, 14, 16, 19, 16}, {0, 16, 2, 2, 19, 14}, {14, 16, 2, 16, 19, 14}};

    // the doorway, two blocks tall, facing north (the back against the wall is z 16). the lower cell holds the threshold, the
    // jambs and the foot of the leaf; the upper cell (y from the cell floor) the top of the leaf, the stepped arch, the
    // lintel and the keystone that stands 2 proud of the frame
    public static final double[][] EXIT_LOWER_PARTS = {
        {0, 0, 8, 16, 2, 16}, {14, 2, 10, 16, 16, 16}, {0, 2, 10, 2, 16, 16}, {2, 2, 14, 14, 16, 16}};
    public static final double[][] EXIT_UPPER_PARTS = {
        {14, 0, 10, 16, 14, 16}, {0, 0, 10, 2, 14, 16}, {0, 14, 10, 16, 16, 16},
        {12, 10, 10, 14, 14, 16}, {2, 10, 10, 4, 14, 16}, {10, 12, 10, 12, 14, 16}, {4, 12, 10, 6, 14, 16},
        {2, 0, 14, 14, 10, 16}, {4, 10, 14, 12, 12, 16}, {6, 12, 14, 10, 14, 16}, {6, 14, 8, 10, 16, 10}};

    public static final int DAIS_TOP = 18;
}
