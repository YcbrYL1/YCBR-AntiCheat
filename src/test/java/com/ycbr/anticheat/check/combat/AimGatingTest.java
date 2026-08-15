package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimGatingTest {

    // ---- 非门控子检测：恒直判 ----

    @Test
    void nonGatedSubAlwaysPunishes() {
        assertTrue(AimGating.shouldPunish("SelfInteract", false, false, false, false,
                false, false, false));
        assertTrue(AimGating.shouldPunish("Reach", false, true, true, true,
                false, true, true));
    }

    // ---- 默认（soft=false）：兼容旧行为 ----

    @Test
    void legacyAimstatDisabled_punishesDirectly() {
        // aimstat 默认关 → 直判（历史行为：门控形同虚设）
        assertTrue(AimGating.shouldPunish("AimStep", true, false, false, true,
                false, false, false));
    }

    @Test
    void legacyCrossDisabled_punishesDirectly() {
        assertTrue(AimGating.shouldPunish("GcdStable", true, false, true, false,
                false, false, false));
    }

    @Test
    void legacyColdStart_punishesDirectly() {
        // aimstat 开 + 交叉开 + 样本未满 → 冷启动直判（兼容）
        assertTrue(AimGating.shouldPunish("AimStep", true, false, true, true,
                false, false, false));
    }

    @Test
    void legacyAimstatHitAndFresh_punishes() {
        assertTrue(AimGating.shouldPunish("BigRot", true, false, true, true,
                true, true, true));
    }

    @Test
    void legacySignalMiss_softSignalsOnly() {
        // aimstat 开 + 交叉开 + 样本足 + 信号未命中 → 只投信号不 punish
        assertFalse(AimGating.shouldPunish("Angle", true, false, true, true,
                true, false, true));
    }

    @Test
    void legacyStaleSignal_softSignalsOnly() {
        assertFalse(AimGating.shouldPunish("Switch", true, false, true, true,
                true, true, false));
    }

    // ---- 软降级（heuristic-soft=true）：冷启动也软降级 ----

    @Test
    void softAimstatOff_neverPunishes() {
        // soft 模式 + aimstat 关 → 无论信号如何都不 punish
        assertFalse(AimGating.shouldPunish("AimStep", true, true, false, true,
                true, true, true));
    }

    @Test
    void softColdStart_neverPunishes() {
        // soft 模式 + 样本未满（aimstat 尚未有判断力）→ 软降级
        assertFalse(AimGating.shouldPunish("GcdStable", true, true, true, true,
                false, true, true));
    }

    @Test
    void softHitAndFresh_punishes() {
        assertTrue(AimGating.shouldPunish("ConstStep", true, true, true, true,
                true, true, true));
    }

    @Test
    void softMissOrStale_softSignalsOnly() {
        assertFalse(AimGating.shouldPunish("AxisAsym", true, true, true, true,
                true, false, true));
        assertFalse(AimGating.shouldPunish("AxisAsym", true, true, true, true,
                true, true, false));
    }
}
