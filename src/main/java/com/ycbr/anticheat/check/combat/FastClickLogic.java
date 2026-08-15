package com.ycbr.anticheat.check.combat;

import java.util.ArrayList;
import java.util.List;

import com.ycbr.anticheat.util.Statistics;

/**
 * 自动点击纯逻辑：cps + burst + CV + 峰度 + 熵 五维判定。
 * 无 Bukkit 依赖，可单测。
 *
 * <p>机械点击：点击间隔高度规律（峰度显著为负）或熵极低（间隔值种类极少）。</p>
 */
public final class FastClickLogic {

    private static final int MAX_INTERVALS = 100;
    private static final int MIN_SAMPLES = 40;

    private final List<Long> intervals = new ArrayList<Long>();

    public void feed(long intervalMs) {
        intervals.add(intervalMs);
        if (intervals.size() > MAX_INTERVALS) {
            intervals.remove(0);
        }
    }

    public void reset() {
        intervals.clear();
    }

    public int sampleCount() {
        return intervals.size();
    }

    /** 机械模式判定：间隔高度规律（负峰度）或熵极低。 */
    public boolean mechanicalPattern() {
        return mechanicalPattern(-1.5D);
    }

    /**
     * 机械模式判定（阈值可配）。
     *
     * @param kurtosisMax 峰度上限（低于即机械心跳）
     */
    public boolean mechanicalPattern(double kurtosisMax) {
        return mechanicalPattern(kurtosisMax, 120.0D);
    }

    /**
     * 机械模式判定（阈值可配）。
     *
     * <p>【误判修复】原判定只看峰度/熵，任何"恒定节奏"（含挖矿时被挖掘周期驱动的
     * 慢速还击、稳定手点）都会命中。点击宏的区分特征不止"恒定"，还有"高速"——
     * 加 {@code maxMeanIntervalMs} 高速前置：平均间隔超过阈值（默认 120ms，即 &gt;8.3cps）
     * 一律视为非点击宏（挖矿/拆包等慢速恒定节奏被排除）。</p>
     *
     * @param kurtosisMax       峰度上限（低于即机械心跳）
     * @param maxMeanIntervalMs 平均间隔上限（ms），超过则不算高速点击宏
     */
    public boolean mechanicalPattern(double kurtosisMax, double maxMeanIntervalMs) {
        if (intervals.size() < MIN_SAMPLES) {
            return false;
        }
        List<Double> xs = new ArrayList<Double>(intervals.size());
        for (long v : intervals) {
            xs.add((double) v);
        }
        double mean = Statistics.average(xs);
        if (mean > maxMeanIntervalMs) {
            return false; // 慢速恒定节奏（挖矿还击/拆包）不是点击宏
        }
        double kurt = Statistics.kurtosis(xs);
        double entropy = Statistics.shannonEntropy(xs);
        return kurt < kurtosisMax || entropy < 1.0D;
    }
}