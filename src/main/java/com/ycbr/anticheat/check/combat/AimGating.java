package com.ycbr.anticheat.check.combat;

/**
 * 瞄准启发式子检测的惩罚门控（纯逻辑，可单测）。
 *
 * <p>解决"门控形同虚设"的问题：历史上 8 个瞄准启发式子检测（AimStep/Gcd/ConstStep/
 * AxisAsym/BigRot/Angle/Switch 等）挂在 aimstat 交叉验证上，但 aimstat 默认关闭时
 * 这些子检测实际是<b>直判</b>（不经过任何交叉验证）——误判风险集中在默认配置下。</p>
 *
 * <p>本类提供两档策略：
 * <ul>
 *   <li><b>直判（legacy）</b>：aimstat 关闭或未开交叉 → 维持原判；aimstat 开启时
 *       要求交叉信号命中且新鲜（冷启动期直判，兼容旧行为）。</li>
 *   <li><b>软降级（soft，config {@code killaura.heuristic-soft}）</b>：无论 aimstat
 *       状态，只有交叉信号命中且新鲜才 punish；否则只投 {@code heur-*} 信号。
 *       适合把误判风险真正管住、先用信号积累数据的服务器。</li>
 * </ul></p>
 */
public final class AimGating {

    private AimGating() {}

    /**
     * @param sub            子检测名（AIM_GATED_SUBS 集合内判定）
     * @param gated          该子检测是否属于"瞄准模式"门控集合
     * @param heuristicSoft  软降级模式是否开启（config killaura.heuristic-soft）
     * @param aimstatEnabled aimstat 检测是否启用
     * @param crossEnabled   aimstat-cross 交叉开关
     * @param samplesReady   统计层样本是否已收集满（冷启动标志）
     * @param signalHit      最近是否命中过 aim-stat 交叉信号
     * @param signalFresh    交叉信号是否新鲜（signal-fresh-ms 内）
     * @return true = 直接 punish；false = 只投启发式信号不 punish
     */
    public static boolean shouldPunish(String sub, boolean gated, boolean heuristicSoft,
            boolean aimstatEnabled, boolean crossEnabled,
            boolean samplesReady, boolean signalHit, boolean signalFresh) {
        if (!gated) {
            return true;
        }
        if (heuristicSoft) {
            // 软降级：仅当 aimstat 启用、样本就绪、交叉信号命中且新鲜才 punish；
            // aimstat 未启用（无交叉验证来源）与冷启动一律软降级，消除直判误杀。
            return aimstatEnabled && samplesReady && signalHit && signalFresh;
        }
        // 兼容旧行为：aimstat 关闭/未开交叉 → 直判；冷启动 → 直判
        if (!aimstatEnabled || !crossEnabled) {
            return true;
        }
        if (!samplesReady) {
            return true;
        }
        return signalHit && signalFresh;
    }
}
