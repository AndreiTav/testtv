package dev.salattv.launcher;

import static dev.salattv.launcher.FocusNavigation.*;
import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class FocusNavigationTest {
    @Test public void everyTileIsReachableWithoutLeavingValidBounds() {
        for (int count = 0; count <= 40; count++) {
            Set<Position> seen = new HashSet<>();
            ArrayDeque<Position> queue = new ArrayDeque<>();
            queue.add(new Position(DOCK, 0));
            while (!queue.isEmpty()) {
                Position p = queue.remove();
                if (!seen.add(p)) continue;
                int size = p.region() == TOP ? 3 : p.region() == DOCK ? 4 : count;
                assertTrue(p.index() >= 0 && p.index() < size);
                for (int d = LEFT; d <= DOWN; d++) queue.add(move(p, d, count, 0));
            }
            assertEquals(7 + count, seen.size());
        }
    }
    @Test public void verticalMovesPreserveColumnOrStayAtIncompleteLastRow() {
        for (int count = 1; count <= 40; count++) {
            for (int i = 0; i < count; i++) {
                Position p = new Position(GRID, i);
                Position down = move(p, DOWN, count, 0);
                assertEquals(i + 4 < count ? i + 4 : i, down.index());
                Position up = move(p, UP, count, 0);
                assertEquals(i < 4 ? DOCK : GRID, up.region());
                assertEquals(i < 4 ? i : i - 4, up.index());
            }
        }
    }
    @Test public void edgesNeverFallThroughAndTopRemembersDock() {
        assertEquals(new Position(TOP, 0), move(new Position(TOP, 0), LEFT, 9, 3));
        assertEquals(new Position(TOP, 2), move(new Position(TOP, 2), RIGHT, 9, 3));
        assertEquals(new Position(DOCK, 3), move(new Position(TOP, 1), DOWN, 9, 3));
        assertEquals(new Position(GRID, 3), move(new Position(GRID, 3), RIGHT, 9, 0));
        assertEquals(new Position(GRID, 4), move(new Position(GRID, 4), LEFT, 9, 0));
    }
}
