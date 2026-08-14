package com.ycbr.anticheat.core;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.bukkit.Bukkit;
import java.util.Map;

import com.ycbr.anticheat.util.MathUtil;
import com.ycbr.anticheat.util.Statistics;

/**
 * 数据集采集管线（借鉴 MX DatasetManager / RECORDING 模式）。
 * 按玩家名记录：视角增量统计特征（熵/IQR/KS/Jiff/Z-score/峰度）与标签（legit/cheat），
 * 样本落盘 plugins/YCBR/dataset/*.csv，供可选 MLP 训练。
 */
public final class DatasetManager {

    private final AntiCheatManager manager;
    private final Path dir;
    private final List<String> recording = new ArrayList<String>();
    private final Map<String, String> labels = new HashMap<String, String>();

    public DatasetManager(AntiCheatManager manager) {
        this.manager = manager;
        this.dir = Paths.get(manager.getPlugin().getDataFolder().getPath(), "dataset");
    }

    public boolean isRecording() {
        return !recording.isEmpty();
    }

    public boolean isRecording(String player) {
        return recording.contains(player);
    }

    public void startRecording(String player, String label) {
        if (!recording.contains(player)) {
            recording.add(player);
            labels.put(player, label == null || label.isEmpty() ? "legit" : label);
        }
    }

    public void stopRecording(String player) {
        recording.remove(player);
        labels.remove(player);
    }

    /** 玩家名消毒：仅保留字母数字下划线，防止路径穿越。 */
    public static String sanitize(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public void record(String player, String label, String dataLine) {
        if (!recording.contains(player)) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(sanitize(label) + "_" + sanitize(player) + ".csv");
            BufferedWriter w = Files.newBufferedWriter(f, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            w.write(dataLine);
            w.newLine();
            w.close();
        } catch (Exception e) {
            Bukkit.getLogger().warning("YCBR: failed to write dataset record: " + e.getMessage());
        }
    }

    /** 写 CSV 表头（首次调用时由命令触发）。 */
    public void writeHeader(String player) {
        String label = labels.get(player);
        if (label == null) {
            return;
        }
        record(player, label, "entropy,iqr,ks,jiff,zscore_count,kurtosis,samples,mean,std");
    }

    /** 由 AimStatisticsCheck 在攻击窗口结束时调用：把窗口特征落盘。 */
    public void recordAimWindow(String player, List<Double> deltas) {
        String label = labels.get(player);
        if (label == null || !recording.contains(player)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(MathUtil.round(Statistics.shannonEntropy(deltas), 3)).append(',');
        sb.append(MathUtil.round(Statistics.iqr(deltas), 3)).append(',');
        sb.append(MathUtil.round(Statistics.kolmogorovSmirnov(deltas,
                uniformSample(deltas)), 3)).append(',');
        sb.append(Statistics.jiffDelta(deltas, 3)).append(',');
        sb.append(Statistics.zScoreOutliers(deltas, 4.0D).size()).append(',');
        sb.append(MathUtil.round(Statistics.kurtosis(deltas), 3)).append(',');
        sb.append(deltas.size()).append(',');
        sb.append(MathUtil.round(Statistics.average(deltas), 3)).append(',');
        sb.append(MathUtil.round(Statistics.standardDeviation(deltas), 3));
        record(player, label, sb.toString());
    }

    private List<Double> uniformSample(List<Double> deltas) {
        List<Double> sorted = new ArrayList<Double>(deltas);
        java.util.Collections.sort(sorted);
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