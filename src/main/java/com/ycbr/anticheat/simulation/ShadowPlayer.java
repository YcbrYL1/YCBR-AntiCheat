package com.ycbr.anticheat.simulation;

/**
 * Per-player shadow state for simulation-based anti-cheat.
 *
 * <p>状态约定（与 {@link PredictionEngine} 一致）：
 * <ul>
 *   <li>motionX/Z = 上一帧的水平位置增量（客户端上报的实际位移，即"携带动量"）；</li>
 *   <li>motionY = 垂直携带速度（上一帧重力拖拽后的值）；</li>
 *   <li>onGround = 服务器判定（不信任客户端 onGround 标志）。</li>
 * </ul>
 * 必须在以下时机重同步：传送、重生、换世界、登入、击退注入、回城/床，以及高 VL flag 后。
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
     * 全量重同步。旧签名：以客户端 onGround 作为地面判定（不推荐）。
     */
    public void sync(double x, double y, double z, double motX, double motY, double motZ,
                     boolean onGround, float yaw, long time) {
        sync(x, y, z, motX, motY, motZ, onGround, onGround, yaw, time);
    }

    /**
     * 全量重同步，只信任服务器 onGround 判定。
     *
     * @param clientOnGround 移动包里的 onGround（不可信）
     * @param serverOnGround 服务器/物理判定（权威）
     */
    public void sync(double x, double y, double z, double motX, double motY, double motZ,
                     boolean clientOnGround, boolean serverOnGround, float yaw, long time) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.motionX = motX;
        this.motionY = motY;
        this.motionZ = motZ;
        this.onGround = serverOnGround; // 只信服务器判定
        this.yaw = yaw;
        this.lastSyncTime = time;
    }

    /**
     * 由位置增量做最小重同步（无显式速度时用上一帧状态推导）。
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
     * 击退/爆炸速度注入（叠加到当前动量）。
     */
    public void injectVelocity(double velX, double velY, double velZ) {
        this.motionX += velX;
        this.motionY += velY;
        this.motionZ += velZ;
    }

    /**
     * 按预测引擎推进一个 tick。水平取引擎位置增量（携带=增量），
     * 垂直按 NMS 顺序推进状态（重力/拖拽/液体/蛛网/梯子）。
     */
    public void tick(float frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
                     double speedLevel, double jumpLevel, double potionLevel) {
        tick(frictionFactor, sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel,
                false, false, false, false, false);
    }

    /**
     * 按预测引擎推进一个 tick（含世界状态，不使用物品）。
     */
    public void tick(float frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
                     double speedLevel, double jumpLevel, double potionLevel,
                     boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
        tick(frictionFactor, sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel,
                inLiquid, inWeb, onLadder, headBlocked, false);
    }

    /**
     * 按预测引擎推进一个 tick（含世界状态）。
     *
     * @param usingItem 使用物品（水平 × 0.2）
     */
    public void tick(float frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
                     double speedLevel, double jumpLevel, double potionLevel,
                     boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked,
                     boolean usingItem) {
        boolean jump = jumping && onGround && !inLiquid && !inWeb && !onLadder;
        double carriedY = this.motionY;

        PredictionEngine.Result r = PredictionEngine.predictSingle(
                motionX, carriedY, motionZ, onGround, yaw, frictionFactor,
                sprinting, jump, sneaking, speedLevel, jumpLevel,
                inLiquid, inWeb, onLadder, headBlocked, usingItem);
        // 水平：增量即新状态（B 约定）
        this.motionX = r.deltaX;
        this.motionZ = r.deltaZ;

        // 垂直状态推进（NMS 顺序）
        double motY;
        if (onLadder) {
            motY = PredictionEngine.LADDER_CLIMB;
        } else if (jump) {
            motY = PredictionEngine.JUMP_VELOCITY + jumpLevel * PhysicsConstants.JUMP_POTION_PER_LEVEL;
            if (headBlocked) {
                motY = Math.min(motY, PredictionEngine.HEAD_BLOCKED_JUMP_CAP);
            }
            motY = (motY - PredictionEngine.GRAVITY) * PredictionEngine.VERTICAL_DRAG;
        } else if (inWeb) {
            motY = (carriedY * PredictionEngine.WEB_DAMP - PredictionEngine.GRAVITY)
                    * PredictionEngine.VERTICAL_DRAG;
        } else if (inLiquid) {
            double base = carriedY + (jumping ? PredictionEngine.LIQUID_SWIM_UP : 0.0);
            motY = base * PredictionEngine.LIQUID_DRAG - PredictionEngine.LIQUID_GRAVITY;
        } else if (onGround) {
            motY = 0.0; // 地板碰撞吸收重力
        } else {
            motY = (carriedY - PredictionEngine.GRAVITY) * PredictionEngine.VERTICAL_DRAG;
        }
        this.motionY = motY;
        this.onGround = false; // 将被下一次服务器判定重同步
    }

    /**
     * 重置到原点（登入/重生）。
     */
    public void reset() {
        reset(0, 0, 0);
    }

    /**
     * 重置到指定位置（传送/登入）。
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
