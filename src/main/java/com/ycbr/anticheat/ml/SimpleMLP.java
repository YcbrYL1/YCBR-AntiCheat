package com.ycbr.anticheat.ml;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

/**
 * 极简 MLP（1 隐藏层，纯 Java，无外部依赖）。
 * 输入：统计特征向量（熵/峰度/IQR/KS/Jiff/均值/方差/灵敏度…）。
 * 输出：0~1 作弊概率（sigmoid）。
 * 仅在 AimStatisticsCheck 统计+启发式交叉之上做"增强"信号，
 * 不独立误判：ML 输出 > 0.9 且统计信号命中才加成 buffer。
 *
 * <p>权重默认随机（种子固定可复现），训练后用
 * {@link #loadWeights(double[][], double[], double[], double)} 或
 * {@link #loadFromFile(File)} 覆盖。权重文件缺失/解析失败 → 静默降级为随机权重，
 * 不影响任何检测。</p>
 */
public final class SimpleMLP {

    private final double[][] w1; // [hidden][input]
    private final double[] b1;
    private final double[] w2;
    private double b2;

    public SimpleMLP(int inputSize, int hiddenSize) {
        w1 = new double[hiddenSize][inputSize];
        b1 = new double[hiddenSize];
        w2 = new double[hiddenSize];
        Random rnd = new Random(42L);
        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < inputSize; j++) {
                w1[i][j] = (rnd.nextDouble() - 0.5D) * 0.3D;
            }
            b1[i] = (rnd.nextDouble() - 0.5D) * 0.1D;
            w2[i] = (rnd.nextDouble() - 0.5D) * 0.3D;
        }
        b2 = 0.0D;
    }

    public double forward(double[] x) {
        double[] h = new double[w1.length];
        for (int i = 0; i < w1.length; i++) {
            double sum = b1[i];
            double[] row = w1[i];
            for (int j = 0; j < x.length; j++) {
                sum += row[j] * x[j];
            }
            h[i] = Math.max(0.0D, sum); // ReLU
        }
        double out = b2;
        for (int i = 0; i < h.length; i++) {
            out += w2[i] * h[i];
        }
        return 1.0D / (1.0D + Math.exp(-out)); // sigmoid
    }

    /** 覆盖权重（由训练脚本生成后加载）。 */
    public void loadWeights(double[][] w1In, double[] b1In, double[] w2In, double b2In) {
        if (w1In == null || w1In.length != w1.length) {
            return;
        }
        for (int i = 0; i < w1In.length; i++) {
            if (w1In[i] == null || w1In[i].length != w1[i].length) {
                return;
            }
            System.arraycopy(w1In[i], 0, w1[i], 0, w1[i].length);
        }
        if (b1In != null && b1In.length == b1.length) {
            System.arraycopy(b1In, 0, b1, 0, b1.length);
        }
        if (w2In != null && w2In.length == w2.length) {
            System.arraycopy(w2In, 0, w2, 0, w2.length);
        }
        b2 = b2In;
    }

    /**
     * 从文本文件加载权重（每行逗号分隔）：
     * <pre>
     * w1_i_j      // hiddenSize 行，每行 inputSize 个权重
     * b1_i        // 1 行，hiddenSize 个偏置
     * w2_i        // 1 行，hiddenSize 个输出权重
     * b2          // 1 行，1 个输出偏置
     * </pre>
     * 任何解析失败 → 返回 false，保持现有权重不变。
     */
    public boolean loadFromFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
            int hidden = w1.length;
            int input = w1[0].length;
            double[][] nw1 = new double[hidden][input];
            for (int i = 0; i < hidden; i++) {
                nw1[i] = parseRow(r.readLine(), input);
                if (nw1[i] == null) {
                    r.close();
                    return false;
                }
            }
            double[] nb1 = parseRow(r.readLine(), hidden);
            double[] nw2 = parseRow(r.readLine(), hidden);
            String b2Line = r.readLine();
            r.close();
            if (nb1 == null || nw2 == null || b2Line == null) {
                return false;
            }
            loadWeights(nw1, nb1, nw2, Double.parseDouble(b2Line.trim()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static double[] parseRow(String line, int expected) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split(",");
        if (parts.length != expected) {
            return null;
        }
        double[] row = new double[expected];
        for (int i = 0; i < expected; i++) {
            try {
                row[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return row;
    }
}