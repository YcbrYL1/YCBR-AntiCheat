package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

/**
 * @deprecated 经验公式检测（魔法数容差），已被 SimulationCheck（预测引擎）取代。
 * 保留为短期冗余兜底，引擎稳定后移除。
 */
@Deprecated
public final class SpeedCheck extends Check {

    public SpeedCheck(AntiCheatManager manager) {
        super(CheckType.SPEED, manager);
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
        if (m.nearLiquidTicks > 0 || m.inWebTicks > 0) {
            return;
        }
        if (!data.movement.initialized) {
            long nowT = System.currentTimeMillis();
            boolean teleported = nowT - data.lastTeleportTime <= 1000L;
            if (!teleported && !data.velocity.pending()
                    && (m.lastMoveX > 3.0D || m.lastMoveZ > 3.0D)) {
                if (isSubEnabled("extreme")
                        && bump(data, "extreme", 3D, i("extreme.vl-before-flag", 2))) {
                    flag(data, "ExtremeMove", "phase dxz="
                            + MathUtil.round(Math.max(m.lastMoveX, m.lastMoveZ), 1)
                            + " dy=" + MathUtil.round(m.lastMoveY, 1));
                }
            }
            return;
        }
        if (m.onGround && !isSubEnabled("ground")) {
            return;
        }
        if (!m.onGround && !isSubEnabled("air")) {
            return;
        }

        double limit = m.onGround ? groundLimit(data) : airLimit(data, m);
        limit += data.velocity.horizontal();
        if (isStrict()) {
            limit *= 0.92D;
        }

        data.speedLimits.addLast(limit);
        if (data.speedLimits.size() > 4) {
            data.speedLimits.removeFirst();
        }
        data.speedSamples.addLast(m.distanceXZ);
        if (data.speedSamples.size() > 4) {
            data.speedSamples.removeFirst();
        }
        if (data.speedSamples.size() == 4) {
            double sum = 0D;
            double limitSum = 0D;
            for (double s : data.speedSamples) sum += s;
            for (double l : data.speedLimits) limitSum += l;
            if (sum > limitSum + sd("min-avg-overage", 0.06D, 0.03D)) {
                if (bump(data, "speed", 1D, i("vl-before-flag", 8))) {
                    flag(data, "SpeedAvg", "sum4=" + MathUtil.round(sum, 3)
                            + " limit4=" + MathUtil.round(limitSum, 3));
                }
            } else {
                drain(data, "speed", 0.03D);
            }
        }

        double over = m.distanceXZ - limit;
        if (m.distanceXZ - m.lastDistanceXZ > sd("spike-grace", 0.25D, 0.12D)) {
            if (++data.speedSpikeTicks <= si("spike-grace-ticks", 3, 1)) {
                drain(data, "speed", 0.02D);
                return;
            }
        }
        data.speedSpikeTicks = 0;
        if (over > 0D) {
            if (over < sd("min-overage", 0.02D, 0.005D)) {
                drain(data, "speed", 0.02D);
            } else if (bump(data, "speed", 1D, i("vl-before-flag", 8))) {
                flag(data, "Speed", "xZ=" + MathUtil.round(m.distanceXZ, 3) + " max=" + MathUtil.round(limit, 3));
            }
        } else {
            drain(data, "speed", 0.05D);
        }
    }

    private double groundLimit(PlayerData data) {
        MovementTracker m = data.movement;
        double limit = d("ground.limit", 0.29D);
        if (m.groundTicks <= si("ground.landing-ticks", 5, 3)) {
            limit += sd("ground.landing-bonus", 0.1D, 0.06D);
        }
        limit += data.speedLevel * d("ground.speed-potion-multiplier", 0.05D);
        if (m.jumpedThisTick) {
            limit += 0.2D;
        }
        if (m.iceTicks > 0) {
            limit *= sd("ground.ice-multiplier", 1.25D, 1.15D);
        }
        if (m.slimeTicks > 0) {
            limit += Math.min(0.12D, 0.022D * Math.pow(1.0375D, m.slimeTicks));
        }
        if (m.boxedIn) {
            limit *= sd("ground.boxed-multiplier", 1.1D, 1.05D);
        }
        return limit;
    }

    private double airLimit(PlayerData data, MovementTracker m) {
        double momentum = sd("air.momentum", 0.36D, 0.34D)
                * Math.pow(sd("air.momentum-decay", 0.985D, 0.98D), m.airTicks + 1D);
        double limit = Math.max(d("air.minimum", 0.11D), momentum);
        double burst = burstLimit(data.speedLevel, m.airTicks);
        if (burst > limit) {
            limit = burst;
        }
        if (m.iceTicks > 0) {
            limit += Math.min(0.18D, 0.025D * Math.pow(1.038D, m.iceTicks));
        }
        if (m.slimeTicks > 0) {
            limit += Math.min(0.12D, 0.022D * Math.pow(1.0375D, m.slimeTicks));
        }
        limit += data.speedLevel * 0.05D + data.jumpLevel * 0.05D;
        if (m.boxedIn) {
            limit += 0.3D;
        }
        return limit;
    }

    private double burstLimit(int speedLevel, int airTicks) {
        double factor = (0.286D * (1D + d("air.burst-potion-factor", 0.2D) * speedLevel) + 0.2D) / 0.486D;
        double base;
        switch (airTicks) {
        case 1:
            base = 0.52D;
            break;
        case 2:
            base = 0.49D;
            break;
        case 3:
            base = 0.47D;
            break;
        case 4:
            base = 0.45D;
            break;
        case 5:
            base = 0.44D;
            break;
        default:
            base = 0.42D * Math.pow(0.91D, airTicks - 6D);
            break;
        }
        return base * factor * (isStrict() ? 0.96D : 1.0D);
    }
}