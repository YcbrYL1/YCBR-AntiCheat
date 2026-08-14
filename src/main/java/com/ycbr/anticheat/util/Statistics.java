package com.ycbr.anticheat.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无状态统计工具库（借鉴 MX Statistics）。
 * 纯函数，无 Bukkit 依赖，可单测。
 */
public final class Statistics {

    private Statistics() {}

    public static double average(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double x : xs) sum += x;
        return sum / xs.size();
    }

    public static double variance(List<Double> xs) {
        if (xs == null || xs.size() < 2) return 0.0;
        double mean = average(xs);
        double sumSq = 0.0;
        for (double x : xs) {
            double d = x - mean;
            sumSq += d * d;
        }
        return sumSq / (xs.size() - 1);
    }

    public static double standardDeviation(List<Double> xs) {
        return Math.sqrt(variance(xs));
    }

    public static double shannonEntropy(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        Map<Double, Integer> counts = new HashMap<Double, Integer>();
        for (double x : xs) {
            Integer c = counts.get(x);
            counts.put(x, c == null ? 1 : c + 1);
        }
        double entropy = 0.0;
        for (int c : counts.values()) {
            double p = (double) c / xs.size();
            if (p > 0.0) entropy -= p * (Math.log(p) / Math.log(2.0));
        }
        return entropy;
    }

    public static double kurtosis(List<Double> xs) {
        if (xs == null || xs.size() < 4) return 0.0;
        double mean = average(xs);
        double std = standardDeviation(xs);
        if (std < 1e-12) return -3.0; // 完全恒定 → 极度机械
        double n = xs.size();
        // 原始矩（不标准化，保证尺度不变）：超额峰度 = m4/m2² - 3
        double m4 = 0.0;
        double m2 = 0.0;
        for (double x : xs) {
            double d = x - mean;
            double d2 = d * d;
            m4 += d2 * d2;
            m2 += d2;
        }
        m4 /= n;
        m2 /= n;
        return m4 / (m2 * m2) - 3.0;
    }

    public static double iqr(List<Double> xs) {
        if (xs == null || xs.size() < 2) return 0.0;
        List<Double> sorted = new ArrayList<Double>(xs);
        Collections.sort(sorted);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        return q3 - q1;
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        double rank = p * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted.get(lo);
        double frac = rank - lo;
        return sorted.get(lo) * (1.0 - frac) + sorted.get(hi) * frac;
    }

    public static List<Double> zScoreOutliers(List<Double> xs, double threshold) {
        List<Double> outliers = new ArrayList<Double>();
        if (xs == null || xs.size() < 3) return outliers;
        // 稳健 z-score：中位数 + MAD（对离群值本身不敏感，避免污染）
        List<Double> sorted = new ArrayList<Double>(xs);
        Collections.sort(sorted);
        double median = percentile(sorted, 0.5);
        List<Double> devs = new ArrayList<Double>(sorted.size());
        for (double x : sorted) {
            devs.add(Math.abs(x - median));
        }
        Collections.sort(devs);
        double mad = percentile(devs, 0.5);
        if (mad < 1e-12) return outliers;
        double scale = 0.6745 / mad;
        for (double x : xs) {
            double z = Math.abs(x - median) * scale;
            if (z > threshold) outliers.add(x);
        }
        return outliers;
    }

    public static double kolmogorovSmirnov(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 1.0;
        List<Double> sa = new ArrayList<Double>(a);
        List<Double> sb = new ArrayList<Double>(b);
        Collections.sort(sa);
        Collections.sort(sb);
        // 双样本 KS：合并两个排序序列，步进时维护两经验 CDF 的最大差
        int i = 0, j = 0;
        double maxDiff = 0.0;
        while (i < sa.size() || j < sb.size()) {
            double ecdf1;
            double ecdf2;
            if (i >= sa.size()) {
                j++;
            } else if (j >= sb.size()) {
                i++;
            } else if (sa.get(i) < sb.get(j)) {
                i++;
            } else if (sb.get(j) < sa.get(i)) {
                j++;
            } else {
                i++;
                j++;
            }
            ecdf1 = (double) i / sa.size();
            ecdf2 = (double) j / sb.size();
            double diff = Math.abs(ecdf1 - ecdf2);
            if (diff > maxDiff) maxDiff = diff;
        }
        return maxDiff;
    }

    public static int jiffDelta(List<Double> xs, int patternLen) {
        if (xs == null || xs.size() < patternLen * 2) return 0;
        int repeats = 0;
        for (int start = 0; start + patternLen * 2 <= xs.size(); start++) {
            boolean match = true;
            for (int k = 0; k < patternLen; k++) {
                if (!xs.get(start + k).equals(xs.get(start + patternLen + k))) {
                    match = false;
                    break;
                }
            }
            if (match) repeats++;
        }
        return repeats;
    }
}
