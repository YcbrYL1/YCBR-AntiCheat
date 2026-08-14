package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.simulation.PhysicsConstants;
import com.ycbr.anticheat.util.MathUtil;

public final class CriticalsCheck extends Check {

    public CriticalsCheck(AntiCheatManager manager) {
        super(CheckType.CRITICALS, manager);
    }

    @Override
    protected void onAttack(AttackContext ctx) {
        if (!isEnabled()) {
            return;
        }
        if (!isSubEnabled("air")) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.dead || data.ping > cfg.maxPing()) {
            return;
        }
        if (System.currentTimeMillis() - data.lastTeleportTime <= 1000L) {
            return;
        }
        if (data.velocity.pending() && data.velocity.ticksSince() < 20) {
            return;
        }
        MovementTracker m = data.movement;
        if (!data.movement.initialized || m.nearLiquidTicks > 0 || m.slimeTicks > 0 || m.inWebTicks > 0
                || m.ladderTicks > 0) {
            return;
        }
        if (!m.airborne || m.jumpedThisTick) {
            drain(data, "air", 0.1D);
            return;
        }
        if (Math.abs(m.lastY - m.lastLastY) > 0.45D && m.distanceXZ < 0.1D) {
            drain(data, "air", 0.1D);
            return;
        }
        double kb = data.velocity.vertical();
        double expectedMax = Math.max(0D,
                PhysicsConstants.JUMP_VELOCITY * Math.pow(PhysicsConstants.VERTICAL_DRAG, m.airTicks))
                + sd("air.jump-tolerance", 0.05D, 0.02D) + kb
                + data.jumpLevel * PhysicsConstants.JUMP_POTION_PER_LEVEL;
        boolean susp = (m.airTicks <= 6 && m.motionY > expectedMax)
                || (m.airTicks > 6 && m.motionY > PhysicsConstants.GRAVITY + kb);
        if (susp) {
            if (bump(data, "air", 1D, i("air.vl-before-flag", 5))) {
                flag(data, "Air", "jump-curve violation airTicks=" + m.airTicks
                        + " motionY=" + MathUtil.round(m.motionY, 3));
            }
        } else {
            drain(data, "air", 0.1D);
        }
    }
}