package com.ycbr.anticheat.simulation;

/**
 * 已知合法异常豁免注册表（蓝图 7.2，借鉴 NCP MagicAir.oddJunction 思想）。
 *
 * <p>引擎系（SimulationCheck/WorldProbe 消费）的所有豁免集中于此：判定门面
 * + {@link #EXEMPTIONS} 注册表登记（名称/版本/描述），便于维护、防遗漏、
 * 新豁免按表扩展。每条豁免只做"是否命中"判定，幅度（容差乘数）由调用方
 * 用 config 决定——职责分离，不改行为。</p>
 *
 * <p>其他 check 的内联豁免（VelocityCheck 墙/天花板、NoSlowCheck 介质、
 * KillAuraCheck 战斗豁免等）属行为敏感区，保持原地，未收拢。</p>
 */
public final class KnownExemptions {

    /** 注册表条目：豁免名 + 适用 mc 版本 + 描述。 */
    public static final class Exemption {
        public final String name;
        public final String mcVersion;
        public final String description;

        Exemption(String name, String mcVersion, String description) {
            this.name = name;
            this.mcVersion = mcVersion;
            this.description = description;
        }
    }

    /** 引擎系豁免注册表（判定实现在下方同名门面方法）。 */
    public static final Exemption[] EXEMPTIONS = {
        new Exemption("MEDIUM", "1.8.8",
                "liquid/web/ladder: input physics differs from air, tolerance x2"),
        new Exemption("PISTON", "1.8.8",
                "piston push: displacement externally driven, sim-speed tolerance x3"),
        new Exemption("MULTI_TICK", "1.8.8",
                "high-ping multi-tick window: accumulated error grows ~sqrt(ticks)"),
        new Exemption("STEP_VERTICAL", "1.8.8",
                "stairs/slab auto-step: |dy| <= 0.6, engine has no step model"),
        new Exemption("SLIME_BOUNCE", "1.8.8",
                "slime block bounce: |dy| <= 0.65 envelope, engine has no bounce model"),
    };

    private KnownExemptions() {}

    // ---------- 门面判定（行为与迁移前完全一致） ----------

    /** MEDIUM：液体/网/梯子任一命中即豁免（调用方按 liquid-tolerance-multiplier 放大）。 */
    public static boolean isMediumExempt(WorldProbe.ProbeResult probe) {
        return probe != null && (probe.inLiquid || probe.inWeb || probe.onLadder);
    }

    /** PISTON：活塞推动中豁免（调用方按 piston-tolerance-multiplier 放大）。 */
    public static boolean isPistonExempt(WorldProbe.ProbeResult probe) {
        return probe != null && probe.onPiston;
    }

    /** MULTI_TICK：tick 窗口 > 1 时累计误差放大因子（sqrt(ticks)），单 tick 恒为 1。 */
    public static double multiTickSqrtFactor(int ticks) {
        return ticks > 1 ? Math.sqrt(ticks) : 1.0;
    }

    /** STEP_VERTICAL：台阶/楼梯自动步进垂直位移在 0.6 步高包络内放行。 */
    public static boolean stepVerticalAllowed(double actualDY, boolean onStepTerrain) {
        return onStepTerrain && Math.abs(actualDY) <= 0.6D;
    }

    /** SLIME_BOUNCE：粘液块弹跳垂直位移在 0.65 包络内放行。 */
    public static boolean slimeBounceAllowed(double actualDY, boolean onSlime) {
        return onSlime && Math.abs(actualDY) <= 0.65D;
    }
}