package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.PredictionEngine;
import com.ycbr.anticheat.simulation.ShadowPlayer;

public final class SimulationCheck extends Check {

    private static final long TICK_MS = 50L;
    private static final int MAX_TICKS = 4;

    public SimulationCheck(AntiCheatManager manager) {
        super(CheckType.SIMULATION, manager);
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
        if (m.nearLiquidTicks > 0 || m.inWebTicks > 0 || m.ladderTicks > 0) {
            return;
        }

        ShadowPlayer shadow = data.shadow;
        float yaw = (float) ctx.yaw;
        double frictionFactor = getFrictionFactor(data);
        boolean sprinting = m.sprinting;
        boolean sneaking = false;
        double speedLevel = data.speedLevel;
        double jumpLevel = data.jumpLevel;

        long elapsed = ctx.arrivalTime - shadow.lastSyncTime;
        if (elapsed < 0) elapsed = TICK_MS;
        int ticks = (int) Math.min(MAX_TICKS, Math.max(1, Math.ceil((double) elapsed / TICK_MS)));

        PredictionEngine.Candidate[] cands;
        if (ticks > 1) {
            cands = PredictionEngine.candidatesMultiTick(
                    shadow.motionX, shadow.motionZ, shadow.motionY,
                    shadow.onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel, ticks);
        } else {
            cands = PredictionEngine.candidates(
                    shadow.motionX, shadow.motionZ, shadow.onGround, yaw,
                    frictionFactor, sprinting, speedLevel, jumpLevel);
        }

        double actualDX = ctx.x - shadow.posX;
        double actualDY = ctx.y - shadow.posY;
        double actualDZ = ctx.z - shadow.posZ;

        double hTol = sd("sim-speed.horizontal-tolerance", 0.03D, 0.01D);
        double vTol = sd("sim-fly.vertical-tolerance", 0.05D, 0.03D);

        if (ticks > 1) {
            hTol *= Math.sqrt(ticks);
            vTol *= Math.sqrt(ticks);
        }

        boolean hMatch = false;
        double bestHDist = Double.MAX_VALUE;
        for (PredictionEngine.Candidate c : cands) {
            double dx = actualDX - c.deltaX;
            double dz = actualDZ - c.deltaZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < bestHDist) {
                bestHDist = dist;
            }
            if (dist <= hTol) {
                hMatch = true;
                break;
            }
        }

        if (hMatch) {
            drain(data, "sim-speed", 0.05D);
        } else {
            double over = bestHDist - hTol;
            if (over > 0.005D) {
                if (bump(data, "sim-speed", 1D, i("sim-speed.vl-before-flag", 8))) {
                    flag(data, "sim-speed",
                            "hDist=" + String.format("%.4f", bestHDist)
                            + " tol=" + String.format("%.3f", hTol)
                            + " ticks=" + ticks);
                }
            } else {
                drain(data, "sim-speed", 0.02D);
            }
        }

        double bestVDist = Double.MAX_VALUE;
        for (PredictionEngine.Candidate c : cands) {
            double vDist = Math.abs(actualDY - c.motionY);
            if (vDist < bestVDist) {
                bestVDist = vDist;
            }
        }
        boolean vMatch = bestVDist <= vTol;

        if (vMatch) {
            drain(data, "sim-fly", 0.05D);
        } else {
            double over = bestVDist - vTol;
            if (over > 0.005D) {
                if (bump(data, "sim-fly", 1D, i("sim-fly.vl-before-flag", 10))) {
                    flag(data, "sim-fly",
                            "vDist=" + String.format("%.4f", bestVDist)
                            + " tol=" + String.format("%.3f", vTol)
                            + " ticks=" + ticks);
                }
            } else {
                drain(data, "sim-fly", 0.02D);
            }
        }

        resyncShadow(shadow, ctx, yaw, frictionFactor, sprinting, sneaking,
                speedLevel, jumpLevel);
    }

    private void resyncShadow(ShadowPlayer shadow, MoveContext ctx, float yaw,
            double frictionFactor, boolean sprinting, boolean sneaking,
            double speedLevel, double jumpLevel) {
        shadow.sync(ctx.x, ctx.y, ctx.z,
                ctx.x - shadow.posX, ctx.y - shadow.posY, ctx.z - shadow.posZ,
                ctx.data.movement.onGround, yaw, ctx.arrivalTime);
    }

    private double getFrictionFactor(PlayerData data) {
        MovementTracker m = data.movement;
        if (m.iceTicks > 0) return 0.98D;
        if (m.slimeTicks > 0) return 0.8D;
        return 0.6D;
    }
}
