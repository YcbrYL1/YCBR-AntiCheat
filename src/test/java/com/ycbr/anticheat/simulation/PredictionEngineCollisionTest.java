package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 引擎级碰撞路径测试：网格可用时候选位移被世界几何收紧（墙截断/台阶步进），
 * 网格缺失/越界时回退（返回 null）。
 */
class PredictionEngineCollisionTest {

    private static final float YAW = 0.0f; // 前进 = +X

    private static PredictionEngine.Candidate[] run(double px, boolean sprint, VoxelGrid grid) {
        return PredictionEngine.candidatesWithCollision(
                0.0, 0.0, 0.0, true, YAW, 0.6, sprint, 0, 0,
                false, false, false, false, false,
                px, 64.0, 0.5, grid);
    }

    @Test
    void wallBlocksForwardCandidates() {
        // 玩家前缘贴墙：px=0.7 → 前缘 1.0 = 墙面（格 (1,64,0) 左缘）
        VoxelGrid walled = new VoxelGrid(0, 64, 0, 0L);
        walled.setFlag(1, 64, 0, VoxelGrid.SOLID);
        walled.setFlag(1, 65, 0, VoxelGrid.SOLID);

        PredictionEngine.Candidate[] openC = run(0.7, true, new VoxelGrid(0, 64, 0, 0L));
        PredictionEngine.Candidate[] wallC = run(0.7, true, walled);
        assertNotNull(openC);
        assertNotNull(wallC);

        double openMaxDX = 0.0;
        double wallMaxDX = 0.0;
        for (PredictionEngine.Candidate c : openC) {
            openMaxDX = Math.max(openMaxDX, c.deltaX);
        }
        for (PredictionEngine.Candidate c : wallC) {
            wallMaxDX = Math.max(wallMaxDX, c.deltaX);
        }
        assertTrue(openMaxDX > 0.1, "无墙时前进候选应 >0.1，实际 " + openMaxDX);
        assertTrue(wallMaxDX < 0.01, "贴墙时前进分量应被截断到 0，实际 " + wallMaxDX);
    }

    @Test
    void slabCandidateStepsUp() {
        VoxelGrid g = new VoxelGrid(0, 64, 0, 0L);
        g.setFlag(0, 63, 0, VoxelGrid.SOLID); // 地面
        g.setFlag(1, 64, 0, VoxelGrid.STEP);  // 前方半砖（面距 0.05 < 单 tick 位移）
        PredictionEngine.Candidate[] cands = run(0.65, true, g);
        assertNotNull(cands);
        boolean sawStep = false;
        for (PredictionEngine.Candidate c : cands) {
            if (c.label.contains("+step") && Math.abs(c.motionY - 0.5) < 1e-9) {
                sawStep = true;
            }
        }
        assertTrue(sawStep, "半砖前应有步进候选（dy=0.5）");
    }

    @Test
    void fullWallGivesNoStepCandidate() {
        VoxelGrid g = new VoxelGrid(0, 64, 0, 0L);
        g.setFlag(0, 63, 0, VoxelGrid.SOLID);
        g.setFlag(1, 64, 0, VoxelGrid.SOLID);
        g.setFlag(1, 65, 0, VoxelGrid.SOLID);
        PredictionEngine.Candidate[] cands = run(0.65, true, g);
        assertNotNull(cands);
        for (PredictionEngine.Candidate c : cands) {
            assertTrue(!c.label.contains("+step"), "整格墙不应出现步进候选");
        }
    }

    @Test
    void gridOutOfRangeFallsBackToNull() {
        // 玩家位置远离网格原点 → 解析失败 → 返回 null，检测层回退旧路径
        PredictionEngine.Candidate[] cands = PredictionEngine.candidatesWithCollision(
                0.0, 0.0, 0.0, true, YAW, 0.6, true, 0, 0,
                false, false, false, false, false,
                20.5, 64.0, 20.5, new VoxelGrid(0, 64, 0, 0L));
        assertEquals(null, cands);
    }
}
