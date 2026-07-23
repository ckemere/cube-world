package com.ckemere.cubeworld.geometry;

/**
 * The six faces of the cube, unfolded as an <em>equatorial band</em> in the
 * world plane: the four equatorial faces form a west-to-east row (each with
 * north up, east right — a correct compass), with the poles hung above and
 * below the prime-meridian face:
 *
 * <pre>
 *                          [NORTH_POLE]
 *   [EQ_WEST] [EQ_PRIME] [EQ_EAST] [EQ_BACK]
 *                          [SOUTH_POLE]
 * </pre>
 *
 * Grid columns increase eastward (+X), rows increase southward (+Z). The band
 * is EQ_WEST(90°W) → EQ_PRIME(0°) → EQ_EAST(90°E) → EQ_BACK(180°), wrapping at
 * EQ_BACK's east edge back to EQ_WEST's west edge (the date line).
 */
public enum CubeFace {
    NORTH_POLE(0, -1, "North Pole"),
    EQ_PRIME(0, 0, "Equator 0°"),
    EQ_EAST(1, 0, "Equator 90°E"),
    EQ_BACK(2, 0, "Equator 180°"),
    EQ_WEST(-1, 0, "Equator 90°W"),
    SOUTH_POLE(0, 1, "South Pole");

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
