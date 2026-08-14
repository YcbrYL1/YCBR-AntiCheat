package com.ycbr.anticheat.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java 1.8.8 physics prediction engine (no NMS/Bukkit dependencies).
 *
 * <p>公式严格转写自 patched_1.8.8.jar (v1_8_R3，与 1.8.9 完全一致)：
 * <pre>
 *   EntityLiving.g()            每 tick 移动主流程
 *   Entity.a(strafe,fwd,friction)  输入施加（方向向量 -> motX/motZ）
 *   Entity.move()               位置移动 + 蜘蛛网阻尼
 *   EntityLiving.bF()           跳跃（motY=0.42 + 跳跃药水 + 疾跑冲量）
 *   EntityHuman.initAttributes  移动速度属性（generic.movementSpeed = 0.1）
 * </pre>
 * 关键语义（务必保持）：
 * <ul>
 *   <li><b>水平状态约定</b>：调用方传入/回写的 motionX/Z 是"上一帧的位置增量"（客户端上报
 *       的实际位移）。因此每 tick 先对携带动量施加摩擦（delta = carried*f5 + 输入），
 *       返回的 deltaX/Z 即"该 tick 客户端应上报的位置增量"，可直接与客户端增量比较。</li>
 *   <li><b>垂直状态约定</b>：motionY 是"携带速度"（上一帧重力拖拽后的值）。空中不跳时
 *       delta_Y = 携带速度；重力/拖拽在 ShadowPlayer 的下一帧状态更新中体现。</li>
 *   <li>速度属性：base = (0.1 + 0.2*速度等级) × (疾跑 ? 1.3 : 1)（NMS 操作码 0 加算药水、
 *       操作码 2 百分比乘算疾跑）。</li>
 *   <li>水中垂直：NMS 顺序 motY*=0.8 然后 motY-=0.02（先乘后减）。</li>
 *   <li>蜘蛛网：Entity.move 内 motX/Y/Z *= 0.105（输入后、摩擦前）。</li>
 * </ul>
 */
public final class PredictionEngine {

    private PredictionEngine() {}

    /** generic.movementSpeed 基础值（EntityHuman.initAttributes:272） */
    public static final double BASE_SPEED = 0.1;
    /** 空中加速度 aM（EntityLiving 字段，默认 0.02F） */
    public static final double AIR_ACCEL = 0.02;
    /** 重力（EntityLiving.g: motY -= 0.08） */
    public static final double GRAVITY = 0.08;
    /** 垂直拖拽（motY *= 0.98） */
    public static final double VERTICAL_DRAG = 0.98;
    /** 空气/水平摩擦基数（onGround ? slipperiness*0.91 : 0.91） */
    public static final double AIR_FRICTION = 0.91;
    /** 地面加速度换算常量（0.16277136 / f5^3） */
    public static final double ACCEL_FACTOR = 0.16277136;
    /** 跳跃初速度（EntityLiving.bF: motY = 0.42） */
    public static final double JUMP_VELOCITY = 0.42;
    /** 疾跑速度修饰（操作码 2，+30%） */
    public static final double SPRINT_MODIFIER = 1.3;
    /** 疾跑跳跃水平冲量（bF: motX -= sin*0.2; motZ += cos*0.2） */
    public static final double SPRINT_JUMP_IMPULSE = 0.2;
    /** 潜行减速因子（客户端施加） */
    public static final double SNEAK_FACTOR = 0.3;
    /** 使用物品减速（NMS 1.8 EntityHuman: 使用物品时 motX/Z *= 0.2） */
    public static final double USING_ITEM_FACTOR = 0.2;
    /** 速度药水每级加算（NMS 操作码 0，+0.2/级） */
    public static final double SPEED_POTION_PER_LEVEL = 0.2;
    /** 水中水平摩擦/拖拽（NMS 水分支 motX/Z *= 0.8） */
    public static final double LIQUID_DRAG = 0.8;
    /** 水中垂直拖拽后减量（NMS 水分支 motY *= 0.8 后 motY -= 0.02） */
    public static final double LIQUID_GRAVITY = 0.02;
    /** 水中输入加速度系数（NMS 水分支 f5 = bI()*0.02；贴地疾跑 *0.1） */
    public static final double LIQUID_INPUT_FACTOR = 0.02;
    /** 水中上浮加速（按住跳跃键 motY += 0.04） */
    public static final double LIQUID_SWIM_UP = 0.04;
    /** 蜘蛛网阻尼（Entity.move: *= 0.105） */
    public static final double WEB_DAMP = 0.105;
    /** 梯子爬升速度（EntityLiving 梯子分支 motY = 0.15） */
    public static final double LADDER_CLIMB = 0.15;
    /** 头顶被挡时跳跃上限（简化碰撞：跳不起高） */
    public static final double HEAD_BLOCKED_JUMP_CAP = 0.3;

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

