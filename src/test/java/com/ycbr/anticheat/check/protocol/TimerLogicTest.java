package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimerLogicTest {

    @Test
    void normalizedInterval_atTps20_isIdentity() {
        assertEquals(1.0, TimerLogic.normalizedInterval(1, 20.0), 1e-9);
        assertEquals(0.5, TimerLogic.normalizedInterval(0.5, 20.0), 1e-9);
    }

    @Test
    void normalizedInterval_atTps19_2_compensatesSlowTicks() {
        // 服务器 TPS=19.2（tick 52ms），客户端 50ms/包 → 每包覆盖 0.96 tick。
        // 归一化后应回到 20 TPS 语义（1.0 tick/包）。
        assertEquals(1.0, TimerLogic.normalizedInterval(0.96, 19.2), 1e-9);
        assertEquals(1.0417, TimerLogic.normalizedInterval(1, 19.2), 1e-3);
    }

    @Test
    void normalizedInterval_neverDividesByZero() {
        assertEquals(2.0, TimerLogic.normalizedInterval(1, 0.0), 1e-9);
        assertEquals(4.0, TimerLogic.normalizedInterval(2, -5.0), 1e-9);
    }

    @Test
    void tps19_2_normalPacing_neverFlags() {
        // 用户服务器实测场景：TPS≈19.2，正常走路被 TimerShort 连刷（avgInterval=0.960）。
        // 归一化后正常玩家 avgInterval 回到 1.0，不再误判。
        TimerLogic logic = new TimerLogic();
        int[] pattern = { 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1 }; // 25 个中 24 个 1、1 个 0 → avg 0.96
        for (int i = 0; i < 500; i++) {
            double raw = pattern[i % pattern.length];
            double v = TimerLogic.normalizedInterval(raw, 19.2);
            if (i < 60) {
                logic.feed(1.0, 25, 0.97);
            } else {
                assertFalse(logic.feed(v, 25, 0.97), "tps 19.2 normal pacing must not flag");
            }
        }
    }

    @Test
    void tps19_2_cheaterStillFlags() {
        // 作弊者 25 pps（40ms/包）：TPS=19.2 时每包覆盖 40/52.08=0.768 tick，
        // 归一化后 0.768*20/19.2=0.8 < 0.97，仍应被抓。
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 100; i++) {
            logic.feed(1.0, 25, 0.97);
        }
        boolean flagged = false;
        for (int i = 0; i < 60; i++) {
            double raw = i % 4 == 0 ? 0 : 1; // avg 0.75 ≈ 25pps @ 52ms/tick
            if (logic.feed(TimerLogic.normalizedInterval(raw, 19.2), 25, 0.97)) {
                flagged = true;
                break;
            }
        }
        assertTrue(flagged, "cheater must still be flagged after normalization");
    }

    @Test
    void normalPacing_neverFlags() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 200; i++) {
            assertFalse(logic.feed(1.0, 60, 0.95));
        }
    }

    @Test
    void highPingJitter_neverFlags() {
        // 高 ping：包到达抖动（0/1/2/3 tick 间隔交替），平均仍趋近 1
        TimerLogic logic = new TimerLogic();
        int[] pattern = { 0, 1, 2, 1, 0, 1, 1, 2, 1, 1 };
        for (int i = 0; i < 300; i++) {
            double v = pattern[i % pattern.length];
            if (i < 60) {
                logic.feed(1.0, 60, 0.95);
            } else {
                assertFalse(logic.feed(v, 60, 0.95));
            }
        }
    }

    @Test
    void acceleratedPacing_flags() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 100; i++) {
            logic.feed(1.0, 60, 0.95);
        }
        boolean flagged = false;
        for (int i = 0; i < 60; i++) {
            if (logic.feed(0.5, 60, 0.95)) {
                flagged = true;
                break;
            }
        }
        assertTrue(flagged, "acceleration should be flagged");
    }

    @Test
    void burstAcceleration_eventuallyRecovers() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 100; i++) {
            logic.feed(1.0, 60, 0.95);
        }
        for (int i = 0; i < 40; i++) {
            logic.feed(0.5, 60, 0.95);
        }
        boolean flaggedDuringBurst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(0.5, 60, 0.95)) {
                flaggedDuringBurst = true;
            }
        }
        assertTrue(flaggedDuringBurst);
        for (int i = 0; i < 100; i++) {
            logic.feed(1.0, 60, 0.95);
        }
        for (int i = 0; i < 60; i++) {
            assertFalse(logic.feed(1.0, 60, 0.95), "should recover after burst");
        }
    }

    @Test
    void coldStart_neverFlags() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 30; i++) {
            assertFalse(logic.feed(0.5, 60, 0.95), "below window size should not flag");
        }
    }
}