package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.PredictionEngine;
import com.ycbr.anticheat.simulation.ShadowPlayer;
import com.ycbr.anticheat.simulation.WorldProbe;

/**
 * 预测引擎检测（初级版 Grim）。默认关闭，稳定后可切换。
 * sim-speed：水平位移模长超过全部合法候选的上界 → 加速；
 * sim-fly：垂直位移偏离全部合法候选 → 上升过快/悬浮。
 */
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
        ShadowPlayer shadow = data.shadow;
        float yaw = (float) ctx.yaw;
        WorldProbe.ProbeResult probe = WorldProbe.fromPlayerData(data);
        double frictionFactor = probe.surface.friction;
        boolean sprinting = m.sprinting;
        boolean sneaking = false;
        double speedLevel = data.speedLevel;
        double jumpLevel = data.jumpLevel;

        long elapsed = ctx.arrivalTime - shadow.lastSyncTime;
        if (elapsed < 0) {
            elapsed = TICK_MS;
        }
        int ticks = (int) Math.min(MAX_TICKS, Math.max(1, Math.ceil((double) elapsed / TICK_MS)));

        PredictionEngine.Candidate[] cands;
        if (ticks > 1) {
            cands = PredictionEngine.candidatesMultiTick(
                    shadow.motionX, shadow.motionZ, shadow.motionY,
                    shadow.onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel, ticks,
                    probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false);
        } else {
            cands = PredictionEngine.candidates(
                    shadow.motionX, shadow.motionY, shadow.motionZ,
                    shadow.onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel,
                    probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false);
        }

        double actualDX = ctx.x - shadow.posX;
        double actualDY = ctx.y - shadow.posY;
        double actualDZ = ctx.z - shadow.posZ;
        double actualH = Math.hypot(actualDX, actualDZ);

        double hTol = sd("sim-speed.horizontal-tolerance", 0.01D, 0.005D);
        double vTol = sd("sim-fly.vertical-tolerance", 0.02D, 0.01D);

        // 液体/网/梯子预测精度下降：容差放大，防误判
        if (probe.inLiquid || probe.inWeb || probe.onLadder) {
            double mult = sd("sim-speed.liquid-tolerance-multiplier", 2.0D, 2.0D);
            hTol *= mult;
            vTol *= mult;
        }
        if (ticks > 1) {
            hTol *= Math.sqrt(ticks);
            vTol *= Math.sqrt(ticks);
        }

        // 水平：模长匹配（方向无关，抗斜向/侧移误判）。idle 候选覆盖静止。
        double maxH = 0.0;
        for (PredictionEngine.Candidate c : cands) {
            double ch = Math.hypot(c.deltaX, c.deltaZ);
            if (ch > maxH) {
                maxH = ch;
            }
        }
        boolean hMatch = actualH <= maxH + hTol;
        if (hMatch) {
            // 方向联合匹配（8AC P2.1，默认关）：模长命中后校验位移方向与
            // 某个候选一致。对高 ping 方向漂移敏感，ticks>=3 放宽角度。
            if (isSubEnabled("direction-match") && actualH > 1e-4) {
                double maxAngleDeg = ticks >= 3 ? 45.0 : d("direction-match.max-angle-deg", 30.0);
                hMatch = false;
                for (PredictionEngine.Candidate c : cands) {
                    double ch = Math.hypot(c.deltaX, c.deltaZ);
                    if (ch <= 1e-4) {
                        continue;
                    }
                    double dot = actualDX * c.deltaX + actualDZ * c.deltaZ;
                    double cosA = dot / (actualH * ch);
                    double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosA))));
                    if (angle <= maxAngleDeg) {
                        hMatch = true;
                        break;
                    }
                }
            }
            drain(data, "sim-speed", 0.05D);
        } else {
            double over = actualH - maxH - hTol;
            if (over > 0.005D) {
                if (bump(data, "sim-speed", 1D, i("sim-speed.vl-before-flag", 8))) {
                    flag(data, "sim-speed",
                            "h=" + String.format("%.4f", actualH)
                            + " max=" + String.format("%.4f", maxH)
                            + " tol=" + String.format("%.3f", hTol)
                            + " ticks=" + ticks);
                }
            } else {
                drain(data, "sim-speed", 0.02D);
            }
        }

        // 垂直：取与最近合法垂直增量的偏差
        double bestVDist = Double.MAX_VALUE;
        for (PredictionEngine.Candidate c : cands) {
            double vDist = Math.abs(actualDY - c.motionY);
            if (vDist < bestVDist) {
                bestVDist = vDist;
            }
        }
        // 台阶/楼梯自动步进豁免：引擎无步进模型，motY 只能到 0/0.42，
        // 而走上半砖/楼梯 motY 可达 ±0.5；仅在脚下确为台阶/楼梯地形时放行。
        boolean stepUp = WorldProbe.stepVerticalAllowed(actualDY, data.blockOnStairsOrSlab);
        // 粘液块弹跳豁免：1.8 粘液块落地反弹 |dy| 可达 ~0.63，引擎无弹跳模型。
        boolean slimeBounce = WorldProbe.slimeBounceAllowed(actualDY, data.blockOnSlime);
        boolean vMatch = bestVDist <= vTol || stepUp || slimeBounce;
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

        resyncShadow(shadow, ctx, yaw, probe, sprinting, sneaking, speedLevel, jumpLevel);
    }

    /**
     * 重同步 shadow：位置/水平增量直接取客户端实际值；
     * 垂直状态按 NMS 推导（空中: (ΔY-0.08)*0.98；地面: 0），下包即可继续预测。
     * onGround 只信服务器判定。
     */
    private void resyncShadow(ShadowPlayer shadow, MoveContext ctx, float yaw,
            WorldProbe.ProbeResult probe, boolean sprinting, boolean sneaking,
            double speedLevel, double jumpLevel) {
        double actualDY = ctx.y - shadow.posY;
        boolean serverGround = Math.abs(actualDY) < 0.001D && !probe.inWeb && !probe.onLadder;
        double nextMotY;
        if (serverGround && !probe.inLiquid) {
            nextMotY = 0.0;
        } else if (probe.onLadder) {
            nextMotY = PredictionEngine.LADDER_CLIMB;
        } else {
            nextMotY = (actualDY - PredictionEngine.GRAVITY) * PredictionEngine.VERTICAL_DRAG;
        }
        shadow.sync(ctx.x, ctx.y, ctx.z,
                actualDX(ctx, shadow), nextMotY, actualDZ(ctx, shadow),
                ctx.data.movement.onGround, serverGround, yaw, ctx.arrivalTime);
    }

    private static double actualDX(MoveContext ctx, ShadowPlayer shadow) {
        return ctx.x - shadow.posX;
    }

    private static double actualDZ(MoveContext ctx, ShadowPlayer shadow) {
        return ctx.z - shadow.posZ;
    }
}
