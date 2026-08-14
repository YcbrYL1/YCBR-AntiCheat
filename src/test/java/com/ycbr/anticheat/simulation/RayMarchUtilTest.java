package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RayMarchUtilTest {

    private static final class MapChecker implements RayMarchUtil.OcclusionChecker {
        final Set<String> solid = new HashSet<String>();

        MapChecker solid(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
            return this;
        }

        @Override
        public boolean occluding(int x, int y, int z) {
            return solid.contains(x + "," + y + "," + z);
        }
    }

    @Test
    void openSpace_notBlocked() {
        RayMarchUtil.Result r = RayMarchUtil.march(new MapChecker(),
                0.5, 0.5, 0.5, 3.0, 0.0, 0.0, 5.0, 0.25);
        assertFalse(r.blocked, "开放空间不应遮挡");
    }

    @Test
    void thickWall_blockedAtEntry() {
        RayMarchUtil.Result r = RayMarchUtil.march(new MapChecker().solid(0, 0, 0),
                -1.0, 0.5, 0.5, 2.0, 0.0, 0.0, 5.0, 0.25);
        assertTrue(r.blocked, "厚墙应遮挡");
        assertEquals(1.0, r.blockedAt, 1e-9, "遮挡点应位于墙入口");
    }

    @Test
    void grazingCorner_shortChord_passed() {
        // 射线几乎沿 +Y，x 方向微斜，起点 x=0.99 贴近格边界：
        // 在格 (1,0,0) 内弦长 ~0.0025 < 0.25 → 擦角放行
        RayMarchUtil.Result r = RayMarchUtil.march(new MapChecker().solid(1, 0, 0),
                0.99, 0.5, 0.5, 0.02, 1.0, 0.0, 3.0, 0.25);
        assertFalse(r.blocked, "擦角短弦长应放行");
    }

    @Test
    void diagonalHole_passed() {
        MapChecker c = new MapChecker();
        // 墙在 y=2 层，射线 y=0.5 层斜穿过去 → 不经过墙格
        c.solid(2, 2, 2);
        RayMarchUtil.Result r = RayMarchUtil.march(c,
                0.5, 0.5, 0.5, 2.0, 0.0, 2.0, 5.0, 0.25);
        assertFalse(r.blocked, "斜向穿过空隙不应遮挡");
    }

    @Test
    void nonOccludingMaterial_passed() {
        MapChecker c = new MapChecker();
        // 半砖/玻璃类材质 occluding=false，即使格子被标记也放行（由 checker 决定）
        RayMarchUtil.Result r = RayMarchUtil.march(c,
                0.5, 0.5, 0.5, 2.0, 0.0, 0.0, 5.0, 0.25);
        assertFalse(r.blocked, "非遮挡材质不应挡");
    }

    @Test
    void startInsideOccluding_skipStartCell() {
        // 起点格 (0,0,0) occluding：起步已在方块内不判（NCP 语义），
        // 墙格 (1,0,0) 才是第一个实挡 → blockedAt=0.5
        RayMarchUtil.Result r = RayMarchUtil.march(new MapChecker().solid(0, 0, 0).solid(1, 0, 0),
                0.5, 0.5, 0.5, 2.0, 0.0, 0.0, 5.0, 0.25);
        assertTrue(r.blocked, "起始格跳过，后续实挡仍应判");
        assertEquals(0.5, r.blockedAt, 1e-9, "遮挡点应为第二个格子入口");
    }

    @Test
    void beyondMaxLen_notBlocked() {
        RayMarchUtil.Result r = RayMarchUtil.march(new MapChecker().solid(3, 0, 0),
                0.5, 0.5, 0.5, 2.0, 0.0, 0.0, 2.5, 0.25);
        assertFalse(r.blocked, "墙在最大距离之后不应判");
    }
}