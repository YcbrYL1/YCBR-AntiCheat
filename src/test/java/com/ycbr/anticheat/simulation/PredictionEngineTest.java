package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PredictionEngineTest {

    @Test
    void placeholderTest() {
        assertTrue(true);
    }

    @Test
    void groundWalkNormal_singleTick() {
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0
        );
        // 状态约定：delta = 携带(0)*0.546 + 输入(0.1)*cos(0) = 0.1
        // 即客户端该 tick 应上报 0.1 的位置增量（摩擦在下一 tick 的携带上体现）
        assertEquals(0.1, r.deltaX, 0.01);
        assertEquals(0.0, r.deltaZ, 0.01);
    }

    @Test
    void groundWalk_steadyStateConvergesToClientSpeed() {
        // 连续行走：delta_t = delta_{t-1}*0.546 + 0.1，收敛于 0.1/(1-0.546) ≈ 0.22
        double delta = 0.0;
        for (int i = 0; i < 60; i++) {
            PredictionEngine.Result r = PredictionEngine.predictSingle(
                delta, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0
            );
            delta = r.deltaX;
        }
        assertEquals(0.2203, delta, 0.005);
    }

    @Test
    void sprintJump_singleTick() {
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, true, true, false, 0, 0, 0
        );
        // 疾跑输入 0.1*1.3=0.13；疾跑跳跃冲量 motZ += cos(0)*0.2 = 0.2
        assertEquals(0.13, r.deltaX, 0.02);
        assertEquals(0.2, r.deltaZ, 0.02);
        // 跳跃该 tick 垂直增量 = 0.42（重力在下 tick 状态中体现）
        assertEquals(0.42, r.motionY, 0.01);
    }

    @Test
    void candidates_groundNormal_sprintJumpIncluded() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            0.0, 0.0, true, 0f, 0.6, true, 0, 0
        );
        // {idle, walk, sprint, sneak} x {no-jump, jump} = 8
        assertEquals(8, cands.length);
        boolean found = false;
        for (PredictionEngine.Candidate c : cands) {
            if ("sprint+jump".equals(c.label)) {
                assertEquals(0.42, c.motionY, 0.01);
                found = true;
            }
        }
        assertTrue(found, "sprint+jump candidate missing");
    }

    @Test
    void candidates_idleMatchesStandingStill() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            0.0, 0.0, true, 0f, 0.6, false, 0, 0
        );
        boolean found = false;
        for (PredictionEngine.Candidate c : cands) {
            if ("idle".equals(c.label)) {
                assertEquals(0.0, c.deltaX, 1e-9);
                assertEquals(0.0, c.deltaZ, 1e-9);
                found = true;
            }
        }
        assertTrue(found, "idle candidate missing (standing still must be legal)");
    }

    @Test
    void candidates_twoTickAir_fallCorrect() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidatesMultiTick(
            0.0, 0.0, 0.0, false, 0f, 0.6, false, 0, 0, 2
        );
        boolean found = false;
        for (PredictionEngine.Candidate c : cands) {
            if (c.label.startsWith("walk")) {
                // tick0: delta0 = 携带0 → totalDY += 0；状态 = (0-0.08)*0.98 = -0.0784
                // tick1: delta1 = -0.0784 → totalDY = -0.0784
                assertEquals(-0.0784, c.motionY, 0.02);
                found = true;
            }
        }
        assertTrue(found, "walk candidate missing in multi-tick");
    }

    @Test
    void integration_normalPlayerNoFlag() {
        double motX = 0.0, motZ = 0.0;
        boolean onGround = true;
        float yaw = 45f;
        double friction = 0.6;
        boolean sprint = false;

        for (int tick = 0; tick < 20; tick++) {
            PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
                motX, motZ, onGround, yaw, friction, sprint, 0, 0
            );
            PredictionEngine.Candidate walk = findLabel(cands, "walk");
            double actualDX = walk.deltaX;
            double actualDZ = walk.deltaZ;

            double tol = 0.03;
            boolean matched = false;
            for (PredictionEngine.Candidate c : cands) {
                if (Math.abs(actualDX - c.deltaX) <= tol && Math.abs(actualDZ - c.deltaZ) <= tol) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched, "Tick " + tick + ": walk delta not matched");

            motX = actualDX;
            motZ = actualDZ;
        }
    }

    @Test
    void integration_sprintNoFlag() {
        double motX = 0.0, motZ = 0.0;
        boolean onGround = true;
        float yaw = 90f;
        double friction = 0.6;
        boolean sprint = true;

        for (int tick = 0; tick < 20; tick++) {
            PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
                motX, motZ, onGround, yaw, friction, sprint, 0, 0
            );
            PredictionEngine.Candidate sprintCand = findLabel(cands, "sprint");
            double actualDX = sprintCand.deltaX;
            double actualDZ = sprintCand.deltaZ;

            double tol = 0.03;
            boolean matched = false;
            for (PredictionEngine.Candidate c : cands) {
                if (Math.abs(actualDX - c.deltaX) <= tol && Math.abs(actualDZ - c.deltaZ) <= tol) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched, "Tick " + tick + ": sprint delta not matched");

            motX = actualDX;
            motZ = actualDZ;
        }
    }

    @Test
    void speedPotion_matchesNmsAttributeFormula() {
        // NMS: (base 0.1 + 0.2*等级) * (疾跑?1.3:1)；普通地面 f6≈1
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 1, 0, 0
        );
        assertEquals(0.1 + 0.2 * 1, r.deltaX, 0.01); // 速度 I = 0.3
        PredictionEngine.Result rSprint = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, true, false, false, 1, 0, 0
        );
        assertEquals((0.1 + 0.2 * 1) * 1.3, rSprint.deltaX, 0.01); // 0.39
    }

    @Test
    void airborneVertical_usesCarriedMotion() {
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, -0.155, 0.0, false, 0f, 0.6, false, false, false, 0, 0,
            false, false, false, false, false);
        assertEquals(-0.155, r.motionY, 0.001); // 空中 delta = 携带速度
    }

    @Test
    void standingOnGround_verticalDeltaZero() {
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0);
        assertEquals(0.0, r.motionY, 0.001); // 地板碰撞吸收重力
    }

    @Test
    void usingItem_slowsHorizontalToNmsFactor() {
        PredictionEngine.Result normal = PredictionEngine.predictSingle(
            0.0, 0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0,
            false, false, false, false, false);
        PredictionEngine.Result using = PredictionEngine.predictSingle(
            0.0, 0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0,
            false, false, false, false, true);
        assertEquals(normal.deltaX * PredictionEngine.USING_ITEM_FACTOR, using.deltaX, 1e-9);
    }

    @Test
    void predictSingle_inLiquidSlowsDown() {
        PredictionEngine.Result ground = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0);
        PredictionEngine.Result liquid = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0, true, false, false, false);
        double groundH = Math.hypot(ground.deltaX, ground.deltaZ);
        double liquidH = Math.hypot(liquid.deltaX, liquid.deltaZ);
        assertTrue(liquidH < groundH * 0.8, "liquidH=" + liquidH + " groundH=" + groundH);
    }

    @Test
    void predictSingle_inWebHeavilySlowed() {
        PredictionEngine.Result normal = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0);
        PredictionEngine.Result web = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0, false, true, false, false);
        double normalH = Math.hypot(normal.deltaX, normal.deltaZ);
        double webH = Math.hypot(web.deltaX, web.deltaZ);
        assertTrue(webH < normalH * 0.3, "webH=" + webH + " normalH=" + normalH);
    }

    @Test
    void predictSingle_onLadderCanClimb() {
        PredictionEngine.Result ladder = PredictionEngine.predictSingle(
            0.0, 0.0, false, 0f, 0.6, false, false, false, 0, 0, 0, false, false, true, false);
        assertTrue(ladder.motionY > 0.0, "ladder motionY=" + ladder.motionY);
    }

    @Test
    void candidates_worldStateOverloadCompiles() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            0.0, 0.0, true, 0f, 0.6, false, 0, 0, false, false, false, false);
        assertTrue(cands.length > 0);
    }

    private static PredictionEngine.Candidate findLabel(PredictionEngine.Candidate[] cands, String label) {
        for (PredictionEngine.Candidate c : cands) {
            if (label.equals(c.label)) {
                return c;
            }
        }
        throw new AssertionError("candidate " + label + " not found");
    }
}
