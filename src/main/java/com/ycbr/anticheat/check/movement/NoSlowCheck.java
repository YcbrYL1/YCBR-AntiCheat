package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.PredictionEngine;
import com.ycbr.anticheat.simulation.WorldProbe;
import com.ycbr.anticheat.util.MathUtil;

/**
 * NoSlow 检测（引擎版）：使用物品（吃/喝/拉弓）时水平位移应按 NMS 减速
 * （1.8 EntityHuman：使用物品 motX/Z *= 0.2）。用预测引擎按 usingItem=true
 * 生成"应减速"的期望位移，实际位移显著超出即 NoSlow。
 */
public final class NoSlowCheck extends Check {

    public NoSlowCheck(AntiCheatManager manager) {
        super(CheckType.NOSLOW, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.dead || data.ping > cfg.maxPing()) {
            return;
        }
        MovementTracker m = data.movement;
        boolean kbRecently = data.velocity.pending() && data.velocity.ticksSince() < 20;
        if (!m.onGround || m.groundTicks < 2
                || m.nearLiquidTicks > 0 || m.inWebTicks > 0 || m.iceTicks > 0
                || m.slimeTicks > 0 || m.ladderTicks > 0 || m.boxedIn || kbRecently) {
            data.noSlowStreak = 0;
            return;
        }
        if (!data.usingItem) {
            data.noSlowStreak = 0;
            return;
        }
        if (data.blockingSword) {
            data.noSlowStreak = 0;
            return;
        }
        if (System.currentTimeMillis() - data.lastKbTime < (isStrict() ? 800L : 1500L)) {
            data.noSlowStreak = 0;
            return;
        }
        if (System.currentTimeMillis() - data.lastItemUseTime < (isStrict() ? 50L : 100L)) {
            return;
        }

        WorldProbe.ProbeResult probe = WorldProbe.fromPlayerData(data);
        double friction = probe.surface.friction;
        float yaw = (float) ctx.yaw;

        // 使用物品时的期望位移：携带上一帧水平增量（状态约定=位置增量），预测减速后结果
        double carried = m.lastDistanceXZ;
        PredictionEngine.Result r = PredictionEngine.predictSingle(
                carried, 0.0, 0.0, true, yaw, friction,
                m.sprinting, false, false, data.speedLevel, data.jumpLevel,
                probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, true);
        double expected = Math.hypot(r.deltaX, r.deltaZ);

        // 期望位移基于携带方向正前方；实际可能侧移/转向，容差给足
        double tol = sd("tolerance", 0.045D, 0.03D);
        if (m.distanceXZ > expected + tol) {
            data.noSlowStreak++;
            if (data.noSlowStreak >= si("streak", 2, 1)
                    && bump(data, "noslow", 1D, i("vl-before-flag", 4))) {
                flag(data, "NoSlow", "xZ=" + MathUtil.round(m.distanceXZ, 3)
                        + " expected=" + MathUtil.round(expected, 3)
                        + " tol=" + MathUtil.round(tol, 3));
            }
        } else {
            data.noSlowStreak = 0;
        }
    }
}
