package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PhaseCheck 穿透计算单测：整墙嵌入、边缘贴合、半砖、灵魂沙 0.875、
 * 网格未知 NaN、活塞邻域豁免。
 */
class PhaseLogicTest {

    /** 玩家脚底在原点格中心，网格原点同格。 */
    private static VoxelGrid grid() {
        return new VoxelGrid(0, 0, 0, System.currentTimeMillis());
    }

    private static void fillWall(VoxelGrid grid) {
        for (int by = 0; by <= 2; by++) {
            for (int bx = -2; bx <= 2; bx++) {
                grid.setFlag(bx, by, 2, VoxelGrid.SOLID);
            }
        }
    }

    @Test
    void standingOnGround_noPenetration() {
        VoxelGrid grid = grid();
        fillWall(grid); // 墙在 z=2 格
        // 玩家站在 y=1 顶（脚底 = 墙格顶面之上），远离墙
        double depth = PhaseLogic.maxPenetration(0.0, 1.0, 0.0, grid);
        assertEquals(0.0, depth, 1e-9, "站在实心地上不应穿透");
    }

    @Test
    void embeddedInWall_positivePenetration() {
        VoxelGrid grid = grid();
        fillWall(grid);
        // 玩家身体中心在墙格内（z=2.0-2.3 落在格 z=2 内）
        double depth = PhaseLogic.maxPenetration(0.0, 1.0, 1.85, grid);
        // ovZ = min(1.85+0.3, 3.0) - max(1.85-0.3, 2.0) = 2.15 - 2.0 = 0.15
        assertEquals(0.15, depth, 1e-9, "嵌入墙体 0.15 应被检出");
    }

    @Test
    void touchingWallFace_noPenetration() {
        VoxelGrid grid = grid();
        fillWall(grid);
        // 玩家边缘恰好贴合墙面（z+0.3 = 2.0），ovZ = 0 → 不算穿透
        double depth = PhaseLogic.maxPenetration(0.0, 1.0, 1.7, grid);
        assertEquals(0.0, depth, 1e-9, "贴合墙面（ovZ=0）不应判穿透");
    }

    @Test
    void stepBlock_standingOnTop_noPenetration() {
        VoxelGrid grid = grid();
        grid.setFlag(0, 0, 0, VoxelGrid.STEP);
        // 玩家站半砖顶 y=0.5：ovY = min(0.5+1.8, 0+0.5) - max(0.5, 0) = 0.5-0.5 = 0
        double depth = PhaseLogic.maxPenetration(0.0, 0.5, 0.0, grid);
        assertEquals(0.0, depth, 1e-9, "站半砖顶不应穿透");
    }

    @Test
    void stepBlock_bodyOverlap_penetration() {
        VoxelGrid grid = grid();
        grid.setFlag(0, 0, 0, VoxelGrid.STEP);
        // 玩家沉入半砖 0.3（y=0.2）：ovY = 0.5-0.2 = 0.3，ovX/ovZ 全重叠 0.6
        double depth = PhaseLogic.maxPenetration(0.0, 0.2, 0.0, grid);
        assertEquals(0.3, depth, 1e-9, "沉入半砖 0.3 应被检出");
    }

    @Test
    void soulSand_standingAt875_noPenetration() {
        VoxelGrid grid = grid();
        grid.setFlag(0, 0, 0, VoxelGrid.SOUL);
        // 玩家站灵魂沙顶 y=0.875（1.8 实际行为：脚底沉入 0.125）
        double depth = PhaseLogic.maxPenetration(0.0, 0.875, 0.0, grid);
        assertEquals(0.0, depth, 1e-9, "站灵魂沙 0.875 顶不应穿透");
    }

    @Test
    void soulSand_deepEmbed_penetration() {
        VoxelGrid grid = grid();
        grid.setFlag(0, 0, 0, VoxelGrid.SOUL);
        // 玩家沉入灵魂沙格 0.5：ovY = 0.875-0.5 = 0.375，ovX/ovZ = 0.3
        // 推出距离 = 最小重叠轴 → 0.3（X/Z 方向推出更近）
        double depth = PhaseLogic.maxPenetration(0.0, 0.5, 0.0, grid);
        assertEquals(0.3, depth, 1e-9, "沉入灵魂沙超 0.875 盒应被检出");
    }

    @Test
    void outOfGrid_returnsNaN() {
        VoxelGrid grid = grid();
        fillWall(grid);
        // 玩家移出网格覆盖（XZ ±2 格）：未知 → NaN（调用方必须跳过）
        double depth = PhaseLogic.maxPenetration(5.0, 1.0, 5.0, grid);
        assertTrue(Double.isNaN(depth), "网格外必须返回 NaN 零误判兜底");
    }

    @Test
    void nullGrid_returnsNaN() {
        assertTrue(Double.isNaN(PhaseLogic.maxPenetration(0, 0, 0, null)));
    }

    @Test
    void headInCeiling_penetration() {
        VoxelGrid grid = grid();
        grid.setFlag(0, 2, 0, VoxelGrid.SOLID); // 天花板在格 by=2（y 2~3）
        // 玩家头 y=2.0+1.8=3.8 → 头盒 [2.0,3.8] 与天花板格 [2,3] 重叠……
        // 改为头扎进天花板：玩家 y=1.5，头 3.3；格 by=2 覆盖 [2,3]，ovY = min(3.3,3)-max(1.5,2)=3-2=1
        double depth = PhaseLogic.maxPenetration(0.0, 1.5, 0.0, grid);
        // ovX = min(0.3,1)-max(-0.3,0) = 0.3-0 = 0.3；ovZ 同；ovY = 1.0 → depth = 0.3
        assertEquals(0.3, depth, 1e-9, "头扎进天花板应被检出");
    }

    @Test
    void nearPiston_exempts() {
        VoxelGrid grid = grid();
        fillWall(grid);
        grid.setFlag(0, 0, 2, VoxelGrid.PISTON); // 墙格叠活塞标志（推动中）
        // 玩家 (0,1,0.85)：expand=1 邻域 X[-1,1] Y[0,3] Z[0,2] 覆盖活塞格 → 豁免
        assertTrue(PhaseLogic.nearPiston(0.0, 1.0, 0.85, grid, 1), "邻域活塞应豁免");
        // expand=0：玩家盒邻域 X[0,0] Y[1,2] Z[0,1]，不含活塞格 (0,0,2) → 不豁免
        assertFalse(PhaseLogic.nearPiston(0.0, 1.0, 0.85, grid, 0), "expand=0 且盒邻域无活塞不豁免");
    }

    @Test
    void nearPiston_outOfGrid_exempts() {
        VoxelGrid grid = grid();
        assertTrue(PhaseLogic.nearPiston(10.0, 1.0, 10.0, grid, 1), "网格外保守豁免");
    }
}
