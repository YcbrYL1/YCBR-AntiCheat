package com.ycbr.anticheat.util;

import org.bukkit.Material;

/**
 * "使用物品"语义判定（纯逻辑，可单测）。
 *
 * <p>1.8 协议中右键发 BLOCK_PLACE 包，既有放置方块（face 0-5）也有使用物品
 * （吃东西/喝药/拉弓/牛奶/钓鱼，face 255/-1）。只有后者才会让客户端进入
 * "使用物品"状态并减速（motX/Z *= 0.2），放置方块不会减速也不会发
 * BLOCK_DIG status 5 复位。若把放置方块误判为使用物品，usingItem 会卡死，
 * 之后的正常走路会被 NoSlow 误判。</p>
 */
public final class ItemUseLogic {

    private ItemUseLogic() {
    }

    /**
     * 手持物品是否属于"使用物品"类（吃/喝/拉弓/牛奶/钓鱼 → 客户端减速）。
     * 放置方块（方块类物品）、工具、剑（格挡单独由 blockingSword 处理）不算。
     */
    public static boolean isUseItem(Material held) {
        if (held == null) {
            return false;
        }
        if (held.isEdible()) {
            return true;
        }
        switch (held) {
        case BOW:
        case POTION:
        case MILK_BUCKET:
        case FISHING_ROD:
            return true;
        default:
            return false;
        }
    }

    /**
     * usingItem 超时复位：客户端中途退出物品使用（不发 BLOCK_DIG status 5）时，
     * usingItem 会卡 true 导致 Sprint/NoSlow 状态误判。超过 timeoutMs 未再使用
     * 即视为过期，调用方应置 usingItem = false。
     */
    public static boolean expired(long now, long lastUseTime, long timeoutMs) {
        return lastUseTime > 0L && now - lastUseTime >= timeoutMs;
    }
}