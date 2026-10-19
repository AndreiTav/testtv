package dev.salattv.launcher;

/** Focus geometry independent of Android; horizontal moves never wrap a row. */
public final class FocusNavigation {
    public static final int TOP = 0, DOCK = 1, GRID = 2;
    public static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;
    public record Position(int region, int index) {}

    public static Position move(Position from, int direction, int gridCount, int lastDock) {
        int i = from.index();
        if (from.region() == TOP) {
            if (direction == LEFT) i = Math.max(0, i - 1);
            if (direction == RIGHT) i = Math.min(2, i + 1);
            if (direction == DOWN) return new Position(DOCK, clamp(lastDock, 4));
            return new Position(TOP, i);
        }
        if (from.region() == DOCK) {
            if (direction == LEFT) i = Math.max(0, i - 1);
            if (direction == RIGHT) i = Math.min(3, i + 1);
            if (direction == UP) return new Position(TOP, Math.min(2, i));
            if (direction == DOWN && gridCount > 0) return new Position(GRID, Math.min(i, gridCount - 1));
            return new Position(DOCK, i);
        }
        if (gridCount == 0) return new Position(DOCK, clamp(lastDock, 4));
        i = clamp(i, gridCount);
        if (direction == UP) return i < 4 ? new Position(DOCK, i) : new Position(GRID, i - 4);
        if (direction == DOWN && i + 4 < gridCount) i += 4;
        if (direction == LEFT && i % 4 > 0) i--;
        if (direction == RIGHT && i % 4 < 3 && i + 1 < gridCount) i++;
        return new Position(GRID, i);
    }

    private static int clamp(int i, int count) { return Math.max(0, Math.min(i, count - 1)); }
    private FocusNavigation() {}
}
