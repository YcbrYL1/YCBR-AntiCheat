package com.ycbr.anticheat.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从旋转序列反推鼠标灵敏度（借鉴 MX SensitivityProcessor）。
 * 有效区间 [20,150]，区间外检测不执行，避免不同 DPI/灵敏度误杀。
 */
public final class SensitivityProcessor {

    private static final double MIN_SENS = 20.0;
    private static final double MAX_SENS = 150.0;
    private static final double TAU = Math.PI * 2.0;

    public double calculateSensitivity(List<Double> rotations) {
        if (rotations == null || rotations.size() < 4) return -1.0;
        // 相邻增量排序后取中位数作为 GCD 步长（抗噪声）
        List<Double> deltas = new ArrayList<Double>(rotations.size() - 1);
        for (int i = 1; i < rotations.size(); i++) {
            double diff = Math.abs(rotations.get(i) - rotations.get(i - 1));
            if (diff < 1e-9) continue;
            deltas.add(diff);
        }
        if (deltas.size() < 3) return -1.0;
        Collections.sort(deltas);
        double step = deltas.get(deltas.size() / 2); // 中位数
        double sens = TAU / step;
        if (sens < MIN_SENS || sens > MAX_SENS) return -1.0;
        return sens;
    }

    public boolean inRange(double sensitivity) {
        return sensitivity >= MIN_SENS && sensitivity <= MAX_SENS;
    }
}
