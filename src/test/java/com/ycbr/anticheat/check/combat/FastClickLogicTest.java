package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FastClickLogicTest {

    @Test
    void mechanicalPattern_detectsConstantIntervals() {
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 60; i++) {
            logic.feed(95L); // 恒定 95ms 间隔
        }
        assertTrue(logic.mechanicalPattern());
    }

    @Test
    void organicPattern_notFlagged() {
        FastClickLogic logic = new FastClickLogic();
        java.util.Random rnd = new java.util.Random(42L);
        for (int i = 0; i < 60; i++) {
            long v;
            if (rnd.nextInt(10) == 0) {
                v = 250L + rnd.nextInt(150); // 真人偶发慢点击（右偏）
            } else {
                v = Math.max(30L, Math.round(110D + rnd.nextGaussian() * 28D));
            }
            logic.feed(v);
        }
        assertFalse(logic.mechanicalPattern(), "organic clicking should not flag");
    }

    @Test
    void insufficientSamples_notFlagged() {
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 20; i++) {
            logic.feed(95L);
        }
        assertFalse(logic.mechanicalPattern(), "cold start should not flag");
    }

    @Test
    void slowConstantRhythm_notFlagged() {
        // 挖矿/拆包等慢速恒定节奏（>8.3cps 的高速前置排除）：恒定 400ms 间隔
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 60; i++) {
            logic.feed(400L);
        }
        assertFalse(logic.mechanicalPattern(-1.5D, 120.0D),
                "慢速恒定节奏（挖矿还击）不应判定为点击宏");
    }

    @Test
    void fastOrganicJitter_notFlagged() {
        // 真人高速手点：60±3ms 抖动（均匀分布，超额峰度≈-1.2 > -1.5，熵≈2.8 > 1）
        FastClickLogic logic = new FastClickLogic();
        java.util.Random rnd = new java.util.Random(7L);
        for (int i = 0; i < 60; i++) {
            logic.feed(60L + rnd.nextInt(7)); // 57..63
        }
        assertFalse(logic.mechanicalPattern(-1.5D, 120.0D),
                "高速但有自然抖动的手点不应判定为机械");
    }

    @Test
    void fastConstantIntervals_stillFlagged() {
        // 高速恒定宏：50ms 恒定 → 仍命中
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 60; i++) {
            logic.feed(50L);
        }
        assertTrue(logic.mechanicalPattern(-1.5D, 120.0D),
                "高速恒定间隔（20cps 宏）应命中");
    }

    @Test
    void meanGateWorksAtBoundary() {
        // 平均间隔恰在阈值边缘：>120 不命中（回归防护）
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 40; i++) {
            logic.feed(125L);
        }
        assertFalse(logic.mechanicalPattern(-1.5D, 120.0D));
    }
}