    // ------------------------------------------------------------------
    // predictSingle 重载
    // ------------------------------------------------------------------

    /** 兼容旧签名：motionY=0、无世界状态、不使用物品。 */
    public static Result predictSingle(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel, double potionLevel) {
        return predictSingle(motionX, 0.0, motionZ, onGround, yaw, frictionFactor,
                sprinting, jumping, sneaking, speedLevel, jumpLevel,
                false, false, false, false, false);
    }

    /** 兼容旧签名：motionY=0、不使用物品。 */
    public static Result predictSingle(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel, double potionLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
        return predictSingle(motionX, 0.0, motionZ, onGround, yaw, frictionFactor,
                sprinting, jumping, sneaking, speedLevel, jumpLevel,
                inLiquid, inWeb, onLadder, headBlocked, false);
    }

    /**
     * 完整单 tick 预测。
     *
     * @param motionX 水平携带动量（上一帧位置增量）
     * @param motionY 垂直携带速度
     * @param motionZ 水平携带动量
     * @param frictionFactor 脚下方块 slipperiness（0.6 普通 / 0.98 冰 / 0.8 史莱姆 / 0.4 灵魂沙）
     * @param jumping  本 tick 按下跳跃键（仅 onGround 生效）
     * @param usingItem 使用物品（吃东西/喝药/拉弓），水平位移 × 0.2
     * @return 该 tick 的期望位置增量（水平=携带*摩擦+输入；垂直=携带速度）
     */
    public static Result predictSingle(
            double motionX, double motionY, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked,
            boolean usingItem) {

        double hFriction = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
        if (inLiquid) {
            hFriction = LIQUID_DRAG;
        }

        // 水平：先对携带动量施加摩擦（状态约定：携带=上一帧位置增量）
        double motX = motionX * hFriction;
        double motZ = motionZ * hFriction;
        double motY = motionY;

        // 跳跃（NMS bF()：仅地面、非液体、非梯子、非蛛网）
        boolean jumped = jumping && onGround && !inLiquid && !onLadder && !inWeb;
        if (jumped) {
            motY = JUMP_VELOCITY + jumpLevel * 0.1;
            if (headBlocked) {
                motY = Math.min(motY, HEAD_BLOCKED_JUMP_CAP);
            }
            if (sprinting) {
                double rad = yaw * Math.PI / 180.0;
                motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
            }
        }

        // 输入加速度（NMS Entity.a(f, f1, f2)）
        double f6 = ACCEL_FACTOR / (hFriction * hFriction * hFriction);
        double baseSpeed = (BASE_SPEED + SPEED_POTION_PER_LEVEL * speedLevel)
                * (sprinting ? SPRINT_MODIFIER : 1.0);
        double inputSpeed;
        if (inLiquid) {
            // NMS 水分支：f4 = onGround&&sprint ? 0.1 : 0.02; f5 = bI()*f4
            inputSpeed = baseSpeed * ((onGround && sprinting) ? 0.1 : LIQUID_INPUT_FACTOR);
        } else if (onGround) {
            inputSpeed = baseSpeed * f6;
        } else {
            inputSpeed = AIR_ACCEL;
        }
        if (sneaking) {
            inputSpeed *= SNEAK_FACTOR;
        }
        if (usingItem) {
            inputSpeed *= USING_ITEM_FACTOR;
        }

        double fwd = 1.0;
        double strafe = 0.0;
        double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
        if (f3 >= 1e-4) {
            if (f3 < 1.0) {
                f3 = 1.0;
            }
            f3 = inputSpeed / f3;
            double sinYaw = Math.sin(yaw * Math.PI / 180.0);
            double cosYaw = Math.cos(yaw * Math.PI / 180.0);
            // NMS: motX += (fwd*cos - strafe*sin)*f3 ; motZ += (strafe*cos + fwd*sin)*f3
            motX += (fwd * f3) * cosYaw - (strafe * f3) * sinYaw;
            motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;
        }

        // 蜘蛛网阻尼（Entity.move 内，输入后）
        if (inWeb) {
            motX *= WEB_DAMP;
            motY *= WEB_DAMP;
            motZ *= WEB_DAMP;
        }

        // 垂直增量
        if (onLadder) {
            motY = LADDER_CLIMB;
        } else if (!jumped) {
            if (onGround) {
                motY = 0.0; // 地板碰撞吸收重力：站立/落地该 tick 无垂直位移
            } else if (inLiquid && jumping) {
                motY += LIQUID_SWIM_UP; // 水中按跳跃上浮
            }
            // 空中不跳：delta_Y = 携带速度（重力体现在 ShadowPlayer 状态更新）
        }

        return new Result(motX, motZ, motY, onGround);
    }

