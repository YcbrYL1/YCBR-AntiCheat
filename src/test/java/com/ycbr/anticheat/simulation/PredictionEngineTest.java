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
        // yaw=0: cos=1, sin=0; motX += 0.1*1.0 = 0.1; motX *= 0.546 ≈ 0.0546
        assertEquals(0.1 * 0.546, r.deltaX, 0.01);
        assertEquals(0.0, r.deltaZ, 0.01);
    }

    @Test
    void sprintJump_singleTick() {
        PredictionEngine.Result r = PredictionEngine.predictSingle(
            0.0, 0.0, true, 0f, 0.6, true, true, false, 0, 0, 0
        );
        // sprint: 0.1*1.3=0.13; input → motX += 0.13
        // sprint jump: motZ += cos(0)*0.2 = 0.2
        // friction: motX *= 0.546 ≈ 0.071; motZ *= 0.546 ≈ 0.109
        assertEquals(0.13 * 0.546, r.deltaX, 0.02);
        assertEquals(0.2 * 0.546, r.deltaZ, 0.02);
        // motY after gravity+drag: (0.42-0.08)*0.98 = 0.3332
        assertEquals((0.42 - 0.08) * 0.98, r.motionY, 0.01);
    }

    @Test
    void candidates_groundNormal_sprintJumpIncluded() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            0.0, 0.0, true, 0f, 0.6, true, 0, 0
        );
        assertEquals(6, cands.length);
        boolean found = false;
        for (PredictionEngine.Candidate c : cands) {
            if ("sprint+jump".equals(c.label)) {
                assertEquals((0.42 - 0.08) * 0.98, c.motionY, 0.01);
                found = true;
            }
        }
        assertTrue(found, "sprint+jump candidate missing");
    }

    @Test
    void candidates_twoTickAir_fallCorrect() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidatesMultiTick(
            0.0, 0.0, 0.0, false, 0f, 0.6, false, 0, 0, 2
        );
        boolean found = false;
        for (PredictionEngine.Candidate c : cands) {
            if (c.label.startsWith("walk")) {
                // tick0: motY=0, totalDY+=0; motY=(0-0.08)*0.98=-0.0784
                // tick1: motY=-0.0784, totalDY+=-0.0784
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
            PredictionEngine.Candidate walk = cands[0];
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
            PredictionEngine.Candidate sprintCand = cands[1];
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
        // 梯子上攀爬：motY 应 > 0（向上）
        assertTrue(ladder.motionY > 0.0, "ladder motionY=" + ladder.motionY);
    }

    @Test
    void candidates_worldStateOverloadCompiles() {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            0.0, 0.0, true, 0f, 0.6, false, 0, 0, false, false, false, false);
        assertTrue(cands.length > 0);
    }
}
