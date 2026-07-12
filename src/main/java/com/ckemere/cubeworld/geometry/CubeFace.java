package com.ckemere.cubeworld.geometry;

/**
 * The six faces of the cube, laid out as an unfolded cross in the world plane.
 * Grid coordinates are in face-size units; the north-pole face is at the
 * center of the cross:
 *
 * <pre>
 *              [EQ_BACK]
 *   [EQ_WEST] [NORTH_POLE] [EQ_EAST]
 *              [EQ_PRIME]
 *              [SOUTH_POLE]
 * </pre>
 *
 * Grid columns increase eastward (+X), rows increase southward (+Z).
 */
public enum CubeFace {
    NORTH_POLE(0, 0, "North Pole"),
    EQ_PRIME(0, 1, "Equator 0°"),
    EQ_EAST(1, 0, "Equator 90°E"),
    EQ_BACK(0, -1, "Equator 180°"),
    EQ_WEST(-1, 0, "Equator 90°W"),
    SOUTH_POLE(0, 2, "South Pole");

    private final int gridCol;
    private final int gridRow;
    private final String displayName;

    CubeFace(int gridCol, int gridRow, String displayName) {
        this.gridCol = gridCol;
        this.gridRow = gridRow;
        this.displayName = displayName;
    }

    public int gridCol() {
        return gridCol;
    }

    public int gridRow() {
        return gridRow;
    }

    public String displayName() {
        return displayName;
    }
}