    // ------------------------------------------------------------------
    // candidates 重载
    // ------------------------------------------------------------------

    /** 兼容旧签名：motionY=0、无世界状态、不使用物品。 */
    public static Candidate[] candidates(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel) {
        return candidates(motionX, 0.0, motionZ, onGround, yaw, frictionFactor,
                sprinting, speedLevel, jumpLevel, false, false, false, false, false);
    }

    /** 兼容旧签名：motionY=0、不使用物品。 */
    public static Candidate[] candidates(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
        return candidates(motionX, 0.0, motionZ, onGround, yaw, frictionFactor,
                sprinting, speedLevel, jumpLevel, inLiquid, inWeb, onLadder, headBlocked, false);
    }

    /**
     * 完整候选生成：{idle, walk, sprint, sneak} × {不跳, 跳}。
     * 覆盖"玩家可能的一切合法输入"，实际位移命中任一候选即合法。
     */
    public static Candidate[] candidates(
            double motionX, double motionY, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked,
            boolean usingItem) {

        List<Candidate> list = new ArrayList<Candidate>();
        double[] speedFactors = {0.0, 1.0, SPRINT_MODIFIER, SNEAK_FACTOR};
        String[] speedLabels = {"idle", "walk", "sprint", "sneak"};
        boolean[] jumpFlags = {false, true};

        for (int s = 0; s < speedFactors.length; s++) {
            for (int j = 0; j < jumpFlags.length; j++) {
                boolean jump = jumpFlags[j] && onGround;
                // 疾跑行（factor==SPRINT_MODIFIER）的输入/冲量不依赖 sprinting 标志：
                // 客户端先发移动包后发 START_SPRINTING（或标志丢失/滞后）时
                // m.sprinting=false 但玩家实际在疾跑；若疾跑行按非疾跑基础速度计算，
                // maxH 低于实际位移 → sim-speed 误判正常走路。过预测是安全方向。
                boolean sprintRow = speedFactors[s] == SPRINT_MODIFIER;
                double factor = speedFactors[s];

                double hFriction = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
                if (inLiquid) {
                    hFriction = LIQUID_DRAG;
                }
                double motX = motionX * hFriction;
                double motZ = motionZ * hFriction;
                double motY = motionY;

                if (jump && !inLiquid && !onLadder && !inWeb) {
                    motY = JUMP_VELOCITY + jumpLevel * 0.1;
                    if (headBlocked) {
                        motY = Math.min(motY, HEAD_BLOCKED_JUMP_CAP);
                    }
                    if (sprintRow) {
                        double rad = yaw * Math.PI / 180.0;
                        motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                        motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                    }
                }

                double f6 = ACCEL_FACTOR / (hFriction * hFriction * hFriction);
                double baseSpeed = (BASE_SPEED + SPEED_POTION_PER_LEVEL * speedLevel)
                        * (sprintRow ? SPRINT_MODIFIER : 1.0);
                double inputSpeed;
                if (inLiquid) {
                    inputSpeed = baseSpeed * ((onGround && sprintRow) ? 0.1 : LIQUID_INPUT_FACTOR);
                } else if (onGround) {
                    inputSpeed = baseSpeed * f6;
                } else {
                    inputSpeed = AIR_ACCEL;
                }
                inputSpeed *= factor;
                if (usingItem) {
                    inputSpeed *= USING_ITEM_FACTOR;
                }

                double fwd = 1.0;
                double strafe = 0.0;
                double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
                if (f3 < 1e-4) {
                    continue;
                }
                if (f3 < 1.0) {
                    f3 = 1.0;
                }
                f3 = inputSpeed / f3;
                double sinYaw = Math.sin(yaw * Math.PI / 180.0);
                double cosYaw = Math.cos(yaw * Math.PI / 180.0);
                motX += (fwd * f3) * cosYaw - (strafe * f3) * sinYaw;
                motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;

                if (inWeb) {
                    motX *= WEB_DAMP;
                    motY *= WEB_DAMP;
                    motZ *= WEB_DAMP;
                }

                if (onLadder) {
                    motY = LADDER_CLIMB;
                } else if (!jump) {
                    if (onGround) {
                        motY = 0.0;
                    } else if (inLiquid && jumpFlags[j]) {
                        motY += LIQUID_SWIM_UP;
                    }
                }

                list.add(new Candidate(motX, motZ, motY,
                        speedLabels[s] + (jump ? "+jump" : "")));
            }
        }
        return list.toArray(new Candidate[0]);
    }

