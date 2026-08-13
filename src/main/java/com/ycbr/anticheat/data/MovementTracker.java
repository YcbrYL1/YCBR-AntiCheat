package com.ycbr.anticheat.data;

public final class MovementTracker {

    private static final double GROUND_EPSILON = 0.001D;
    private static final int ICE_TICKS = 60;
    private static final int SLIME_TICKS = 40;
    private static final int LIQUID_TICKS = 8;
    private static final int WEB_TICKS = 60;
    private static final int LADDER_TICKS = 6;

    public volatile double lastX;
    public volatile double lastY;
    public volatile double lastLastY;
    public volatile double lastZ;
    public volatile double lastLastZ;
    public volatile double lastLastX;
    public boolean initialized;
    private long lastTickTime;
    public volatile long lastMoveTime;

    public volatile double lastMoveX;
    public volatile double lastMoveY;
    public volatile double lastMoveZ;

    public double motionY;
    public double lastMotionY;
    public double distanceXZ;
    public double lastDistanceXZ;
    public double timeScale;
    public boolean onGround;
    public boolean sprinting;
    public boolean jumpedThisTick;
    public boolean airborne;
    public int airTicks;
    public int groundTicks;

    public volatile int iceTicks;
    public volatile int slimeTicks;
    public volatile int nearLiquidTicks;
    public volatile int inWebTicks;
    public volatile int ladderTicks;
    public volatile boolean boxedIn;

    public boolean handle(double x, double y, double z, boolean onIce, boolean onSlime, boolean nearLiquid,
            boolean boxedIn, boolean inWeb, boolean onLadder) {
        if (initialized
                && (Math.abs(x - lastX) > 3.0D || Math.abs(z - lastZ) > 3.0D || Math.abs(y - lastY) > 2.5D)) {
            lastMoveX = Math.abs(x - lastX);
            lastMoveY = Math.abs(y - lastY);
            lastMoveZ = Math.abs(z - lastZ);
            lastX = x;
            lastY = y;
            lastLastY = y;
            lastZ = z;
            lastLastZ = z;
            lastLastX = x;
            lastTickTime = System.currentTimeMillis();
            lastMoveTime = lastTickTime;
            initialized = false;
            return true;
        }
        if (!initialized) {
            lastMoveX = 0D;
            lastMoveY = 0D;
            lastMoveZ = 0D;
            lastX = x;
            lastY = y;
            lastLastY = y;
            lastZ = z;
            lastLastZ = z;
            lastLastX = x;
            lastTickTime = System.currentTimeMillis();
            lastMoveTime = lastTickTime;
            initialized = true;
            return false;
        }

        this.boxedIn = boxedIn;
        if (onIce) {
            iceTicks = ICE_TICKS;
        } else if (iceTicks > 0) {
            iceTicks--;
        }
        if (onSlime) {
            slimeTicks = SLIME_TICKS;
        } else if (slimeTicks > 0) {
            slimeTicks--;
        }
        if (nearLiquid) {
            nearLiquidTicks = LIQUID_TICKS;
        } else if (nearLiquidTicks > 0) {
            nearLiquidTicks--;
        }
        if (inWeb) {
            inWebTicks = WEB_TICKS;
        } else if (inWebTicks > 0) {
            inWebTicks--;
        }
        if (onLadder) {
            ladderTicks = LADDER_TICKS;
        } else if (ladderTicks > 0) {
            ladderTicks--;
        }

        lastMotionY = motionY;
        long now = System.currentTimeMillis();
        long elapsed = lastTickTime == 0L ? 50L : now - lastTickTime;
        lastTickTime = now;
        lastMoveTime = now;
        if (elapsed < 50L) {
            elapsed = 50L;
        } else if (elapsed > 250L) {
            elapsed = 250L;
        }
        double timeScale = 50.0D / elapsed;
        this.timeScale = timeScale;
        motionY = (y - lastY) * timeScale;
        lastDistanceXZ = distanceXZ;
        double dx = x - lastX;
        double dz = z - lastZ;
        distanceXZ = Math.sqrt(dx * dx + dz * dz) * timeScale;

        onGround = Math.abs(motionY) < GROUND_EPSILON;
        jumpedThisTick = motionY > 0.3D && lastMotionY < 0.3D;

        if (onGround) {
            groundTicks++;
            airTicks = 0;
            airborne = false;
        } else {
            airTicks++;
            groundTicks = 0;
            airborne = true;
        }

        lastMoveX = Math.abs(dx);
        lastMoveY = Math.abs(y - lastY);
        lastMoveZ = Math.abs(dz);
        lastLastX = lastX;
        lastX = x;
        lastLastY = lastY;
        lastY = y;
        lastLastZ = lastZ;
        lastZ = z;
        return false;
    }
}