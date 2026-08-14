package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.simulation.PredictionEngine.Candidate;

/**
 * sim-speed 修复：疾跑候选行的输入必须独立于 m.sprinting 标志。
 *
 * <p>背景：客户端先发移动包、后发 ENTITY_ACTION(START_SPRINTING)，或标志
 * 丢失/滞后时，m.sprinting=false 而玩家实际在疾跑。若疾跑候选行按
 * "非疾跑" 基础速度计算（0.1 而非 0.13），maxH 会低于实际疾跑位移
 * （h≈0.279 > maxH≈0.255）→ sim-speed 误判正常走路。
 * 修复：sprint 行始终使用完整疾跑输入（过预测=安全方向）。</p>
 */
class PredictionEngineSprintTest {

    private static Candidate row(Candidate[] cands, String prefix) {
        for (Candidate c : cands) {
            if (c.label.startsWith(prefix)) {
                return c;
            }
        }
        throw new AssertionError("no row starting with " + prefix);
    }

    private static Candidate[] groundCands(boolean sprinting) {
        return PredictionEngine.candidates(0.0, 0.0, 0.0, true, 0.0F, 0.6,
                sprinting, 0.0, 0.0, false, false, false, false, false);
    }

    @Test
    void sprintCandidate_inputIndependentOfFlag() {
        Candidate noFlag = row(groundCands(false), "sprint");
        Candidate withFlag = row(groundCands(true), "sprint");
        assertEquals(withFlag.deltaX, noFlag.deltaX, 1e-9,
                "疾跑候选行必须不受 sprinting 标志影响");
        assertEquals(withFlag.deltaZ, noFlag.deltaZ, 1e-9);
    }

    @Test
    void sprintCandidate_exceedsWalkCandidate_evenWithoutFlag() {
        Candidate walk = row(groundCands(false), "walk");
        Candidate sprint = row(groundCands(false), "sprint");
        assertTrue(sprint.deltaX > walk.deltaX,
                "无疾跑标志时疾跑行仍应高于行走行: sprint=" + sprint.deltaX + " walk=" + walk.deltaX);
    }

    @Test
    void multiTickSprintCandidate_inputIndependentOfFlag() {
        Candidate[] noFlag = PredictionEngine.candidatesMultiTick(
                0.0, 0.0, 0.0, true, 0.0F, 0.6,
                false, 0.0, 0.0, 3, false, false, false, false, false);
        Candidate[] withFlag = PredictionEngine.candidatesMultiTick(
                0.0, 0.0, 0.0, true, 0.0F, 0.6,
                true, 0.0, 0.0, 3, false, false, false, false, false);
        Candidate a = row(noFlag, "sprint");
        Candidate b = row(withFlag, "sprint");
        assertEquals(b.deltaX, a.deltaX, 1e-9,
                "多 tick 疾跑候选行必须不受 sprinting 标志影响");
    }

    @Test
    void sprintJumpCandidate_appliesImpulseWithoutFlag() {
        Candidate[] noFlag = PredictionEngine.candidates(
                0.0, 0.0, 0.0, true, 90.0F, 0.6,
                false, 0.0, 0.0, false, false, false, false, false);
        Candidate[] withFlag = PredictionEngine.candidates(
                0.0, 0.0, 0.0, true, 90.0F, 0.6,
                true, 0.0, 0.0, false, false, false, false, false);
        Candidate a = row(noFlag, "sprint+jump");
        Candidate b = row(withFlag, "sprint+jump");
        assertEquals(b.deltaX, a.deltaX, 1e-9,
                "疾跑跳冲量不得依赖 sprinting 标志");
        assertEquals(b.deltaZ, a.deltaZ, 1e-9);
    }
}