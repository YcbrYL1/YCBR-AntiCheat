package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * P0-3 多 tick 碰撞重演测试：candidatesMultiTickWithCollision 逐 tick AABB 解析，
 * 覆盖墙截断 / 台阶步进 / 落地归位 / 网格耗尽回退 / 无墙与旧路径一致。
 *
 * <p>布局约定同 {@link CollisionResolverTest}：玩家脚底 (0.5, 64.0, 0.5)，
 * 地面格 (0,63,0)，面向 +X（yaw=0）。</p>
 */
class MultiTickCollisionTest {

    private static final double PX = 0.5;
    private static final double PY = 64.0;
    private static final double PZ = 0.5;
    private static final double FRICTION = 0.6; // 普通地面 slipperiness

    private static VoxelGrid emptyGrid() {
        return new VoxelGrid(0, 64, 0, 0L);
    }

    private static VoxelGrid groundGrid() {
        VoxelGrid g = emptyGrid();
        g.setFlag(0, 63, 0, VoxelGrid.SOLID);
        return g;
    }

    /** 标准 3 tick 走路（无跳跃）候选：网格路径。 */
    private static PredictionEngine.Candidate[] walkGrid(VoxelGrid g, int ticks) {
        return PredictionEngine.candidatesMultiTickWithCollision(
                0.0, 0.0, 0.0, true, 0f, FRICTION,
                false, 0.0, 0.0, ticks,
                false, false, false, false, false,
                PX, PY, PZ, g);
    }

    private static PredictionEngine.Candidate find(CandidateType type,
            PredictionEngine.Candidate[] cands, int ticks) {
        // 引擎 label 格式：speedLabel+(jump?)+xN+strafe=S+grid（label 与 xN 间无空格）
        String label = type.label + "x" + ticks + "+strafe=0+grid";
        for (PredictionEngine.Candidate c : cands) {
            if (label.equals(c.label)) {
                return c;
            }
        }
        return null;
    }

    private enum CandidateType {
        WALK("walk"), SPRINT("sprint");

        final String label;

        CandidateType(String label) {
            this.label = label;
        }
    }

    @Test
    void wall_multiTickTruncatesAtFace() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.SOLID);
        g.setFlag(1, 65, 0, VoxelGrid.SOLID); // 整格墙不可步进

        PredictionEngine.Candidate[] cands = walkGrid(g, 3);
        assertNotNull(cands);
        PredictionEngine.Candidate walk = find(CandidateType.WALK, cands, 3);
        assertNotNull(walk, "缺少 walk x3+strafe=0 候选");
        // 墙面 x=1.0，玩家右缘 0.8：无论走几个 tick，累计水平位移封顶 0.2
        assertEquals(0.2, walk.deltaX, 1e-9, "累计位移应逐 tick 截断到墙面");
        assertEquals(0.0, walk.deltaZ, 1e-9);
        assertEquals(0.0, walk.motionY, 1e-9);
    }

    @Test
    void slab_multiTickStepsUpAndContinues() {
        VoxelGrid g = groundGrid();
        g.setFlag(1, 64, 0, VoxelGrid.STEP); // 半砖顶面 64.5，上方留空可步进

        PredictionEngine.Candidate[] cands = walkGrid(g, 2);
        assertNotNull(cands);
        PredictionEngine.Candidate walk = find(CandidateType.WALK, cands, 2);
        assertNotNull(walk);
        assertEquals(0.5, walk.motionY, 1e-9, "步进抬升到半砖顶面 64.5");
        assertTrue(walk.deltaX > 0.2, "步进后水平位移应恢复（不被当成墙截断），实际 "
                + walk.deltaX);
    }

    @Test
    void fall_landsMidSimulationThenGrounded() {
        VoxelGrid g = groundGrid();
        // 空中起始 y=64.5，携带垂直速度 -0.2，3 tick：
        // t1 dy=-0.2（未触地）→ t2 dy=-0.2744（未触地）→ t3 落地面 64.0（dy=-0.0256）
        PredictionEngine.Candidate[] cands = PredictionEngine.candidatesMultiTickWithCollision(
                0.0, 0.0, -0.2, false, 0f, FRICTION,
                false, 0.0, 0.0, 3,
                false, false, false, false, false,
                PX, 64.5, PZ, g);
        assertNotNull(cands);
        // 垂直路径与水平输入无关：所有候选 totalDY 一致 = 64.0 - 64.5
        assertEquals(-0.5, cands[0].motionY, 1e-9, "应精确落在地面 64.0 而非穿地");
    }

    @Test
    void gridExhaustion_returnsNullForFallback() {
        // 起点贴近网格右缘（覆盖 x∈[-2,2]），+X 移动立即越界 → null 回退旧路径
        PredictionEngine.Candidate[] cands = PredictionEngine.candidatesMultiTickWithCollision(
                0.1, 0.0, 0.0, true, 0f, FRICTION,
                false, 0.0, 0.0, 2,
                false, false, false, false, false,
                2.5, PY, PZ, groundGrid());
        assertNull(cands, "网格不足应整体回退，不得部分解析");
    }

    @Test
    void nullGrid_returnsNull() {
        assertNull(walkGrid(null, 2));
    }

    @Test
    void openGround_sustainedWalkUsesGroundPhysics() {
        // 贴地持续行走：网格路径逐 tick 保持地面摩擦/加速度（旧路径每 tick 切
        // 空中物理，摩擦 0.91/加速 0.02，系统性偏差）。walk 行 3 tick 解析验证。
        PredictionEngine.Candidate[] cands = walkGrid(groundGrid(), 3);
        assertNotNull(cands);
        PredictionEngine.Candidate walk = find(CandidateType.WALK, cands, 3);
        assertNotNull(walk);

        double slip = 0.6 * PhysicsConstants.AIR_FRICTION; // 地面水平摩擦
        double f6 = PhysicsConstants.ACCEL_FACTOR / (slip * slip * slip);
        double input = 0.1 * f6; // walk 行输入加速度
        double expected = 0.0;
        double carried = 0.0;
        for (int t = 0; t < 3; t++) {
            carried = carried * slip + input;
            expected += carried;
        }
        assertEquals(expected, walk.deltaX, 1e-9, "持续贴地行走应逐 tick 地面物理累计");
        assertEquals(0.0, walk.deltaZ, 1e-9);
        assertEquals(0.0, walk.motionY, 1e-9, "贴地行走垂直增量恒为 0");
    }

    @Test
    void singleTickDelegatesToCollisionCandidates() {
        // ticks<=1 直接委托单 tick 碰撞重演（含解析后候选）
        PredictionEngine.Candidate[] cands = walkGrid(groundGrid(), 1);
        assertNotNull(cands);
        assertTrue(cands.length > 0);
        for (PredictionEngine.Candidate c : cands) {
            assertTrue(c.label.contains("+strafe="));
        }
    }
}
