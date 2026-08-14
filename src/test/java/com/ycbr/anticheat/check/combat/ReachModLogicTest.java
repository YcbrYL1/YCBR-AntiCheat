package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReachModLogicTest {

    // 初始无收缩
    @Test
    void initialModifier_zero() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        assertEquals(0.0, logic.currentModifier(), 1e-9);
    }

    // 连续临界攻击达阈值 → 收缩
    @Test
    void repeatedEdgeAttacks_shrink() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int i = 0; i < 8; i++) {
            logic.onEdgeAttack(1.0);
        }
        assertTrue(logic.currentModifier() > 0.0, "连续临界攻击应产生收缩");
    }

    // 正常距离攻击 → 不收缩且衰减
    @Test
    void cleanAttacks_decay() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int i = 0; i < 8; i++) {
            logic.onEdgeAttack(1.0);
        }
        double shrunk = logic.currentModifier();
        assertTrue(shrunk > 0.0);
        for (int i = 0; i < 20; i++) {
            logic.onCleanAttack();
        }
        assertTrue(logic.currentModifier() < shrunk, "正常攻击应衰减收缩");
    }

    // 收缩有上限
    @Test
    void shrink_capped() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 8; i++) {
                logic.onEdgeAttack(1.0);
            }
        }
        assertTrue(logic.currentModifier() <= 0.5 + 1e-9, "收缩不得超过上限");
    }
}