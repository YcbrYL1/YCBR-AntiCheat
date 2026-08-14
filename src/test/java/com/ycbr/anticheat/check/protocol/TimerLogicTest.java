package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimerLogicTest {

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