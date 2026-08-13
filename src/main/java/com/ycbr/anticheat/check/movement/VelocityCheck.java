package com.ycbr.anticheat.check.movement;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.VelocityState;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

public final class VelocityCheck extends Check {

    public VelocityCheck(AntiCheatManager manager) {
        super(CheckType.VELOCITY, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || !data.movement.initialized
                || data.ping > cfg.maxPing()) {
            return;
        }
        MovementTracker m = data.movement;
        if (m.nearLiquidTicks > 0 || m.inWebTicks > 0 || m.slimeTicks > 0 || m.iceTicks > 0
                || m.ladderTicks > 0 || m.boxedIn) {
            return;
        }

        VelocityState vs = data.velocity;
        if (!vs.pending()) {
            return;
        }
        if (!m.onGround) {
            vs.markAirborne();
        }

        int pingTicks = Math.min(8, Math.max(0, (int) Math.ceil(data.ping / 50.0D)));
        int t = vs.ticksSince() - pingTicks;
        if (t < 1) {
            return;
        }

        long kbDelayMs = System.currentTimeMillis() - vs.issuedAtMillis();
        int jumpWindow = si("jumpreset.window-ms", 40, 60);
        if (isSubEnabled("jumpreset") && vs.y() >= 0.3D && !data.kbJumpedThisKb
                && kbDelayMs <= jumpWindow && m.jumpedThisTick && m.groundTicks >= 2) {
            data.kbJumpedThisKb = true;
            if (++data.kbJumpResetStreak >= si("jumpreset.streak", 3, 2)) {
                data.kbJumpResetStreak = 0;
                if (bump(data, "jumpreset", 1D, i("jumpreset.vl-before-flag", 4))) {
                    flag(data, "JumpReset", "jump on kb tick delay=" + kbDelayMs + "ms groundTicks="
                            + m.groundTicks);
                }
            }
        } else if (vs.pending() && kbDelayMs > jumpWindow && !data.kbJumpedThisKb) {
            data.kbJumpResetStreak = 0;
        }

        double kbX = vs.x();
        double kbZ = vs.z();
        double kbY = vs.y();
        double expectedH = Math.sqrt(kbX * kbX + kbZ * kbZ) * Math.pow(0.91D, Math.min(20, t));
        boolean wall = blockedByWall(data, kbX, kbZ);
        boolean ceiling = blockedAbove(data);

        if (isSubEnabled("horizontal") && expectedH >= sd("horizontal.expected-min", 0.3D, 0.2D) && !wall && !ceiling
                && !m.jumpedThisTick && m.motionY >= -0.05D) {
            double ratio = m.distanceXZ / expectedH;
            if (cfg.raw().getBoolean("settings.debug-velocity", false)) {
                Bukkit.getConsoleSender().sendMessage("§8[YCBR-VEL] §7t=" + t + " ratio="
                        + MathUtil.round(ratio, 3) + " exp=" + MathUtil.round(expectedH, 3)
                        + " kbPre=" + MathUtil.round(data.kbPreSpeed, 3) + " ts="
                        + MathUtil.round(m.timeScale, 3));
            }
            double preciseMin = sd("horizontal.precise-expected-min", 0.3D, 0.2D);
            if (expectedH >= preciseMin && data.kbPreSpeed < d("horizontal.precise-pre-speed", 0.06D)
                    && m.timeScale >= 0.9D && m.timeScale <= 1.1D) {
                double bandMin = sd("horizontal.precise-band-min", 0.97D, 0.95D);
                double bandMax = sd("horizontal.precise-band-max", 1.2D, 1.3D);
                if (ratio >= bandMin && ratio <= bandMax) {
                    if (ratio < sd("horizontal.ratio-precise", 0.995D, 1.0D)) {
                        if (++data.kbPreciseTicks >= si("horizontal.precise-streak", 2, 1)) {
                            data.kbPreciseTicks = 0;
                            if (bump(data, "horizontal", 1D, i("horizontal.vl-before-flag", 2))) {
                                flag(data, "HorizontalPrecise", "KB reduced ratio="
                                        + MathUtil.round(ratio, 3) + " expected="
                                        + MathUtil.round(expectedH, 2));
                            }
                        }
                    } else {
                        data.kbPreciseTicks = 0;
                    }
                } else {
                    data.kbPreciseTicks = 0;
                }
            } else {
                data.kbPreciseTicks = 0;
            }
            double minRatio = m.onGround
                    ? sd("horizontal.ratio-ground", 0.15D, 0.2D)
                    : sd("horizontal.ratio-air", 0.25D, 0.35D);
            double partialRatio = sd("horizontal.ratio-partial", 0.5D, 0.6D);
            double partialMin = sd("horizontal.expected-min-partial", 0.6D, 0.45D);
            boolean reversed = false;
            if (expectedH >= partialMin) {
                double dx = m.lastX - m.lastLastX;
                double dz = m.lastZ - m.lastLastZ;
                double moveLen = Math.sqrt(dx * dx + dz * dz);
                double kbLen = Math.sqrt(kbX * kbX + kbZ * kbZ);
                if (moveLen > 0.05D && kbLen > 0.05D) {
                    reversed = (dx * kbX + dz * kbZ) / (moveLen * kbLen)
                            < sd("horizontal.direction-dot", -0.25D, -0.1D);
                }
            }
            if (ratio < minRatio) {
                data.kbHPartialTicks = 0;
                data.kbDirectionTicks = 0;
                if (++data.kbHLowTicks >= (isStrict() ? 1 : 2)) {
                    data.kbHLowTicks = 0;
                    if (bump(data, "horizontal", 1D, i("horizontal.vl-before-flag", 2))) {
                        flag(data, "Horizontal", "KB canceled ratio=" + MathUtil.round(ratio, 2)
                                + " expected=" + MathUtil.round(expectedH, 2));
                    }
                }
            } else if (ratio < partialRatio && expectedH >= partialMin) {
                data.kbHLowTicks = 0;
                data.kbDirectionTicks = 0;
                if (++data.kbHPartialTicks >= si("horizontal.partial-streak", 3, 2)) {
                    data.kbHPartialTicks = 0;
                    if (bump(data, "horizontal", 1D, i("horizontal.vl-before-flag", 2))) {
                        flag(data, "HorizontalPartial", "KB reduced ratio=" + MathUtil.round(ratio, 2)
                                + " expected=" + MathUtil.round(expectedH, 2));
                    }
                }
            } else if (reversed) {
                data.kbHLowTicks = 0;
                data.kbHPartialTicks = 0;
                if (++data.kbDirectionTicks >= si("horizontal.reversed-streak", 3, 2)) {
                    data.kbDirectionTicks = 0;
                    if (bump(data, "horizontal", 1D, i("horizontal.vl-before-flag", 2))) {
                        flag(data, "HorizontalReversed", "moved against KB expected="
                                + MathUtil.round(expectedH, 2));
                    }
                }
            } else {
                data.kbHLowTicks = 0;
                data.kbHPartialTicks = 0;
                data.kbDirectionTicks = 0;
                drain(data, "horizontal", 0.1D);
            }
        } else {
            data.kbHLowTicks = 0;
            data.kbHPartialTicks = 0;
            data.kbDirectionTicks = 0;
        }

        if (isSubEnabled("vertical") && kbY >= 0.05D) {
            double expectedV = vs.verticalAt(t);
            double minExpected = sd("vertical.expected-min", 0.25D, 0.2D);
            if (expectedV >= minExpected) {
                if (m.onGround && !vs.airborneSeen() && t >= 3 && !ceiling) {
                    if (++data.kbNoRiseTicks >= (isStrict() ? 1 : 2)) {
                        data.kbNoRiseTicks = 0;
                        if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
                            flag(data, "Vertical", "KB fully absorbed t=" + t);
                        }
                    }
                } else if (!m.onGround && !ceiling && !m.jumpedThisTick) {
                    double pct = m.motionY / expectedV;
                    double minimum = sd("vertical.minimum-percentage", 45D, 60D) / 100D;
                    if (m.motionY > 0.01D) {
                        boolean fallingBeforeKb = m.lastMotionY < -0.05D;
                        if (pct >= minimum) {
                            data.fallKbMissTicks = 0;
                            data.kbNoRiseTicks = 0;
                            drain(data, "vertical", 0.1D);
                        } else if (m.motionY >= 0.05D || !fallingBeforeKb) {
                            if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
                                flag(data, "Vertical", "KB insufficient pct="
                                        + MathUtil.round(pct * 100D, 1) + " expected="
                                        + MathUtil.round(expectedV, 3));
                            }
                        } else if (++data.fallKbMissTicks >= (isStrict() ? 2 : 3)) {
                            data.fallKbMissTicks = 0;
                            if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
                                flag(data, "Vertical", "KB canceled while falling pct="
                                        + MathUtil.round(pct * 100D, 1) + " expected="
                                        + MathUtil.round(expectedV, 3));
                            }
                        } else {
                            data.fallKbMissTicks = 0;
                        }
                    } else if (++data.fallKbMissTicks >= (isStrict() ? 2 : 3)) {
                        data.fallKbMissTicks = 0;
                        if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
                            flag(data, "Vertical", "KB canceled airborne motionY="
                                    + MathUtil.round(m.motionY, 3) + " expected="
                                    + MathUtil.round(expectedV, 3));
                        }
                    }
                } else {
                    data.kbNoRiseTicks = 0;
                    data.fallKbMissTicks = 0;
                }
            }
        }

        if (vs.ticksSince() > 12 + pingTicks) {
            vs.expire();
        }
    }

    public void onKbIssued(PlayerData data) {
        data.kbJumpedThisKb = false;
        data.kbSprintResetCounted = false;
    }

    public void checkSprintReset(PlayerData data, long now) {
        if (!isEnabled() || !isSubEnabled("sprintreset") || data.creative) {
            return;
        }
        if (!data.velocity.pending() || data.kbSprintResetCounted) {
            return;
        }
        long issue = data.velocity.issuedAtMillis();
        if (now - issue > 250L) {
            return;
        }
        long stop = data.lastSprintStopTime;
        long lead = issue - stop;
        if (stop > 0L && lead >= si("sprintreset.min-lead-ms", 60, 40)
                && lead <= si("sprintreset.max-lead-ms", 150, 200)) {
            data.kbSprintResetCounted = true;
            if (++data.kbSprintResetStreak >= si("sprintreset.streak", 3, 2)) {
                data.kbSprintResetStreak = 0;
                if (bump(data, "sprintreset", 1D, i("sprintreset.vl-before-flag", 4))) {
                    flag(data, "SprintReset", "stop-to-kb=" + lead + "ms");
                }
            }
        }
    }

    private boolean blockedByWall(PlayerData data, double kbX, double kbZ) {
        double len = Math.sqrt(kbX * kbX + kbZ * kbZ);
        if (len < 1.0E-4D) {
            return false;
        }
        double nx = kbX / len;
        double nz = kbZ / len;
        double x = data.movement.lastX;
        double y = data.movement.lastY;
        double z = data.movement.lastZ;
        int baseY = (int) Math.floor(y);
        for (int i = 1; i <= 3; i++) {
            int bx = (int) Math.floor(x + nx * i);
            int bz = (int) Math.floor(z + nz * i);
            for (int dy = 0; dy <= 2; dy++) {
                if (isSolid(data, bx, baseY + dy, bz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean blockedAbove(PlayerData data) {
        int bx = (int) Math.floor(data.movement.lastX);
        int by = (int) Math.floor(data.movement.lastY);
        int bz = (int) Math.floor(data.movement.lastZ);
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (isSolid(data, bx + dx, by + dy, bz + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSolid(PlayerData data, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return false;
        }
        try {
            Block block = Bukkit.getPlayer(data.getUuid()).getWorld().getBlockAt(x, y, z);
            return block.getType().isSolid();
        } catch (Exception e) {
            return false;
        }
    }
}
