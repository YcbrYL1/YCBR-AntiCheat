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
}