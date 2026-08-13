package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

public final class FlyCheck extends Check {

    public FlyCheck(AntiCheatManager manager) {
        super(CheckType.FLY, manager);
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
        if (!data.movement.initialized) {
            return;
        }
        checkRise(data);
        checkLevel(data);
    }

    private void checkRise(PlayerData data) {
        if (!isSubEnabled("rise")) {
            return;
        }
        MovementTracker m = data.movement;
        double motionY = m.motionY;
        if (m.slimeTicks > 0) {
            drain(data, "rise", 0.05D);
            return;
        }
        if (m.jumpedThisTick || !m.airborne) {
            drain(data, "rise", 0.05D);
            return;
        }
        if (m.ladderTicks > 0) {
            drain(data, "rise", 0.05D);
            return;
        }
        if (Math.abs(m.lastY - m.lastLastY) > 0.45D && m.distanceXZ < 0.1D) {
            drain(data, "rise", 0.05D);
            return;
        }
        double expected = data.velocity.vertical()
                + (m.airTicks < 45 ? sd("rise.jump-tolerance", 0.05D, 0.02D)
                        : sd("rise.full-tolerance", 0.02D, 0.005D));
        if (m.airTicks < 45) {
            expected += Math.max(0.12D, 0.42D * Math.pow(0.98D, m.airTicks))
                    + data.jumpLevel * (isStrict() ? 0.05D : 0.1D);
        }
        if (m.nearLiquidTicks > 0) {
            expected += 0.1D;
        }
        if (motionY > expected) {
            if (data.lastRiseOver || isStrict()) {
                if (bump(data, "rise", 1D, i("rise.vl-before-flag", 5))) {
                    flag(data, "Rise", "motionY=" + MathUtil.round(motionY, 2)
                            + " expected=" + MathUtil.round(expected, 2));
                }
            } else {
                data.lastRiseOver = true;
            }
        } else {
            data.lastRiseOver = false;
            drain(data, "rise", 0.05D);
        }
    }

    private void checkLevel(PlayerData data) {
        if (!isSubEnabled("level")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastTeleportTime <= 500L) {
            data.hoverTicks = 0;
            drain(data, "level", 0.1D);
            return;
        }
        if (data.velocity.pending() && data.velocity.ticksSince() < 20) {
            data.hoverTicks = 0;
            drain(data, "level", 0.1D);
            return;
        }
        double motionY = data.movement.motionY;
        if (data.movement.nearLiquidTicks > 0 || data.movement.inWebTicks > 0 || data.movement.ladderTicks > 0) {
            data.hoverTicks = 0;
            drain(data, "level", 0.1D);
            return;
        }
        if (!data.clientOnGround && !data.movement.jumpedThisTick && Math.abs(motionY) < 0.05D) {
            data.hoverTicks++;
        } else {
            data.hoverTicks = 0;
        }
        int max = si("level.max-hover-ticks", 8, 4);
        if (data.hoverTicks >= max) {
            if (bump(data, "level", 1D, i("level.vl-before-flag", 6))) {
                flag(data, "Level", "hover=" + data.hoverTicks + " max=" + max);
            }
        } else {
            drain(data, "level", 0.1D);
        }
    }
}