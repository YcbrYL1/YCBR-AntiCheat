package com.ycbr.anticheat.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ItemUseLogicTest {

    @Test
    void food_triggersItemUse() {
        assertTrue(ItemUseLogic.isUseItem(Material.BREAD));
        assertTrue(ItemUseLogic.isUseItem(Material.APPLE));
        assertTrue(ItemUseLogic.isUseItem(Material.COOKED_BEEF));
        assertTrue(ItemUseLogic.isUseItem(Material.GOLDEN_APPLE));
    }

    @Test
    void bow_potion_milk_fishingRod_triggerItemUse() {
        assertTrue(ItemUseLogic.isUseItem(Material.BOW));
        assertTrue(ItemUseLogic.isUseItem(Material.POTION));
        assertTrue(ItemUseLogic.isUseItem(Material.MILK_BUCKET));
        assertTrue(ItemUseLogic.isUseItem(Material.FISHING_ROD));
    }

    @Test
    void blockPlacement_doesNotTriggerItemUse() {
        assertFalse(ItemUseLogic.isUseItem(Material.DIRT));
        assertFalse(ItemUseLogic.isUseItem(Material.STONE));
        assertFalse(ItemUseLogic.isUseItem(Material.WOOD));
        assertFalse(ItemUseLogic.isUseItem(Material.TORCH));
        assertFalse(ItemUseLogic.isUseItem(Material.WOOD_STEP));
        assertFalse(ItemUseLogic.isUseItem(Material.PISTON_BASE));
    }

    @Test
    void toolsAndWeapons_doNotTriggerItemUse() {
        assertFalse(ItemUseLogic.isUseItem(Material.DIAMOND_PICKAXE));
        assertFalse(ItemUseLogic.isUseItem(Material.DIAMOND_SWORD));
        assertFalse(ItemUseLogic.isUseItem(Material.IRON_AXE));
    }

    @Test
    void null_doesNotTriggerItemUse() {
        assertFalse(ItemUseLogic.isUseItem(null));
    }

    // ---- usingItem 超时复位（Sprint 残留 FP：客户端中途退出物品使用不发 dig status 5）----

    @Test
    void expired_afterTimeout() {
        long now = 10_000L;
        assertTrue(ItemUseLogic.expired(now, now - 1600L, 1500L), "超时后应过期");
        assertTrue(ItemUseLogic.expired(now, now - 1500L, 1500L), "恰好超时应过期");
    }

    @Test
    void expired_withinTimeout() {
        long now = 10_000L;
        assertFalse(ItemUseLogic.expired(now, now - 100L, 1500L), "窗口内不应过期");
        assertFalse(ItemUseLogic.expired(now, 0L, 1500L), "从未使用不应过期");
    }
}