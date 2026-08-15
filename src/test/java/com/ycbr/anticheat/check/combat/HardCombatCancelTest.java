package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.data.PlayerData;

class HardCombatCancelTest {

    private static PlayerData player() {
        return new PlayerData(UUID.randomUUID());
    }

    // ---- SelfInteract：攻击自己 ---- //

    @Test
    void selfAttackCancelsWhenEnabled() {
        PlayerData data = player();
        assertTrue(KillAuraCheck.shouldHardCancel(data, 7, 7, true, true));
    }

    @Test
    void selfAttackNotCancelledWhenSubDisabled() {
        PlayerData data = player();
        assertFalse(KillAuraCheck.shouldHardCancel(data, 7, 7, false, true));
    }

    @Test
    void selfAttackNotCancelledWhenHardCancelOff() {
        PlayerData data = player();
        assertFalse(KillAuraCheck.shouldHardCancel(data, 7, 7, true, false));
    }

    @Test
    void normalAttackNeverCancelledBySelfCheck() {
        PlayerData data = player();
        assertFalse(KillAuraCheck.shouldHardCancel(data, 42, 7, true, true));
    }

    // ---- MultiInteract：突发取消窗口（Grim cancelBuffer 语义） ---- //

    @Test
    void burstWindowCancelsAnyTarget() {
        PlayerData data = player();
        data.hardCancelUntil = System.currentTimeMillis() + 10_000L;
        assertTrue(KillAuraCheck.shouldHardCancel(data, 42, 7, false, false));
    }

    @Test
    void burstWindowExpiredStopsCancelling() {
        PlayerData data = player();
        data.hardCancelUntil = System.currentTimeMillis() - 1L;
        assertFalse(KillAuraCheck.shouldHardCancel(data, 42, 7, false, false));
    }

    // ---- 权限/模式豁免 ---- //

    @Test
    void opNeverCancelled() {
        PlayerData data = player();
        data.op = true;
        assertFalse(KillAuraCheck.shouldHardCancel(data, 7, 7, true, true));
    }

    @Test
    void creativeNeverCancelled() {
        PlayerData data = player();
        data.creative = true;
        assertFalse(KillAuraCheck.shouldHardCancel(data, 7, 7, true, true));
    }
}