    // ------------------------------------------------------------------
    // candidatesMultiTick 重载（高 ping：一个包覆盖多个服务器 tick）
    // ------------------------------------------------------------------

    /** 兼容旧签名：无世界状态、不使用物品。 */
    public static Candidate[] candidatesMultiTick(
            double motionX, double motionZ, double motionY,
            boolean onGround, float yaw, double frictionFactor,
            boolean sprinting, double speedLevel, double jumpLevel, int ticks) {
        return candidatesMultiTick(motionX, motionZ, motionY, onGround, yaw, frictionFactor,
                sprinting, speedLevel, jumpLevel, ticks, false, false, false, false, false);
    }

    /** 兼容旧签名：不使用物品。 */
    public static Candidate[] candidatesMultiTick(
            double motionX, double motionZ, double motionY,
            boolean onGround, float yaw, double frictionFactor,
            boolean sprinting, double speedLevel, double jumpLevel, int ticks,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
        return candidatesMultiTick(motionX, motionZ, motionY, onGround, yaw, frictionFactor,
                sprinting, speedLevel, jumpLevel, ticks,
                inLiquid, inWeb, onLadder, headBlocked, false);
    }

    /**
     * 多 tick 候选：逐 tick 模拟（含重力/摩擦/跳跃），累加位置增量。
     * 用于一个移动包间隔覆盖多个服务器 tick（高 ping）的场景。
     */
    public static Candidate[] candidatesMultiTick(
            double motionX, double motionZ, double motionY,
            boolean onGround, float yaw, double frictionFactor,
            boolean sprinting, double speedLevel, double jumpLevel, int ticks,
            boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked,
            boolean usingItem) {

        if (ticks <= 1) {
            return candidates(motionX, motionY, motionZ, onGround, yaw, frictionFactor,
                    sprinting, speedLevel, jumpLevel, inLiquid, inWeb, onLadder, headBlocked, usingItem);
        }

        List<Candidate> list = new ArrayList<Candidate>();
        double[] speedFactors = {0.0, 1.0, SPRINT_MODIFIER, SNEAK_FACTOR};
        String[] speedLabels = {"idle", "walk", "sprint", "sneak"};

        for (int s = 0; s < speedFactors.length; s++) {
            for (int jumpAttempt = 0; jumpAttempt <= 1; jumpAttempt++) {
                boolean jumpOnTick0 = (jumpAttempt == 1) && onGround;
                // 疾跑行输入/冲量不依赖 sprinting 标志（原因同单 tick 候选）。
                boolean sprintRow = speedFactors[s] == SPRINT_MODIFIER;
                double factor = speedFactors[s];

                double motX = motionX;
                double motZ = motionZ;
                double motY = motionY;
                double totalDX = 0.0;
                double totalDZ = 0.0;
                double totalDY = 0.0;
                boolean ground = onGround;

                for (int t = 0; t < ticks; t++) {
                    double hFriction = ground ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
                    if (inLiquid) {
                        hFriction = LIQUID_DRAG;
                    }
                    motX *= hFriction;
                    motZ *= hFriction;

                    boolean jumpedTick = (t == 0 && jumpOnTick0 && !inLiquid && !onLadder && !inWeb);
                    if (jumpedTick) {
                        motY = JUMP_VELOCITY + jumpLevel * 0.1;
                        if (headBlocked) {
                            motY = Math.min(motY, HEAD_BLOCKED_JUMP_CAP);
                        }
                        double rad = yaw * Math.PI / 180.0;
                        motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                        motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                        ground = false;
                    }

                    double f6 = ACCEL_FACTOR / (hFriction * hFriction * hFriction);
                    double baseSpeed = (BASE_SPEED + SPEED_POTION_PER_LEVEL * speedLevel)
                            * (sprintRow ? SPRINT_MODIFIER : 1.0);
                    double inputSpeed;
                    if (inLiquid) {
                        inputSpeed = baseSpeed * ((ground && sprintRow) ? 0.1 : LIQUID_INPUT_FACTOR);
                    } else if (ground) {
                        inputSpeed = baseSpeed * f6;
                    } else {
                        inputSpeed = AIR_ACCEL;
                    }
                    inputSpeed *= factor;
                    if (usingItem) {
                        inputSpeed *= USING_ITEM_FACTOR;
                    }

                    double fwd = 1.0;
                    double f3 = Math.max(1.0, Math.sqrt(fwd * fwd));
                    f3 = inputSpeed / f3;
                    double sinYaw = Math.sin(yaw * Math.PI / 180.0);
                    double cosYaw = Math.cos(yaw * Math.PI / 180.0);
                    motX += fwd * f3 * cosYaw;
                    motZ += fwd * f3 * sinYaw;

                    if (inWeb) {
                        motX *= WEB_DAMP;
                        motY *= WEB_DAMP;
                        motZ *= WEB_DAMP;
                    }

                    // 该 tick 的位置增量
                    totalDX += motX;
                    totalDZ += motZ;
                    totalDY += motY;

                    // 状态推进（下一 tick 的携带值）
                    if (onLadder) {
                        motY = LADDER_CLIMB;
                    } else if (jumpedTick) {
                        motY = (motY - GRAVITY) * VERTICAL_DRAG;
                    } else if (ground) {
                        motY = 0.0;
                    } else if (inWeb) {
                        motY = (motY - GRAVITY) * VERTICAL_DRAG;
                    } else if (inLiquid) {
                        motY = motY * LIQUID_DRAG - LIQUID_GRAVITY;
                    } else {
                        motY = (motY - GRAVITY) * VERTICAL_DRAG;
                    }
                    ground = false;
                }

                list.add(new Candidate(totalDX, totalDZ, totalDY,
                        speedLabels[s] + (jumpOnTick0 ? "+jump" : "") + "x" + ticks));
            }
        }
        return list.toArray(new Candidate[0]);
    }
}
