package com.ycbr.anticheat.check.combat;

/**
 * 临界距离动态收缩（借鉴 NCP reachMod，8AC 蓝图 P3.3 第 3 点）。
 *
 * <p>玩家长期在临界距离攻击（边缘命中，距离逼近上限）→ 逐步收紧允许距离；
 * 正常距离攻击 → 指数衰减回零。擦边但合法的玩家会周期性 cleanAttack，
 * 收缩不会累计到误判水平。纯逻辑，无 Bukkit 依赖。</p>
 */
public final class ReachModLogic {

    private final int edgeStreakRequired;
    private final double shrinkStep;
    private final double maxShrink;
    private int edgeStreak;
    private double modifier;

    public ReachModLogic(int edgeStreakRequired, double shrinkStep, double maxShrink) {
        this.edgeStreakRequired = edgeStreakRequired;
        this.shrinkStep = shrinkStep;
        this.maxShrink = maxShrink;
    }

    /** 临界距离攻击（距离接近上限）：累计连击，达阈值即收缩一步。 */
    public void onEdgeAttack(double distanceRatio) {
        edgeStreak++;
        if (edgeStreak >= edgeStreakRequired) {
            modifier = Math.min(maxShrink, modifier + shrinkStep);
            edgeStreak = 0;
        }
    }

    /** 正常距离攻击：衰减。 */
    public void onCleanAttack() {
        edgeStreak = 0;
        modifier *= 0.8D;
    }

    public double currentModifier() {
        return modifier;
    }
}