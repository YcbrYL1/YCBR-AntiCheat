package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.snapshot.EntitySnapshot;

class ReachCancelTest {

    private static EntitySnapshot snap(double x, double y, double z, double w, double h) {
        return new EntitySnapshot(1, UUID.randomUUID(), "PLAYER", x, y, z, w, h,
                System.currentTimeMillis(), 0D, 0D, 0D);
    }

    private static PlayerData player() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        data.movement.lastX = 0;
        data.movement.lastY = 0;
        data.movement.lastZ = 0;
        return data;
    }

    @Test
    void farTargetCancels() {
        EntitySnapshot t = snap(20, 0, 20, 0.6, 1.8);
        assertTrue(ReachCheck.shouldCancelAttack(player(), t, 3.1, 0.03, 2, 0, 0.05));
    }

    @Test
    void closeTargetNotCancelled() {
        EntitySnapshot t = snap(2, 0, 0, 0.6, 1.8);
        assertFalse(ReachCheck.shouldCancelAttack(player(), t, 3.1, 0.03, 2, 0, 0.05));
    }
}
