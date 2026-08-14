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
        return mechanicalPattern(-1.0D);
    }

    /**
     * 机械模式判定（阈值可配）。
     *
     * @param kurtosisMax 峰度上限（低于即机械心跳）
     */
    public boolean mechanicalPattern(double kurtosisMax) {
        if (intervals.size() < MIN_SAMPLES) {
            return false;
        }
        List<Double> xs = new ArrayList<Double>(intervals.size());
        for (long v : intervals) {
            xs.add((double) v);
        }
        double kurt = Statistics.kurtosis(xs);
        double entropy = Statistics.shannonEntropy(xs);
        return kurt < kurtosisMax || entropy < 1.0D;
    }
}