package com.ycbr.anticheat.simulation;

/**
 * Per-player shadow state for simulation-based anti-cheat.
 * Maintains a copy of the player's motion state as the server last knew it,
 * used by PredictionEngine to generate expected position candidates.
 *
 * Must be resynced on: teleport, respawn, world change, join, velocity injection,
 * bed/respawn, and after any high-VL flag.
 */
public final class ShadowPlayer {

    public double motionX;
    public double motionY;
    public double motionZ;
    public boolean onGround;
    public float yaw;
    public double posX;
    public double posY;
    public double posZ;
    public long lastSyncTime;

    public ShadowPlayer() {
        reset();
    }

    /**
     * Full resync from server-side player state.
     */
    public void sync(double x, double y, double z, double motX, double motY, double motZ,
                     boolean onGround, float yaw, long time) {
        sync(x, y, z, motX, motY, motZ, onGround, onGround, yaw, time);
    }

    /**
     * Full resync from server-side player state, trusting server onGround.
     *
     * @param clientOnGround onGround flag from the movement packet (untrusted)
     * @param serverOnGround server-side ground determination (authoritative)
     */
    public void sync(double x, double y, double z, double motX, double motY, double motZ,
                     boolean clientOnGround, boolean serverOnGround, float yaw, long time) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.motionX = motX;
        this.motionY = motY;
        this.motionZ = motZ;
        this.onGround = serverOnGround; // trust server determination only
        this.yaw = yaw;
        this.lastSyncTime = time;
    }

    /**
     * Minimal resync from position delta (for packet-based updates where
     * motion isn't directly available; derive from last known state).
     */
    public void resyncPosition(double x, double y, double z, boolean onGround, float yaw, long time) {
        this.motionX = x - this.posX;
        this.motionY = y - this.posY;
        this.motionZ = z - this.posZ;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.onGround = onGround;
        this.yaw = yaw;
        this.lastSyncTime = time;
    }

    /**
     * Apply velocity injection (e.g. from knockback / explosion).
     */
    public void injectVelocity(double velX, double velY, double velZ) {
        this.motionX += velX;
        this.motionY += velY;
        this.motionZ += velZ;
    }

    /**
     * Advance shadow state by one predicted tick using PredictionEngine physics.
     * Call after each packet prediction to keep shadow in sync with expected state.
     */
    public void tick(float frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
                     double speedLevel, double jumpLevel, double potionLevel) {
        tick(frictionFactor, sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel,
                false, false, false, false);
    }

    /**
     * Advance shadow state with world-state modifiers.
     * Horizontal motion from PredictionEngine; vertical motion keeps shadow's
     * own motionY (gravity/drag applied here).
     */
    public void tick(float frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
                     double speedLevel, double jumpLevel, double potionLevel,
                     boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
        boolean jump = jumping && onGround;
        PredictionEngine.Result r = PredictionEngine.predictSingle(
                motionX, motionZ, onGround, yaw, frictionFactor,
                sprinting, jump, sneaking, speedLevel, jumpLevel, potionLevel,
                inLiquid, inWeb, onLadder, headBlocked);
        this.motionX = r.deltaX;
        this.motionZ = r.deltaZ;

        double motY = this.motionY;
        if (jump) {
            motY = PredictionEngine.JUMP_VELOCITY + jumpLevel * 0.1;
            if (headBlocked) {
                motY = Math.min(motY, 0.3);
            }
        }
        if (inWeb) {
            motY *= 0.105;
        } else if (inLiquid) {
            motY = (motY - 0.02) * 0.8;
        } else if (onLadder) {
            motY = 0.15;
        } else {
            motY = (motY - PredictionEngine.GRAVITY) * PredictionEngine.VERTICAL_DRAG;
        }
        this.motionY = motY;
        this.onGround = false; // will be resynced from actual server state
    }

    /**
     * Reset to origin (used on join / respawn).
     */
    public void reset() {
        reset(0, 0, 0);
    }

    /**
     * Reset to specific position (used on teleport / join).
     */
    public void reset(double x, double y, double z) {
        this.motionX = 0;
        this.motionY = 0;
        this.motionZ = 0;
        this.onGround = true;
        this.yaw = 0;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.lastSyncTime = System.currentTimeMillis();
    }
}
