package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

public final class NoFallCheck extends Check {

    public NoFallCheck(AntiCheatManager manager) {
        super(CheckType.NOFALL, manager);
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
        if (System.currentTimeMillis() - data.lastTeleportTime <= 1000L) {
            data.noFallMaxY = ctx.y;
            data.noFallMinY = ctx.y;
            drain(data, "nofall", 0.05D);
            drain(data, "nofalldmg", 0.05D);
            data.prevClientOnGround = data.clientOnGround;
            return;
        }
        if (data.velocity.pending() && data.velocity.ticksSince() < 20) {
            data.noFallMaxY = ctx.y;
            data.noFallMinY = ctx.y;
            drain(data, "nofall", 0.05D);
            drain(data, "nofalldmg", 0.05D);
            data.prevClientOnGround = data.clientOnGround;
            return;
        }
        if (m.ladderTicks > 0) {
            drain(data, "nofall", 0.05D);
            data.prevClientOnGround = data.clientOnGround;
            return;
        }
        if (m.slimeTicks > 0 || m.inWebTicks > 0 || m.nearLiquidTicks > 0) {
            data.noFallMaxY = ctx.y;
            data.noFallMinY = ctx.y;
        } else if (m.airTicks > 0) {
            if (ctx.y > data.noFallMaxY) {
                data.noFallMaxY = ctx.y;
            }
            if (data.noFallMinY == 0D || ctx.y < data.noFallMinY) {
                data.noFallMinY = ctx.y;
            }
        } else if (data.lastAirTicks > 0) {
            double fall = data.noFallMaxY - data.noFallMinY;
            double threshold = (isStrict() ? 2.5D : 3.0D) + data.jumpLevel * 0.5D;
            if (fall - threshold > (isStrict() ? 0.2D : 0.5D)
                    && m.nearLiquidTicks == 0
                    && System.currentTimeMillis() - data.lastFallDamageTime > 2500L) {
                if (bump(data, "nofalldmg", 1D, i("fall-damage-vl-before-flag", 3))) {
                    flag(data, "NoFall", "fell " + MathUtil.round(fall, 2) + " without damage");
                }
            } else {
                drain(data, "nofalldmg", 0.05D);
            }
            data.noFallMaxY = ctx.y;
            data.noFallMinY = ctx.y;
        } else {
            data.noFallMaxY = ctx.y;
            data.noFallMinY = ctx.y;
        }
        data.lastAirTicks = m.airTicks;
        if (data.clientOnGround && data.prevClientOnGround && fallingAir(m)) {
            if (data.blockBelowUnstandable) {
                if (bump(data, "nofall", 1D, i("vl-before-flag", 5))) {
                    flag(data, "NoFall", "claimed onGround airTicks=" + m.airTicks
                            + " motionY=" + MathUtil.round(m.motionY, 2));
                }
            } else {
                drain(data, "nofall", 0.05D);
            }
        } else {
            drain(data, "nofall", 0.05D);
        }
        if (fallingAir(m) && data.blockBelowUnstandable) {
            data.nofallWindowTicks++;
            if (data.clientOnGround) {
                data.nofallClaims++;
            }
            if (data.nofallWindowTicks >= si("window-ticks", 20, 10)) {
                int minClaims = si("window-min-claims", 8, 4);
                if (data.nofallClaims >= minClaims) {
                    if (bump(data, "nofall", 1D, i("vl-before-flag", 5))) {
                        flag(data, "NoFall", "air claims=" + data.nofallClaims
                                + "/" + data.nofallWindowTicks);
                    }
                } else {
                    drain(data, "nofall", 0.05D);
                }
                data.nofallWindowTicks = 0;
                data.nofallClaims = 0;
            }
        } else {
            data.nofallWindowTicks = 0;
            data.nofallClaims = 0;
        }
        data.prevClientOnGround = data.clientOnGround;
    }

    private boolean fallingAir(MovementTracker m) {
        return m.airTicks > (isStrict() ? 5 : 8) && m.motionY < (isStrict() ? -0.3D : -0.4D);
    }

    @Override
    protected void onLook(PlayerData data, boolean onGround) {
        if (!isEnabled()) {
            return;
        }
        if (!onGround || data.creative || data.flying || data.inVehicle || data.dead
                || data.ping > cfg.maxPing()) {
            return;
        }
        if (System.currentTimeMillis() - data.lastTeleportTime <= 500L) {
            return;
        }
        if (data.velocity.pending() && data.velocity.ticksSince() < 20) {
            return;
        }
        MovementTracker m = data.movement;
        if (fallingAir(m) && data.blockBelowUnstandable) {
            if (bump(data, "nofall", 1D, i("vl-before-flag", 5))) {
                flag(data, "NoFall", "look claimed onGround airTicks=" + m.airTicks
                        + " motionY=" + MathUtil.round(m.motionY, 2));
            }
        } else {
            drain(data, "nofall", 0.05D);
        }
    }
}
