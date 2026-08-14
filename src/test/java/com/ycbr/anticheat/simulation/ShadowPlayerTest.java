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
        // 状态约定：motX = 携带(0)*0.546 + 输入(0.1) = 0.1（位置增量）
        assertEquals(0.1, sp.motionX, 0.01);
        assertEquals(0.0, sp.motionZ, 0.01);
        // 地面站立：地板碰撞吸收重力，垂直状态为 0（下一 tick 不会误判为下落）
        assertEquals(0.0, sp.motionY, 0.01);
    }

    @Test
    void tick_liquidVerticalOrderMatchesNms() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.sync(0, 0, 0, 0, 0.3, 0, false, 0f, System.currentTimeMillis());
        // NMS 水分支：motY = 0.3*0.8 - 0.02 = 0.22（先乘后减，不是 (0.3-0.02)*0.8）
        sp.tick(0.6f, false, false, false, 0, 0, 0, true, false, false, false, false);
        assertEquals(0.3 * 0.8 - 0.02, sp.motionY, 0.001);
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

    @Test
    void sync_ignoresClientOnGroundWhenServerSaysAirborne() {
        ShadowPlayer sp = new ShadowPlayer();
        // 客户端谎报 onGround=true，但位置差显示仍在下降
        sp.sync(0, 64.0, 0, 0.0, -0.4, 0.0, true, false, 0f, 100L);
        // 下一 tick：motionY 继续 -0.4 → 证明 shadow 未被客户端 onGround 污染
        sp.tick(0.6f, false, false, false, 0, 0, 0, false, false, false, false);
        assertTrue(sp.motionY < -0.3, "motionY=" + sp.motionY);
    }

    @Test
    void sync_trustsServerOnGroundWhenClientSaysAirborne() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.sync(0, 64.0, 0, 0.0, 0.0, 0.0, false, true, 0f, 100L);
        // 服务器判定贴地：跳起应产生正 motY
        sp.tick(0.6f, false, true, false, 0, 0, 0, false, false, false, false);
        assertTrue(sp.motionY > 0.3, "motionY=" + sp.motionY);
    }
}
