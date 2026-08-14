package com.ycbr.anticheat.check.protocol;

/**
 * Timer 纯逻辑（无 Bukkit 依赖，可单测）。
 *
 * <p>输入：每移动包到达时"该包覆盖的服务器 tick 间隔"（由主线程 tick 计数差值提供，
 * 与 ping 解耦——高 ping 玩家包到达有延迟，但相邻包覆盖的服务器 tick 数平均仍趋近 1；
 * 加速器则稳定 &lt; 1）。窗口内平均间隔低于阈值即判加速。</p>
 */
public final class TimerLogic {

    private static final int WINDOW = 100;

    private final double[] intervals = new double[WINDOW];
    private int head;
    private int count;
    private double lastAverage;

    /**
     * 记录一次移动包：intervalTicks = 该包覆盖的服务器 tick 数
     * （正常 1 tick；加速器 &lt;1；丢包合并 &gt;1）。
     *
     * @return true 表示窗口内平均间隔显著低于 1（加速）
     */
    public boolean feed(double intervalTicks, int windowSize, double threshold) {
        intervals[head] = intervalTicks;
        head = (head + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
        if (count < windowSize) {
            return false;
        }
        double sum = 0;
        int n = Math.min(count, windowSize);
        for (int i = 0; i < n; i++) {
            int idx = (head - 1 - i + WINDOW) % WINDOW;
            sum += intervals[idx];
        }
        lastAverage = sum / n;
        return lastAverage < threshold;
    }

    /** 最近一次窗口平均间隔（tick/包）。 */
    public double lastAverage() {
        return lastAverage;
    }

    /**
     * 按实际 TPS 归一化间隔：把"每包覆盖的服务器 tick 数"换算回 20 TPS 语义。
     *
     * <p>客户端按 50ms 真实时间发包，而服务器 tick 时长 = 1000/tps。TPS&lt;20 时
     * （如 19.2 → tick 52ms），正常玩家每包仅覆盖 50/52≈0.96 tick，会被误判为加速。
     * 归一化后正常玩家回到 ≈1.0；真正加速器（40ms/包）仍 &lt;1，可继续被抓。</p>
     *
     * @param intervalTicks 原始间隔（每包覆盖的服务器 tick 数，可为小数）
     * @param tps 服务器当前 TPS（≤0 时按保守下限处理，避免除零/负值放大误判）
     */
    public static double normalizedInterval(double intervalTicks, double tps) {
        double safe = Math.max(tps, 10.0D);
        return intervalTicks * 20.0D / safe;
    }

    public void reset() {
        head = 0;
        count = 0;
        lastAverage = 0D;
    }
}