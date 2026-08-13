package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShadowPlayerTest {

    @Test
    void resetSetsAllToZero() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.motionX = 0.1;
        sp.motionY = 0.5;
        sp.motionZ = -0.3;
        sp.reset();
        assertEquals(0.0, sp.motionX);
        assertEquals(0.0, sp.motionY);
        assertEquals(0.0, sp.motionZ);
        assertEquals(0.0, sp.posX);
        assertEquals(0.0, sp.posY);
        assertEquals(0.0, sp.posZ);
        assertTrue(sp.onGround);
        assertEquals(0f, sp.yaw);
    }

    @Test
    void syncSetsAllFields() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.sync(1.0, 64.5, 2.0, 0.1, 0.08, -0.05, false, 90f, 1000L);
        assertEquals(1.0, sp.posX);
        assertEquals(64.5, sp.posY);
        assertEquals(2.0, sp.posZ);
        assertEquals(0.1, sp.motionX);
        assertEquals(0.08, sp.motionY);
        assertEquals(-0.05, sp.motionZ);
        assertFalse(sp.onGround);
        assertEquals(90f, sp.yaw);
        assertEquals(1000L, sp.lastSyncTime);
    }

    @Test
    void injectVelocityAddsToMotion() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.motionX = 0.1;
        sp.motionY = 0.0;
        sp.motionZ = -0.2;
        sp.injectVelocity(-0.5, 0.3, 0.1);
        assertEquals(-0.4, sp.motionX, 0.001);
        assertEquals(0.3, sp.motionY, 0.001);
        assertEquals(-0.1, sp.motionZ, 0.001);
    }

    @Test
    void tickUpdatesMotionFromPhysics() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.sync(0, 0, 0, 0, 0, 0, true, 0f, System.currentTimeMillis());
        // walk forward on ground, no jump, no sprint
        sp.tick(0.6f, false, false, false, 0, 0, 0);
        // After tick: motX = 0.1 * cos(0) * 0.546 = 0.0546
        assertEquals(0.1 * 0.546, sp.motionX, 0.01);
        assertEquals(0.0, sp.motionZ, 0.01);
        // motY = 0 - 0.08 = -0.08; -0.08 * 0.98 = -0.0784
        assertEquals(-0.0784, sp.motionY, 0.01);
    }

    @Test
    void resyncPositionDerivesMotion() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.sync(0, 0, 0, 0, 0, 0, true, 0f, 100L);
        sp.resyncPosition(0.5, 64.0, 1.0, false, 45f, 200L);
        assertEquals(0.5, sp.motionX, 0.001);
        assertEquals(64.0, sp.motionY, 0.001);
        assertEquals(1.0, sp.motionZ, 0.001);
        assertEquals(0.5, sp.posX);
        assertEquals(64.0, sp.posY);
        assertEquals(1.0, sp.posZ);
        assertEquals(200L, sp.lastSyncTime);
    }
}
