package com.ycbr.anticheat.simulation;

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

        public boolean anySpecial() {
            return inLiquid || inWeb || onLadder || headBlocked || belowUnstandable
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
        return r;
    }
}