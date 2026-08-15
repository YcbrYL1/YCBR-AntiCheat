package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SprintLogicTest {

    @Test
    void hungryBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_HUNGRY));
    }

    @Test
    void sneakingBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_SNEAKING));
    }

    @Test
    void usingItemBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_USING_ITEM));
    }

    @Test
    void blindedBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_BLINDED));
    }

    @Test
    void headBlockedBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_HEAD_BLOCKED));
    }

    @Test
    void inLiquidBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_IN_LIQUID));
    }

    @Test
    void normalStateAllowsSprint() {
        assertTrue(SprintLogic.canSprint(0));
    }

    @Test
    void flipInBlockedStateIsViolation() {
        assertTrue(SprintLogic.isIllegalFlip(SprintLogic.STATE_HUNGRY));
    }

    @Test
    void flipInLegalStateIsNotViolation() {
        assertFalse(SprintLogic.isIllegalFlip(0));
    }

    // ---- 权威状态（直判路径）/ 合法但非权威状态 ----

    @Test
    void authoritativeStates_areAuthoritative() {
        assertTrue(SprintLogic.isAuthoritative(SprintLogic.STATE_HUNGRY));
        assertTrue(SprintLogic.isAuthoritative(SprintLogic.STATE_SNEAKING));
        assertTrue(SprintLogic.isAuthoritative(SprintLogic.STATE_USING_ITEM));
        assertTrue(SprintLogic.isAuthoritative(SprintLogic.STATE_IN_LIQUID));
    }

    @Test
    void legalInVanilla_statesAreNotAuthoritative() {
        // 1.8 客户端不禁疾跑：失明/头顶挡（2 格高走廊疾跑、失明疾跑均合法）
        assertFalse(SprintLogic.isAuthoritative(SprintLogic.STATE_BLINDED));
        assertFalse(SprintLogic.isAuthoritative(SprintLogic.STATE_HEAD_BLOCKED));
    }

    @Test
    void combinedBlocked_authoritativeWhenAnyAuthorityPresent() {
        assertTrue(SprintLogic.isAuthoritative(SprintLogic.STATE_HEAD_BLOCKED
                | SprintLogic.STATE_USING_ITEM));
    }

    @Test
    void blockedStateName_listsAllBits() {
        assertTrue(SprintLogic.blockedStateName(SprintLogic.STATE_USING_ITEM).contains("UsingItem"));
        assertTrue(SprintLogic.blockedStateName(SprintLogic.STATE_IN_LIQUID).contains("InLiquid"));
        assertTrue(SprintLogic.blockedStateName(SprintLogic.STATE_HUNGRY
                | SprintLogic.STATE_BLINDED).contains("Hungry"));
        assertTrue(SprintLogic.blockedStateName(SprintLogic.STATE_HUNGRY
                | SprintLogic.STATE_BLINDED).contains("Blinded"));
        assertTrue(SprintLogic.blockedStateName(0).equals("None"));
    }
}