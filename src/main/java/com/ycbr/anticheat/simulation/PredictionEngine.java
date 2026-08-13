package com.ycbr.anticheat.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java 1.8.8 physics prediction engine (no NMS/Bukkit dependencies).
 * Formulas sourced from patched_1.8.8.jar v1_8_R3:
 *   EntityLiving.g() / Entity.a() / Entity.move() / EntityHuman.initAttributes
 */
public final class PredictionEngine {

    private PredictionEngine() {}

    public static final double BASE_SPEED = 0.1;
    public static final double AIR_ACCEL = 0.02;
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double NORMAL_FRICTION = 0.546;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double ACCEL_FACTOR = 0.16277136;
    public static final double SPRINT_MODIFIER = 1.3;
    public static final double SPRINT_JUMP_IMPULSE = 0.2;
    public static final double AIR_FRICTION = 0.91;

    public static final class Result {
        public final double deltaX;
        public final double deltaZ;
        public final double motionY;
        public final boolean onGround;

        public Result(double deltaX, double deltaZ, double motionY, boolean onGround) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
            this.motionY = motionY;
            this.onGround = onGround;
        }
    }

    public static final class Candidate {
        public final double deltaX;
        public final double deltaZ;
        public final double motionY;
        public final String label;

        public Candidate(double deltaX, double deltaZ, double motionY, String label) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
            this.motionY = motionY;
            this.label = label;
        }
    }

    /**
     * Single-tick prediction.
     */
    public static Result predictSingle(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel, double potionLevel) {
        return predictSingle(motionX, motionZ, onGround, yaw, frictionFactor,
                sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel,
                false, false, false, false);
    }

    /**
     * Single-tick prediction with world-state modifiers.
     *
     * @param inLiquid     water/lava: reduced friction 0.8, gravity 0.02, vertical drag 0.8
     * @param inWeb        cobweb: motion scaled by 0.105 after input
     * @param onLadder     ladder/vine: can climb, motY capped at 0.15
     * @param headBlocked  ceiling collision: jump velocity capped at 0.3
     */
    public static Result predictSingle(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel, double potionLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {

        double motX = motionX;
        double motZ = motionZ;
        double motY = 0.0;

        double gravity = GRAVITY;
        double vDrag = VERTICAL_DRAG;
        double hFriction = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;

        if (inWeb) {
            hFriction = 0.6;
            gravity = 0.02;
        } else if (inLiquid) {
            gravity = 0.02;
            vDrag = 0.8;
            hFriction = 0.8;
            if (jumping) {
                motY = 0.08;
            }
        }

        if (jumping && onGround && !inLiquid) {
            motY = JUMP_VELOCITY + jumpLevel * 0.1;
            if (headBlocked) {
                motY = Math.min(motY, 0.3);
            }
            if (sprinting) {
                double rad = yaw * Math.PI / 180.0;
                motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
            }
        }

        double f5 = hFriction;
        double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

        double baseSpeed = BASE_SPEED;
        if (sprinting) baseSpeed *= SPRINT_MODIFIER;
        double effectivePotion = Math.max(speedLevel, potionLevel);
        if (effectivePotion > 0) baseSpeed *= 1.0 + 0.2 * effectivePotion;

        double inputSpeed;
        if (inLiquid) {
            inputSpeed = baseSpeed * 0.4;
        } else {
            inputSpeed = onGround ? baseSpeed * f6 : AIR_ACCEL;
        }

        double inputFactor = sneaking ? 0.3 : 1.0;
        inputSpeed *= inputFactor;

        double fwd = 1.0;
        double strafe = 0.0;
        double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
        if (f3 >= 1e-4) {
            if (f3 < 1.0) f3 = 1.0;
            f3 = inputSpeed / f3;
            double sinYaw = Math.sin(yaw * Math.PI / 180.0);
            double cosYaw = Math.cos(yaw * Math.PI / 180.0);
            motX += (fwd * f3) * cosYaw - (strafe * f3) * sinYaw;
            motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;
        }

        // cobweb damps motion after input is applied
        if (inWeb) {
            motX *= 0.105;
            motY *= 0.105;
            motZ *= 0.105;
        }

        if (onLadder) {
            // climb: climb speed 0.15 when holding W, ignore gravity
            motY = 0.15;
            gravity = 0.0;
        } else if (jumping && onGround && !inLiquid) {
            // motY already set above
        } else if (onGround) {
            motY = 0.0;
        }
        motY -= gravity;
        motY *= vDrag;
        motX *= f5;
        motZ *= f5;

        return new Result(motX, motZ, motY, onGround);
    }

    /**
     * Generate candidate predictions for all input combinations:
     * {walk, sprint, sneak} x {no-jump, jump}
     */
    public static Candidate[] candidates(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel) {
        return candidates(motionX, motionZ, onGround, yaw, frictionFactor,
                sprinting, speedLevel, jumpLevel, false, false, false, false);
    }

    /**
     * Candidate generation with world-state modifiers.
     */
    public static Candidate[] candidates(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {

        List<Candidate> list = new ArrayList<Candidate>();
        double[] speedFactors = {1.0, SPRINT_MODIFIER, 0.3};
        String[] speedLabels = {"walk", "sprint", "sneak"};
        boolean[] jumpFlags = {false, true};

        for (int s = 0; s < speedFactors.length; s++) {
            for (int j = 0; j < jumpFlags.length; j++) {
                boolean isJump = jumpFlags[j] && onGround;
                boolean effectiveSprint = sprinting && speedFactors[s] == SPRINT_MODIFIER;

                double motX = motionX;
                double motZ = motionZ;
                double motY = 0.0;

                double gravity = GRAVITY;
                double vDrag = VERTICAL_DRAG;
                double hFriction = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;

                if (inWeb) {
                    hFriction = 0.6;
                    gravity = 0.02;
                } else if (inLiquid) {
                    gravity = 0.02;
                    vDrag = 0.8;
                    hFriction = 0.8;
                }

                if (isJump && !inLiquid) {
                    motY = JUMP_VELOCITY + jumpLevel * 0.1;
                    if (headBlocked) {
                        motY = Math.min(motY, 0.3);
                    }
                    if (effectiveSprint) {
                        double rad = yaw * Math.PI / 180.0;
                        motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                        motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                    }
                }

                double f5 = hFriction;
                double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

                double baseSpeed = BASE_SPEED;
                if (sprinting) baseSpeed *= SPRINT_MODIFIER;
                if (speedLevel > 0) baseSpeed *= 1.0 + 0.2 * speedLevel;

                double inputSpeed;
                if (inLiquid) {
                    inputSpeed = baseSpeed * 0.4;
                } else {
                    inputSpeed = onGround ? baseSpeed * f6 : AIR_ACCEL;
                }
                inputSpeed *= speedFactors[s];

                double fwd = 1.0;
                double strafe = 0.0;
                double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
                if (f3 < 1e-4) continue;
                if (f3 < 1.0) f3 = 1.0;
                f3 = inputSpeed / f3;
                double sinYaw = Math.sin(yaw * Math.PI / 180.0);
                double cosYaw = Math.cos(yaw * Math.PI / 180.0);
                motX += (fwd * f3) * cosYaw - (strafe * f3) * sinYaw;
                motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;

                // cobweb damps motion after input is applied
                if (inWeb) {
                    motX *= 0.105;
                    motY *= 0.105;
                    motZ *= 0.105;
                }

                if (onLadder) {
                    motY = 0.15;
                    gravity = 0.0;
                } else if (isJump && !inLiquid) {
                    // motY already set above
                } else if (onGround) {
                    motY = 0.0;
                }
                motY -= gravity;
                motY *= vDrag;
                motX *= f5;
                motZ *= f5;

                list.add(new Candidate(motX, motZ, motY, speedLabels[s] + (isJump ? "+jump" : "")));
            }
        }

        return list.toArray(new Candidate[0]);
    }

    /**
     * Multi-tick candidate prediction (for high-ping: one packet = multiple server ticks).
     * Simulates tick-by-track, returns accumulated delta after all ticks.
     */
    public static Candidate[] candidatesMultiTick(
            double motionX, double motionZ, double motionY,
            boolean onGround, float yaw, double frictionFactor,
            boolean sprinting, double speedLevel, double jumpLevel, int ticks) {

        if (ticks <= 1) {
            return candidates(motionX, motionZ, onGround, yaw, frictionFactor, sprinting, speedLevel, jumpLevel);
        }

        List<Candidate> list = new ArrayList<Candidate>();
        double[] speedFactors = {1.0, SPRINT_MODIFIER, 0.3};
        String[] speedLabels = {"walk", "sprint", "sneak"};

        for (int s = 0; s < speedFactors.length; s++) {
            for (int jumpAttempt = 0; jumpAttempt <= 1; jumpAttempt++) {
                boolean jumpOnTick0 = (jumpAttempt == 1) && onGround;

                double motX = motionX;
                double motZ = motionZ;
                double motY = motionY;
                double totalDX = 0.0;
                double totalDZ = 0.0;
                double totalDY = 0.0;
                boolean ground = onGround;

                for (int t = 0; t < ticks; t++) {
                    if (t == 0 && jumpOnTick0) {
                        motY = JUMP_VELOCITY + jumpLevel * 0.1;
                        double rad = yaw * Math.PI / 180.0;
                        motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                        motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                        ground = false;
                    }

                    double f5 = ground ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
                    double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

                    double baseSpeed = BASE_SPEED;
                    if (sprinting) baseSpeed *= SPRINT_MODIFIER;
                    if (speedLevel > 0) baseSpeed *= 1.0 + 0.2 * speedLevel;

                    double inputSpeed = ground ? baseSpeed * f6 : AIR_ACCEL;
                    inputSpeed *= speedFactors[s];

                    double fwd = 1.0;
                    double f3 = Math.max(1.0, Math.sqrt(fwd * fwd));
                    f3 = inputSpeed / f3;
                    double sinYaw = Math.sin(yaw * Math.PI / 180.0);
                    double cosYaw = Math.cos(yaw * Math.PI / 180.0);
                    motX += fwd * f3 * cosYaw;
                    motZ += fwd * f3 * sinYaw;

                    totalDX += motX;
                    totalDZ += motZ;
                    totalDY += motY;

                    if (t == 0 && jumpOnTick0) {
                        // motY already set above
                    } else if (ground) {
                        motY = 0.0;
                    }
                    motY -= GRAVITY;
                    motY *= VERTICAL_DRAG;
                    motX *= f5;
                    motZ *= f5;
                    ground = false;
                }

                list.add(new Candidate(totalDX, totalDZ, totalDY,
                    speedLabels[s] + (jumpOnTick0 ? "+jump" : "") + "x" + ticks));
            }
        }

        return list.toArray(new Candidate[0]);
    }
}
