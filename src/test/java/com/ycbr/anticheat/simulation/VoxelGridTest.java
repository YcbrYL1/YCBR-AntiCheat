package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoxelGridTest {

    private static VoxelGrid grid(int ox, int oy, int oz) {
        return new VoxelGrid(ox, oy, oz, 0L);
    }

    @Test
    void setAndReadFlagsRoundTrip() {
        VoxelGrid g = grid(0, 0, 0);
        assertTrue(g.setFlag(1, 0, -1, VoxelGrid.SOLID | VoxelGrid.SLIME));
        int f = g.flagAt(1, 0, -1);
        assertEquals(VoxelGrid.SOLID | VoxelGrid.SLIME, f);
    }

    @Test
    void emptyCellIsZero() {
        VoxelGrid g = grid(0, 0, 0);
        assertEquals(0, g.flagAt(0, 0, 0));
    }

    @Test
    void outOfRangeReturnsUnknown() {
        VoxelGrid g = grid(0, 0, 0);
        assertEquals(-1, g.flagAt(VoxelGrid.RANGE_XZ + 1, 0, 0));
        assertEquals(-1, g.flagAt(0, VoxelGrid.RANGE_Y_ABOVE + 1, 0));
        assertEquals(-1, g.flagAt(0, -VoxelGrid.RANGE_Y_BELOW - 1, 0));
        assertEquals(-1, g.flagAt(0, 0, -VoxelGrid.RANGE_XZ - 1));
    }

    @Test
    void topHeightsPerFlag() {
        VoxelGrid g = grid(0, 0, 0);
        g.setFlag(0, 0, 0, VoxelGrid.SOLID);
        g.setFlag(1, 0, 0, VoxelGrid.STEP);
        g.setFlag(2, 0, 0, VoxelGrid.SLIME);
        g.setFlag(-1, 0, 0, VoxelGrid.LIQUID);
        assertEquals(1.0, g.topAt(0, 0, 0), 1e-9);
        assertEquals(0.5, g.topAt(1, 0, 0), 1e-9);
        assertEquals(1.0, g.topAt(2, 0, 0), 1e-9);
        assertEquals(0.0, g.topAt(-1, 0, 0), 1e-9);
        assertEquals(0.0, g.topAt(-2, 0, 0), 1e-9);
    }

    @Test
    void solidity() {
        VoxelGrid g = grid(0, 0, 0);
        g.setFlag(0, 0, 0, VoxelGrid.SOLID);
        assertTrue(g.isSolid(0, 0, 0));
        assertFalse(g.isSolid(1, 0, 0));
    }

    @Test
    void freshnessWindow() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 1_000L);
        assertTrue(g.isFresh(1_200L, 250L));
        assertFalse(g.isFresh(1_300L, 250L));
    }
}
