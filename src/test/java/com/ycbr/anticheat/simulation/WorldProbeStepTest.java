package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.data.PlayerData;

class WorldProbeStepTest {

    @Test
    void slabsAndDoubleSlabs_areStepLike() {
        assertTrue(WorldProbe.isStepMaterial(Material.STEP));
        assertTrue(WorldProbe.isStepMaterial(Material.WOOD_STEP));
        assertTrue(WorldProbe.isStepMaterial(Material.DOUBLE_STEP));
        assertTrue(WorldProbe.isStepMaterial(Material.WOOD_DOUBLE_STEP));
    }

    @Test
    void stairs_areStepLike() {
        assertTrue(WorldProbe.isStepMaterial(Material.WOOD_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.COBBLESTONE_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.BRICK_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.SMOOTH_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.NETHER_BRICK_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.SANDSTONE_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.SPRUCE_WOOD_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.BIRCH_WOOD_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.JUNGLE_WOOD_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.QUARTZ_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.ACACIA_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.DARK_OAK_STAIRS));
        assertTrue(WorldProbe.isStepMaterial(Material.RED_SANDSTONE_STAIRS));
    }

    @Test
    void flatBlocks_areNotStepLike() {
        assertFalse(WorldProbe.isStepMaterial(Material.GRASS));
        assertFalse(WorldProbe.isStepMaterial(Material.DIRT));
        assertFalse(WorldProbe.isStepMaterial(Material.STONE));
        assertFalse(WorldProbe.isStepMaterial(Material.WOOD));
        assertFalse(WorldProbe.isStepMaterial(Material.AIR));
        assertFalse(WorldProbe.isStepMaterial(null));
    }

    @Test
    void stepVerticalAllowed_onStepTerrain_smallDelta() {
        assertTrue(WorldProbe.stepVerticalAllowed(0.5, true));
        assertTrue(WorldProbe.stepVerticalAllowed(-0.5, true));
        assertTrue(WorldProbe.stepVerticalAllowed(0.0, true));
        assertTrue(WorldProbe.stepVerticalAllowed(0.599, true));
    }

    @Test
    void stepVerticalAllowed_exceedingStepHeight_rejected() {
        assertFalse(WorldProbe.stepVerticalAllowed(0.61, true));
        assertFalse(WorldProbe.stepVerticalAllowed(1.0, true));
        assertFalse(WorldProbe.stepVerticalAllowed(-1.0, true));
    }

    @Test
    void stepVerticalAllowed_flatTerrain_rejected() {
        assertFalse(WorldProbe.stepVerticalAllowed(0.5, false));
        assertFalse(WorldProbe.stepVerticalAllowed(0.2, false));
    }

    @Test
    void slimeBounceAllowed_onSlime_smallDelta() {
        assertTrue(WorldProbe.slimeBounceAllowed(0.6, true));
        assertTrue(WorldProbe.slimeBounceAllowed(-0.6, true));
        assertTrue(WorldProbe.slimeBounceAllowed(0.63, true));
    }

    @Test
    void slimeBounceAllowed_exceedingBounceEnvelope_rejected() {
        assertFalse(WorldProbe.slimeBounceAllowed(1.2, true));
        assertFalse(WorldProbe.slimeBounceAllowed(-1.0, true));
    }

    @Test
    void slimeBounceAllowed_flatTerrain_rejected() {
        assertFalse(WorldProbe.slimeBounceAllowed(0.6, false));
        assertFalse(WorldProbe.slimeBounceAllowed(0.3, false));
    }

    // ---- 活塞推动豁免 ----

    @Test
    void pistonMovingPiece_below_isExempted() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        data.blockOnPiston = true;
        assertTrue(WorldProbe.fromPlayerData(data).onPiston);
    }

    @Test
    void normalSurface_notExempted() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        data.blockOnPiston = false;
        assertFalse(WorldProbe.fromPlayerData(data).onPiston);
    }
}