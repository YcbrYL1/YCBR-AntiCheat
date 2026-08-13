package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

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
        double expected = m.lastDistanceXZ * sd("deceleration", 1.0D, 0.92D)
                + sd("per-tick-budget", 0.03D, 0.01D);
        if (m.distanceXZ > expected) {
            data.noSlowStreak++;
            if (data.noSlowStreak >= si("streak", 2, 1)
                    && bump(data, "noslow", 1D, i("vl-before-flag", 4))) {
                flag(data, "NoSlow", "xZ=" + MathUtil.round(m.distanceXZ, 3)
                        + " expected=" + MathUtil.round(expected, 3));
            }
        } else {
            data.noSlowStreak = 0;
        }
    }
}