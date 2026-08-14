package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlinkLogicTest {

    // 静默期（有活体 pong 但无移动包）结束后突发补发位置包 = 重放
    @Test
    void silenceThenBurst_isReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        for (int i = 0; i < 40; i++) {
            logic.feed(50L, false); // 正常节奏，无静默
        }
        // 静默 1800ms（pong 活跃）
        for (int i = 0; i < 30; i++) {
            logic.tick(50L, true);
        }
        // 突发补发 20 包，间隔 ~2ms
        boolean burst = false;
        for (int i = 0; i < 20; i++) {
            if (logic.feed(2L, true)) {
                burst = true;
            }
        }
        assertTrue(burst, "静默后突发补发应判定为重放");
    }

    // 无静默期的 burst（网络拥塞恢复）不得判定
    @Test
    void burstWithoutSilence_notReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        boolean burst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(2L, false)) {
                burst = true;
            }
        }
        assertFalse(burst, "无静默期的 burst 不应判定为重放");
    }

    // 正常节奏永不判定
    @Test
    void normalPacing_neverReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        boolean burst = false;
        for (int i = 0; i < 200; i++) {
            if (logic.feed(50L, false)) {
                burst = true;
            }
        }
        assertFalse(burst, "正常节奏不应判定");
    }

    // 静默但无突发补发（仅掉线边缘）不得判定
    @Test
    void silenceWithoutBurst_notReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        for (int i = 0; i < 40; i++) {
            logic.feed(50L, false);
        }
        for (int i = 0; i < 30; i++) {
            logic.tick(50L, true);
        }
        for (int i = 0; i < 20; i++) {
            logic.feed(50L, true); // 恢复后正常节奏
        }
        boolean burst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(50L, true)) {
                burst = true;
            }
        }
        assertFalse(burst, "静默后恢复但无突发，不应判定");
    }
}