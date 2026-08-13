package com.ycbr.anticheat.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class StatisticsTest {

    @Test
    void average_handlesEmptyAndNormal() {
        assertEquals(0.0, Statistics.average(new ArrayList<Double>()), 1e-9);
        assertEquals(2.0, Statistics.average(Arrays.asList(1.0, 2.0, 3.0)), 1e-9);
    }

    @Test
    void varianceAndStdDev() {
        List<Double> xs = Arrays.asList(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        double var = Statistics.variance(xs);
        assertTrue(var > 4.0 && var < 5.0, "variance=" + var);
        assertEquals(Math.sqrt(var), Statistics.standardDeviation(xs), 1e-9);
    }

    @Test
    void shannonEntropy_lowForConstantHighForUniform() {
        List<Double> constant = Arrays.asList(1.0, 1.0, 1.0, 1.0);
        List<Double> uniform = Arrays.asList(1.0, 2.0, 3.0, 4.0);
        double hConst = Statistics.shannonEntropy(constant);
        double hUni = Statistics.shannonEntropy(uniform);
        assertTrue(hConst < 0.05, "constant entropy=" + hConst);
        assertTrue(hUni > 1.9, "uniform entropy=" + hUni);
    }

    @Test
    void kurtosis_negativeForMechanicalPattern() {
        List<Double> mechanical = Arrays.asList(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);
        List<Double> organic = Arrays.asList(50.0, 62.0, 41.0, 70.0, 33.0, 55.0, 48.0, 65.0);
        double kMechanical = Statistics.kurtosis(mechanical);
        double kOrganic = Statistics.kurtosis(organic);
        assertTrue(kMechanical < 0.0, "mechanical kurtosis=" + kMechanical);
        assertTrue(kOrganic > kMechanical);
    }

    @Test
    void iqr() {
        List<Double> xs = Arrays.asList(1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0);
        assertEquals(6.0, Statistics.iqr(xs), 1e-9); // Q1=3, Q3=9
    }

    @Test
    void zScoreOutliers_detectsFarValue() {
        List<Double> xs = new ArrayList<Double>(Arrays.asList(10.0, 12.0, 11.0, 13.0, 10.5, 11.5, 12.5, 60.0));
        List<Double> outliers = Statistics.zScoreOutliers(xs, 3.0);
        assertEquals(1, outliers.size());
        assertEquals(60.0, outliers.get(0), 1e-9);
    }

    @Test
    void kolmogorovSmirnov_uniformVsConstant() {
        List<Double> uniform = new ArrayList<Double>();
        for (int i = 0; i < 100; i++) uniform.add((double) (i % 10));
        List<Double> constant = new ArrayList<Double>(Arrays.asList(1.0, 1.0, 1.0, 1.0));
        double dUniform = Statistics.kolmogorovSmirnov(uniform, uniform); // 同分布 → 小
        double dConst = Statistics.kolmogorovSmirnov(uniform, constant); // 异分布 → 大
        assertTrue(dUniform < 0.05);
        assertTrue(dConst > 0.5);
    }

    @Test
    void jiffDelta_countsRepeatedSequences() {
        List<Double> xs = Arrays.asList(1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0, 2.0, 3.0);
        assertTrue(Statistics.jiffDelta(xs, 3) >= 2);
    }
}
