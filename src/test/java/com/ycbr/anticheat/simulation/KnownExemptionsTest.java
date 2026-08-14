package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnownExemptionsTest {

    private static WorldProbe.ProbeResult probe(boolean liquid, boolean web, boolean ladder, boolean piston) {
        WorldProbe.ProbeResult p = new WorldProbe.ProbeResult();
        p.inLiquid = liquid;
        p.inWeb = web;
        p.onLadder = ladder;
        p.onPiston = piston;
        return p;
    }

    @Test
    void stepVerticalAllowed_withinStepHeight_passes() {
        assertTrue(KnownExemptions.stepVerticalAllowed(0.5, true), "0.5 步高应在 0.6 包络内");
        assertTrue(KnownExemptions.stepVerticalAllowed(0.6, true), "0.6 边界应放行");
    }

    @Test
    void stepVerticalAllowed_overStepHeight_blocked() {
        assertFalse(KnownExemptions.stepVerticalAllowed(0.61, true), "超过 0.6 步高不放行");
        assertFalse(KnownExemptions.stepVerticalAllowed(0.5, false), "非台阶/楼梯地形不放行");
    }

    @Test
    void slimeBounceAllowed_withinEnvelope_passes() {
        assertTrue(KnownExemptions.slimeBounceAllowed(0.63, true), "0.63 弹跳包络内");
        assertTrue(KnownExemptions.slimeBounceAllowed(0.65, true), "0.65 边界应放行");
    }

    @Test
    void slimeBounceAllowed_overEnvelope_blocked() {
        assertFalse(KnownExemptions.slimeBounceAllowed(0.66, true), "超过 0.65 包络不放行");
        assertFalse(KnownExemptions.slimeBounceAllowed(0.63, false), "非粘液块不放行");
    }

    @Test
    void mediumExempt_anyMedium_true() {
        assertTrue(KnownExemptions.isMediumExempt(probe(true, false, false, false)), "液体豁免");
        assertTrue(KnownExemptions.isMediumExempt(probe(false, true, false, false)), "网豁免");
        assertTrue(KnownExemptions.isMediumExempt(probe(false, false, true, false)), "梯子豁免");
    }

    @Test
    void mediumExempt_none_false() {
        assertFalse(KnownExemptions.isMediumExempt(probe(false, false, false, false)), "无介质不豁免");
    }

    @Test
    void pistonExempt_onlyOnPiston() {
        assertTrue(KnownExemptions.isPistonExempt(probe(false, false, false, true)), "活塞推动豁免");
        assertFalse(KnownExemptions.isPistonExempt(probe(false, false, false, false)), "无活塞不豁免");
    }

    @Test
    void multiTickSqrtFactor() {
        assertEquals(1.0, KnownExemptions.multiTickSqrtFactor(1), 1e-9, "单 tick 无放大");
        assertEquals(2.0, KnownExemptions.multiTickSqrtFactor(4), 1e-9, "多 tick 按 sqrt 放大");
    }

    @Test
    void registry_complete_withVersionAndDescription() {
        KnownExemptions.Exemption[] reg = KnownExemptions.EXEMPTIONS;
        assertEquals(5, reg.length, "注册表应登记全部 5 条引擎系豁免");
        for (KnownExemptions.Exemption e : reg) {
            assertFalse(e.name.isEmpty(), "豁免名非空");
            assertFalse(e.mcVersion.isEmpty(), "豁免必须标注 mc 版本");
            assertFalse(e.description.isEmpty(), "豁免必须带描述");
        }
    }
}