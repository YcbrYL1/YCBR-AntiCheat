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
}