package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.simulation.PredictionEngine.Candidate;

/**
 * 8AC 蓝图 P2.1：候选集扩展 strafe 维度（斜跑是合法输入）。
 *
 * <p>Entity.a 中 f3 = sqrt(fwd^2 + strafe^2)，f3>=1 时位移模长 =
 * inputSpeed 不变，只改变方向。因此 strafe 候选不会抬高 maxH
 * （不影响 sim-speed 安全方向），仅增加方向覆盖。</p>
 */
class PredictionEngineStrafeTest {

    private static Candidate row(Candidate[] cands, String prefix) {
        for (Candidate c : cands) {
            if (c.label.startsWith(prefix)) {
                return c;
            }
        }
        throw new AssertionError("no row starting with " + prefix);
    }

    private static Candidate[] groundCands() {
        return PredictionEngine.candidates(0.0, 0.0, 0.0, true, 0.0F, 0.6,
                false, 0.0, 0.0, false, false, false, false, false);
    }

    @Test
    void candidates_includeStrafeRows() {
        Candidate[] cands = groundCands();
        boolean foundStrafe = false;
        for (Candidate c : cands) {
            if (c.label.contains("strafe=")) {
                foundStrafe = true;
                break;
            }
        }
        assertTrue(foundStrafe, "候选应包含 strafe 行");
    }

    @Test
    void candidates_strafeMirrors_sameMagnitude() {
        // yaw=0 时 motX 与 strafe 无关（均 +f3），motZ 随 strafe 镜像
        Candidate left = row(groundCands(), "walk+strafe=-1");
        Candidate right = row(groundCands(), "walk+strafe=1");
        assertEquals(left.deltaX, right.deltaX, 1e-9,
                "yaw=0 时 strafe 不应改变 deltaX");
        assertEquals(left.deltaZ, -right.deltaZ, 1e-9,
                "strafe 正负应互为镜像");
        assertEquals(Math.hypot(left.deltaX, left.deltaZ),
                Math.hypot(right.deltaX, right.deltaZ), 1e-9);
    }

    @Test
    void candidates_strafeMagnitude_equalsStraight() {
        // 物理不变量：斜跑模长 == 直行模长（strafe 不抬高模长上限）
        Candidate straight = row(groundCands(), "walk+strafe=0");
        Candidate strafed = row(groundCands(), "walk+strafe=-1");
        assertEquals(Math.hypot(straight.deltaX, straight.deltaZ),
                Math.hypot(strafed.deltaX, strafed.deltaZ), 1e-9,
                "strafe 候选不得改变位移模长");
    }

    @Test
    void candidates_sprintStrafeRow_exists() {
        Candidate sprintStrafe = row(groundCands(), "sprint+strafe=-1");
        Candidate sprint = row(groundCands(), "sprint+strafe=0");
        assertEquals(Math.hypot(sprint.deltaX, sprint.deltaZ),
                Math.hypot(sprintStrafe.deltaX, sprintStrafe.deltaZ), 1e-9);
    }

    @Test
    void multiTick_includeStrafeRows() {
        Candidate[] cands = PredictionEngine.candidatesMultiTick(
                0.0, 0.0, 0.0, true, 0.0F, 0.6,
                false, 0.0, 0.0, 3, false, false, false, false, false);
        Candidate strafed = row(cands, "walkx3+strafe=-1");
        Candidate straight = row(cands, "walkx3+strafe=0");
        assertEquals(Math.hypot(straight.deltaX, straight.deltaZ),
                Math.hypot(strafed.deltaX, strafed.deltaZ), 1e-9,
                "多 tick strafe 候选不得改变位移模长");
    }
}