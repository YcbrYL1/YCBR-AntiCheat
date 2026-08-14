package com.ycbr.anticheat.simulation;

/**
 * 1.8.8 物理常量集中表（蓝图 7.1 集中化）。
 *
 * <p>所有引擎/几何/豁免使用的物理常量必须定义于此，其余类引用或委托，
 * 防止跨类魔法数漂移。出处标注 NMS 1.8.8 类/方法名。</p>
 *
 * <p>经验拟合值（SpeedCheck 等 @Deprecated 经验检测）不属于 1.8.8 物理常量，
 * 保持原地冻结，不收入本类。</p>
 */
public final class PhysicsConstants {

    private PhysicsConstants() {}

    // ---------- 移动基础 ----------

    /** generic.movementSpeed 基础值（EntityHuman.initAttributes:272）。 */
    public static final double BASE_SPEED = 0.1;

    /** 空中加速度 aM（EntityLiving 字段，默认 0.02F）。 */
    public static final double AIR_ACCEL = 0.02;

    /** 重力（EntityLiving.g: motY -= 0.08）。 */
    public static final double GRAVITY = 0.08;

    /** 垂直拖拽（motY *= 0.98）。 */
    public static final double VERTICAL_DRAG = 0.98;

    /** 空气/水平摩擦基数（onGround ? slipperiness*0.91 : 0.91）。 */
    public static final double AIR_FRICTION = 0.91;

    /** 地面加速度换算常量（0.16277136 / f5^3）。 */
    public static final double ACCEL_FACTOR = 0.16277136;

    /** 跳跃初速度（EntityLiving.bF: motY = 0.42）。 */
    public static final double JUMP_VELOCITY = 0.42;

    /** 疾跑速度修饰（操作码 2，+30%）。 */
    public static final double SPRINT_MODIFIER = 1.3;

    /** 疾跑跳跃水平冲量（bF: motX -= sin*0.2; motZ += cos*0.2）。 */
    public static final double SPRINT_JUMP_IMPULSE = 0.2;

    /** 潜行减速因子（客户端施加）。 */
    public static final double SNEAK_FACTOR = 0.3;

    /** 使用物品减速（NMS 1.8 EntityHuman: 使用物品时 motX/Z *= 0.2）。 */
    public static final double USING_ITEM_FACTOR = 0.2;

    // ---------- 药水 ----------

    /** 速度药水每级加算（NMS 操作码 0，+0.2/级）。 */
    public static final double SPEED_POTION_PER_LEVEL = 0.2;

    /** 跳跃药水每级加算（EntityLiving.bF: motY = 0.42 + jumpLevel*0.1）。 */
    public static final double JUMP_POTION_PER_LEVEL = 0.1;

    // ---------- 液体 / 网 / 梯子 ----------

    /** 水中水平摩擦/拖拽（NMS 水分支 motX/Z *= 0.8）。 */
    public static final double LIQUID_DRAG = 0.8;

    /** 水中垂直拖拽后减量（NMS 水分支 motY *= 0.8 后 motY -= 0.02）。 */
    public static final double LIQUID_GRAVITY = 0.02;

    /** 水中输入加速度系数（NMS 水分支 f5 = bI()*0.02；贴地疾跑 *0.1）。 */
    public static final double LIQUID_INPUT_FACTOR = 0.02;

    /** 水中贴地疾跑输入系数（NMS 水分支 f5 贴地疾跑时 *0.1）。 */
    public static final double LIQUID_GROUND_SPRINT_FACTOR = 0.1;

    /** 水中上浮加速（按住跳跃键 motY += 0.04）。 */
    public static final double LIQUID_SWIM_UP = 0.04;

    /** 蜘蛛网阻尼（Entity.move: *= 0.105）。 */
    public static final double WEB_DAMP = 0.105;

    /** 梯子爬升速度（EntityLiving 梯子分支 motY = 0.15）。 */
    public static final double LADDER_CLIMB = 0.15;

    /** 头顶被挡时跳跃上限（简化碰撞：跳不起高）。 */
    public static final double HEAD_BLOCKED_JUMP_CAP = 0.3;

    // ---------- 地面 slipperiness（Surface 摩擦表） ----------

    /** 普通地面 slipperiness（friction 基准 0.6）。 */
    public static final double SLIPPERINESS_NORMAL = 0.6;

    /** 冰面 slipperiness 0.98（与 VERTICAL_DRAG 数值相同但语义不同，独立命名）。 */
    public static final double SLIPPERINESS_ICE = 0.98;

    /** 粘液块 slipperiness 0.8。 */
    public static final double SLIPPERINESS_SLIME = 0.8;

    /** 灵魂沙 slipperiness 0.4。 */
    public static final double SLIPPERINESS_SOUL_SAND = 0.4;

    // ---------- 碰撞 / 探测 ----------

    /** 墙碰撞截断生效上限：墙距 ≥ 此值视为无墙（吸收主线程探测 1 tick 滞后误差）。 */
    public static final double WALL_TRUNCATION_LIMIT = 0.65;

    /** 墙距探测步长（主线程 wallDistance 每步 0.05 米）。 */
    public static final double WALL_PROBE_STEP = 0.05;
}