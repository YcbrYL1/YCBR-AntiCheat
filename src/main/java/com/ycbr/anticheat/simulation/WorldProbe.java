package com.ycbr.anticheat.simulation;

import org.bukkit.Material;

import com.ycbr.anticheat.data.PlayerData;

/**
 * 世界查询门面：把 PlayerData 中 MainThreadHandler 每 tick 探测的
 * 方块状态（blockOnIce/blockNearLiquid/blockInWeb 等）转换为
 * PredictionEngine 消费的物理状态。不做重复方块查询。
 */
public final class WorldProbe {

    public enum Surface {
        NORMAL(0.6),
        ICE(0.98),
        SLIME(0.8),
        SOUL_SAND(0.4),
        AIR(0.91);

        public final double friction;

        Surface(double friction) {
            this.friction = friction;
        }
    }

    public static final class ProbeResult {
        public Surface surface = Surface.NORMAL;
        public boolean inLiquid;
        public boolean inWeb;
        public boolean onLadder;
        public boolean headBlocked;
        public boolean belowUnstandable;
        public boolean onPiston;

        public boolean anySpecial() {
            return inLiquid || inWeb || onLadder || headBlocked || belowUnstandable || onPiston
                    || surface != Surface.NORMAL;
        }
    }

    private WorldProbe() {}

    /**
     * 从 PlayerData 已探测字段构建物理状态（主线程调用，无方块查询）。
     */
    public static ProbeResult fromPlayerData(PlayerData data) {
        ProbeResult r = new ProbeResult();
        if (data.blockOnIce) {
            r.surface = Surface.ICE;
        } else if (data.blockOnSlime) {
            r.surface = Surface.SLIME;
        } else if (data.blockOnSoulSand) {
            r.surface = Surface.SOUL_SAND;
        } else if (data.blockBelowUnstandable) {
            r.surface = Surface.AIR;
        }
        r.inLiquid = data.blockNearLiquid;
        r.inWeb = data.blockInWeb;
        r.onLadder = data.blockOnLadder;
        r.headBlocked = data.blockBoxedIn;
        r.belowUnstandable = data.blockBelowUnstandable;
        r.onPiston = data.blockOnPiston;
        return r;
    }

    /**
     * 该方块是否属于"台阶/楼梯"类（玩家可自动步进 0.5 高度的地形）。
     * 1.8 半砖与楼梯：走过时 motY 会短暂达到 ±0.5，模拟引擎无步进模型，
     * 若不豁免会被 sim-fly 误判。
     */
    public static boolean isStepMaterial(Material m) {
        if (m == null) {
            return false;
        }
        switch (m) {
        case STEP:
        case WOOD_STEP:
        case DOUBLE_STEP:
        case WOOD_DOUBLE_STEP:
        case WOOD_STAIRS:
        case COBBLESTONE_STAIRS:
        case BRICK_STAIRS:
        case SMOOTH_STAIRS:
        case NETHER_BRICK_STAIRS:
        case SANDSTONE_STAIRS:
        case SPRUCE_WOOD_STAIRS:
        case BIRCH_WOOD_STAIRS:
        case JUNGLE_WOOD_STAIRS:
        case QUARTZ_STAIRS:
        case ACACIA_STAIRS:
        case DARK_OAK_STAIRS:
        case RED_SANDSTONE_STAIRS:
            return true;
        default:
            return false;
        }
    }

    /**
     * 台阶/楼梯步进产生的垂直位移是否在合理步高内（|dy| ≤ 0.6，覆盖 0.5 半砖）。
     * 仅当脚下确实是台阶/楼梯地形时生效，平地/飞行不豁免。
     */
    public static boolean stepVerticalAllowed(double actualDY, boolean onStepTerrain) {
        return onStepTerrain && Math.abs(actualDY) <= 0.6D;
    }

    /**
     * 粘液块弹跳豁免：1.8 粘液块落地反弹（下落速度反跳约 1.5 倍），
     * 引擎无弹跳模型，|dy| 可达 ~0.6（起跳反弹 0.42×1.5≈0.63）。
     * 仅当脚下确为粘液块时生效；作弊者垂直速度 &gt;0.65 仍会被抓。
     */
    public static boolean slimeBounceAllowed(double actualDY, boolean onSlime) {
        return onSlime && Math.abs(actualDY) <= 0.65D;
    }
}