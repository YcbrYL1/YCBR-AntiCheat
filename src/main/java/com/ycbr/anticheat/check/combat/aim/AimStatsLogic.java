package com.ycbr.anticheat.check.combat.aim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ycbr.anticheat.util.Statistics;

/**
 * Aim 统计纯逻辑（无 Bukkit 依赖，可单测）。
 *
 * <p>输入：攻击窗口内的视角增量序列（yaw 增量）。输出：命中的统计信号集合
 * （entropy/iqr/ks/jiff/zscore/kurtosis），供 {@link AimStatisticsCheck} 转为
 * 交叉信号。任一信号都不可单独判定——必须由启发式（KillAura）交叉后才 punish。</p>
 */
public final class AimStatsLogic {

    /** 判定一次统计信号的最小样本数（冷启动保护）。 */
    public static final int MIN_SAMPLES = 25;

    /**
     * 对 yaw 增量序列做统计判定。
     *
     * @param deltas        攻击窗口内的 yaw 增量（度），已过滤过大/过小转动
     * @param entropyMax    Shannon 熵上限（低于即机械）
     * @param iqrMin        IQR 下限（低于即常数步长）
     * @param ksMinUniform  KS 下限（对均匀分布偏差过小 = 过度均匀 = 随机化修饰）
     * @param jiffPatternLen Jiff 模式长度
     * @param jiffMax       Jiff 重复次数上限（达到即序列重复）
     * @param zscoreThreshold Z-score 离群阈值
     * @param kurtosisMax   峰度上限（低于即机械心跳）
     * @return 命中的信号名集合（可能为空）
     */
    public List<String> evaluate(List<Double> deltas, double entropyMax, double iqrMin,
            double ksMinUniform, int jiffPatternLen, int jiffMax, double zscoreThreshold,
            double kurtosisMax) {
        List<String> hits = new ArrayList<String>();
        if (deltas == null || deltas.size() < MIN_SAMPLES) {
            return hits;
        }

        double entropy = Statistics.shannonEntropy(deltas);
        if (entropy < entropyMax) {
            hits.add("entropy");
        }

        double iqr = Statistics.iqr(deltas);
        if (iqr < iqrMin) {
            hits.add("iqr");
        }

        // KS：与均匀分布比较。真人瞄准呈尖峰分布（与均匀差异大）；
        // 随机化修饰（aimbot jitter）会把增量抹平成均匀分布（与均匀差异小）。
        double ks = Statistics.kolmogorovSmirnov(deltas, uniformSample(deltas));
        if (ks < ksMinUniform) {
            hits.add("ks");
        }

        int jiff = Statistics.jiffDelta(deltas, jiffPatternLen);
        if (jiff >= jiffMax) {
            hits.add("jiff");
        }

        // z-score：需≥3 个离群（持续转枪/连拍），单次真人 flick 不算
        if (Statistics.zScoreOutliers(deltas, zscoreThreshold).size() >= 3) {
            hits.add("zscore");
        }

        double kurt = Statistics.kurtosis(deltas);
        if (kurt < kurtosisMax) {
            hits.add("kurtosis");
        }

        return hits;
    }

    /** 生成与输入同量程的均匀分布样本（KS 对照）。 */
    private List<Double> uniformSample(List<Double> deltas) {
        List<Double> sorted = new ArrayList<Double>(deltas);
        Collections.sort(sorted);
        double min = sorted.get(0);
        double max = sorted.get(sorted.size() - 1);
        double span = max - min;
        if (span < 1e-9) {
            span = 1e-9;
        }
        List<Double> uniform = new ArrayList<Double>(deltas.size());
        for (int i = 0; i < deltas.size(); i++) {
            uniform.add(min + span * (i + 0.5D) / deltas.size());
        }
        return uniform;
    }
}