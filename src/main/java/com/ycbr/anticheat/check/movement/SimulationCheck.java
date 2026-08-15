package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.KnownExemptions;
import com.ycbr.anticheat.simulation.PredictionEngine;
import com.ycbr.anticheat.simulation.ShadowPlayer;
import com.ycbr.anticheat.simulation.VoxelGrid;
import com.ycbr.anticheat.simulation.WorldProbe;

/**
 * 预测引擎检测（初级版 Grim）。默认开启（P0-1），配套关闭弃用启发式 Speed/Fly/NoFall。
 * sim-speed：水平位移模长超过全部合法候选的上界 → 加速；
 * sim-fly：垂直位移偏离全部合法候选 → 上升过快/悬浮。
 * 单 tick 与多 tick（高 ping）路径均优先 AABB 碰撞重演（VoxelGrid + CollisionResolver），
 * 网格缺失/过期/越界自动回退旧墙距路径。
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
        // sim-speed / sim-fly 子开关（config checks.simulation.sim-speed.enabled 等）：
        // 此前子开关被忽略（顶层开即全跑），用户无法单独控制——已接线生效。
        boolean speedOn = isSubEnabled("sim-speed");
        boolean flyOn = isSubEnabled("sim-fly");
        if (!speedOn && !flyOn) {
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

        // P1 碰撞重演路径：体素网格新鲜可用时，用 AABB 逐轴解析替代墙距/台阶豁免。
        // 网格缺失/过期/越界 → 自动回退旧（墙距+豁免）路径，零误判兜底。
        boolean collisionReplay = cfg.raw().getBoolean(
                "checks.simulation.sim-speed.collision-replay", true);
        VoxelGrid grid = null;
        if (collisionReplay && data.voxelGrid != null) {
            long maxAge = cfg.raw().getLong("checks.simulation.grid-max-age-ms", 250L);
            if (data.voxelGrid.isFresh(ctx.arrivalTime, maxAge)) {
                grid = data.voxelGrid;
            }
        }

        PredictionEngine.Candidate[] cands;
        boolean gridPath = false;
        if (ticks == 1 && grid != null) {
            PredictionEngine.Candidate[] g = PredictionEngine.candidatesWithCollision(
                    shadow.motionX, shadow.motionY, shadow.motionZ,
                    shadow.onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel,
                    probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false,
                    shadow.posX, shadow.posY, shadow.posZ, grid);
            if (g != null) {
                cands = g;
                gridPath = true;
            } else {
                cands = PredictionEngine.candidates(
                        shadow.motionX, shadow.motionY, shadow.motionZ,
                        shadow.onGround, yaw, frictionFactor,
                        sprinting, speedLevel, jumpLevel,
                        probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false,
                        probe.wallFwd, probe.wallLeft, probe.wallRight);
            }
        } else if (ticks > 1) {
            // P0-3：多 tick 也优先碰撞重演（逐 tick AABB 解析，落点推进）；
            // 网格不足（快跑移出覆盖）→ null 回退旧墙距路径。
            PredictionEngine.Candidate[] g = null;
            if (grid != null) {
                g = PredictionEngine.candidatesMultiTickWithCollision(
                        shadow.motionX, shadow.motionZ, shadow.motionY,
                        shadow.onGround, yaw, frictionFactor,
                        sprinting, speedLevel, jumpLevel, ticks,
                        probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false,
                        shadow.posX, shadow.posY, shadow.posZ, grid);
            }
            if (g != null) {
                cands = g;
                gridPath = true;
            } else {
                cands = PredictionEngine.candidatesMultiTick(
                        shadow.motionX, shadow.motionZ, shadow.motionY,
                        shadow.onGround, yaw, frictionFactor,
                        sprinting, speedLevel, jumpLevel, ticks,
                        probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false,
                        probe.wallFwd, probe.wallLeft, probe.wallRight);
            }
        } else {
            cands = PredictionEngine.candidates(
                    shadow.motionX, shadow.motionY, shadow.motionZ,
                    shadow.onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel,
                    probe.inLiquid, probe.inWeb, probe.onLadder, probe.headBlocked, false,
                    probe.wallFwd, probe.wallLeft, probe.wallRight);
        }

        double actualDX = ctx.x - shadow.posX;
        double actualDY = ctx.y - shadow.posY;
        double actualDZ = ctx.z - shadow.posZ;
        double actualH = Math.hypot(actualDX, actualDZ);

        // 搭路豁免：最近放置 + 正在移动（边走边放）或短窗口内 ≥N 次连续放置（快速搭路）
        // → sim-speed 与 sim-fly 完全豁免。搭路玩家在刚放下的方块上走/跳，水平碰撞微调 +
        // 体素网格异步采集不匹配 + 垂直位移大（可能 >0.2m），单次放置豁免不适用。
        // 搭路是 1.8 合法玩法，且真正的 speed/fly 作弊在搭路时仍被其他检测覆盖 → 无漏网风险。
        // 关键：附"最近放置新鲜度"窗口——搭完停止放置后豁免自动失效，避免永久豁免掩盖作弊。
        // 慢速搭路（1 格/秒）streak 达不到阈值 → 用"有放置 + 有水平位移"兜底（边走边放）。
        boolean bridgeActive = data.lastBridgePlaceTime > 0L
                && ctx.arrivalTime - data.lastBridgePlaceTime
                <= i("bridge-active-window-ms", 500)
                && (data.bridgePlaceStreak >= i("bridge-min-placements", 2)
                    || actualH > d("bridge-min-move", 0.05D));
        if (bridgeActive) {
            drain(data, "sim-speed", 0.05D);
            drain(data, "sim-fly", 0.05D);
            resyncShadow(shadow, ctx, yaw, probe, sprinting, sneaking, speedLevel, jumpLevel);
            return;
        }

        double hTol = sd("sim-speed.horizontal-tolerance", 0.01D, 0.005D);
        double vTol = sd("sim-fly.vertical-tolerance", 0.02D, 0.01D);

        // liquid/web/ladder precision drops: widen tolerance (exemption via KnownExemptions)
        if (KnownExemptions.isMediumExempt(probe)) {
            double mult = sd("sim-speed.liquid-tolerance-multiplier", 2.0D, 2.0D);
            hTol *= mult;
            vTol *= mult;
        }
        // 活塞推动豁免：位移由活塞外部驱动（可与输入叠加），sim-speed 容差放大
        if (KnownExemptions.isPistonExempt(probe)) {
            double mult = sd("sim-speed.piston-tolerance-multiplier", 3.0D, 3.0D);
            hTol *= mult;
        }
        if (KnownExemptions.multiTickSqrtFactor(ticks) > 1.0) {
            hTol *= KnownExemptions.multiTickSqrtFactor(ticks);
            vTol *= KnownExemptions.multiTickSqrtFactor(ticks);
        }

        // ---- sim-speed（水平）----
        if (speedOn) {
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
        }

        // ---- sim-fly（垂直）----
        if (flyOn) {
        // 垂直：取与最近合法垂直增量的偏差
        double bestVDist = Double.MAX_VALUE;
        for (PredictionEngine.Candidate c : cands) {
            double vDist = Math.abs(actualDY - c.motionY);
            if (vDist < bestVDist) {
                bestVDist = vDist;
            }
        }
        // 台阶/楼梯自动步进豁免：仅旧（无网格）路径需要——引擎无步进模型，
        // motY 只能到 0/0.42，而走上半砖/楼梯 motY 可达 ±0.5。
        // 碰撞重演路径下步进由 CollisionResolver 原生解析（候选已含 lift），无需豁免。
        boolean stepUp = !gridPath
                && WorldProbe.stepVerticalAllowed(actualDY, data.blockOnStairsOrSlab);
        // 粘液块弹跳豁免：1.8 粘液块落地反弹 |dy| 可达 ~0.63，引擎无弹跳模型。
        boolean slimeBounce = WorldProbe.slimeBounceAllowed(actualDY, data.blockOnSlime);
        // 放置方块豁免：1.8 客户端放置方块会产生微小垂直位移（方块与玩家
        // 碰撞微调，如放脚下被顶起 ~0.08），引擎无此模型 → 放置后短暂豁免。
        // 限微小位移（place-max-dy）防掩盖真正的飞行作弊。搭路（高频放置）已由
        // 上方 bridgeActive 完全豁免，这里只兜底单次/低频放置。
        boolean placeExempt = ctx.arrivalTime - data.lastPlaceTime
                < i("sim-fly.place-exempt-ms", 500)
                && Math.abs(actualDY) < d("sim-fly.place-max-dy", 0.2D);
        boolean vMatch = bestVDist <= vTol || stepUp || slimeBounce || placeExempt;
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
