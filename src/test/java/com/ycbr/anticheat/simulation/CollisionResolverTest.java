package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 碰撞重演解析器测试：玩家脚底位置 (x,y,z)，半宽 0.3、高 1.8。
 * 地面约定：站在地面上时脚底 y = 下方方块顶面（如地面格 (0,63,0) 顶面 = 64.0）。
 */
class CollisionResolverTest {

    /** 玩家站在格 (0,63,0) 方块顶上（脚底 y=64.0），面向 +X。 */
    private static final double PX = 0.5;
    private static final double PY = 64.0;
    private static final double PZ = 0.5;

    private static VoxelGrid emptyGrid() {
        return new VoxelGrid(0, 64, 0, 0L);
    }

    private static VoxelGrid groundGrid() {
        VoxelGrid g = emptyGrid();
        g.setFlag(0, 63, 0, VoxelGrid.SOLID);
        return g;
    }

    @Test
    void openSpace_unchanged() {
        CollisionResolver.Resolution r = CollisionResolver.resolve(
                PX, PY, PZ, 0.3, 0.0, 0.1, emptyGrid());
        assertEquals(0.3, r.dx, 1e-9);
        assertEquals(0.1, r.dz, 1e-9);
        assertEquals(0.0, r.dy, 1e-9);
        assertFalse(r.hitWallX);
        assertFalse(r.hitWallZ);
        assertFalse(r.hitGround);
        assertFalse(r.stepped);
    }

    @Test
    void forwardWall_truncatesToFace() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.SOLID);
        g.setFlag(1, 65, 0, VoxelGrid.SOLID); // 头所在格（64+1.8 → 65）
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, 0.3, 0.0, 0.0, g);
        assertEquals(0.2, r.dx, 1e-9); // 墙面在 x=1.0，玩家前缘 0.8 → 距离 0.2
        assertTrue(r.hitWallX);
        assertFalse(r.stepped);
    }

    @Test
    void leftWall_truncatesNegativeX() {
        VoxelGrid g = groundGrid();
        g.setFlag(-1, 64, 0, VoxelGrid.SOLID); // 墙占 x∈[-1,0)，玩家左缘 0.2 → 面距 0.2
        g.setFlag(-1, 65, 0, VoxelGrid.SOLID);
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, -0.4, 0.0, 0.0, g);
        assertEquals(-0.2, r.dx, 1e-9, "左移到 0.0（贴墙）即被截断");
        assertTrue(r.hitWallX);
        assertFalse(r.stepped);
    }

    @Test
    void slabStep_liftsOver() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.STEP); // 半砖顶面 64.5
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, 0.3, 0.0, 0.0, g);
        assertTrue(r.stepped, "半砖应触发步进");
        assertEquals(0.3, r.dx, 1e-9, "步进后水平位移恢复");
        assertEquals(0.5, r.dy, 1e-9, "脚底抬升到 64.5");
        assertFalse(r.hitWallX);
    }

    @Test
    void fullWall_noStep() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.SOLID);
        g.setFlag(1, 65, 0, VoxelGrid.SOLID);
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, 0.3, 0.0, 0.0, g);
        assertFalse(r.stepped, "整格墙不可步进（步高 0.5 < 1.0）");
        assertEquals(0.2, r.dx, 1e-9);
        assertTrue(r.hitWallX);
    }

    @Test
    void landingOnGround_truncatesVertical() {
        // 脚底 64.2 下落 0.4 → 落到地面格 (0,63,0) 顶面 64.0
        CollisionResolver.Resolution r = CollisionResolver.resolve(
                0.5, 64.2, 0.5, 0.0, -0.4, 0.0, groundGrid());
        assertEquals(-0.2, r.dy, 1e-9, "只下落 0.2 即落地");
        assertTrue(r.hitGround);
        assertFalse(r.landedOnSlime);
    }

    @Test
    void midAirFall_unchanged() {
        CollisionResolver.Resolution r = CollisionResolver.resolve(
                0.5, 64.5, 0.5, 0.0, -0.4, 0.0, groundGrid());
        assertEquals(-0.4, r.dy, 1e-9, "未触及地面，继续下落");
        assertFalse(r.hitGround);
    }

    @Test
    void ceiling_blocksJump() {
        VoxelGrid g = groundGrid();
        g.setFlag(0, 66, 0, VoxelGrid.SOLID); // 天花板底面 66.0
        // 头 65.8 上跳 0.4 → 顶到 66.0 → 只上升 0.2
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, 0.0, 0.4, 0.0, g);
        assertEquals(0.2, r.dy, 1e-9);
        assertTrue(r.hitCeiling);
    }

    @Test
    void corner_bothAxesTruncated() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.SOLID);
        g.setFlag(1, 65, 0, VoxelGrid.SOLID);
        g.setFlag(0, 64, 1, VoxelGrid.SOLID);
        g.setFlag(0, 65, 1, VoxelGrid.SOLID);
        CollisionResolver.Resolution r = CollisionResolver.resolve(PX, PY, PZ, 0.3, 0.0, 0.3, g);
        assertEquals(0.2, r.dx, 1e-9);
        assertEquals(0.2, r.dz, 1e-9);
        assertTrue(r.hitWallX);
        assertTrue(r.hitWallZ);
    }

    @Test
    void slimeLanding_marksSlime() {
        VoxelGrid g = emptyGrid();
        g.setFlag(0, 63, 0, VoxelGrid.SLIME);
        CollisionResolver.Resolution r = CollisionResolver.resolve(
                0.5, 64.2, 0.5, 0.0, -0.4, 0.0, g);
        assertTrue(r.hitGround);
        assertTrue(r.landedOnSlime);
        assertEquals(-0.2, r.dy, 1e-9);
    }

    @Test
    void outOfGrid_returnsNull() {
        // 玩家远离网格原点 → flagAt 未知 → 整体回退（调用方走旧路径）
        CollisionResolver.Resolution r = CollisionResolver.resolve(
                20.5, 64.0, 20.5, 0.3, 0.0, 0.0, emptyGrid());
        assertNull(r);
    }
}
