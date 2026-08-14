package com.ycbr.anticheat.check.combat.aim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class AimStatsLogicTest {

    private static final double ENTROPY_MAX = 1.5;
    private static final double IQR_MIN = 0.0005;
    private static final double KS_MIN_UNIFORM = 0.15;
    private static final int JIFF_LEN = 3;
    private static final int JIFF_MAX = 2;
    private static final double ZSCORE = 4.0;
    private static final double KURT_MAX = -0.5;

    private AimStatsLogic logic() {
        return new AimStatsLogic();
    }

    private List<String> evaluate(List<Double> xs) {
        return logic().evaluate(xs, ENTROPY_MAX, IQR_MIN, KS_MIN_UNIFORM, JIFF_LEN, JIFF_MAX,
                ZSCORE, KURT_MAX);
    }

    private List<Double> constantSteps() {
        List<Double> xs = new ArrayList<Double>();
        for (int i = 0; i < 40; i++) {
            xs.add(0.5D);
        }
        return xs;
    }

    private List<Double> repeatingPattern() {
        List<Double> xs = new ArrayList<Double>();
        double[] pattern = { 0.3D, 0.7D, 1.1D };
        for (int i = 0; i < 20; i++) {
            for (double d : pattern) {
                xs.add(d);
            }
        }
        return xs;
    }

    /** 真人瞄准：尖峰分布（小步长为主，含偶发大转），符合高斯样分布。 */
    private List<Double> organic(boolean seeded) {
        List<Double> xs = new ArrayList<Double>();
        Random rnd = seeded ? new Random(42L) : new Random();
        for (int i = 0; i < 60; i++) {
            double v = 0.1D + Math.abs(rnd.nextGaussian() * 0.4D) + rnd.nextDouble() * 0.6D;
            if (v >= 30.0D) {
                v = 29.9D;
            }
            xs.add(v);
        }
        return xs;
    }

    /** 随机化修饰（aimbot jitter）：把增量均匀抹平。 */
    private List<Double> uniformized() {
        List<Double> xs = new ArrayList<Double>();
        Random rnd = new Random(7L);
        for (int i = 0; i < 60; i++) {
            xs.add(0.1D + rnd.nextDouble() * 5.0D);
        }
        return xs;
    }

    @Test
    void coldStart_neverSignals() {
        List<Double> xs = new ArrayList<Double>();
        for (int i = 0; i < 20; i++) {
            xs.add(0.5D);
        }
        assertTrue(evaluate(xs).isEmpty(), "below MIN_SAMPLES should be silent");
    }

    @Test
    void constantSteps_flagsEntropyIqrKurtosis() {
        List<String> hits = evaluate(constantSteps());
        assertTrue(hits.contains("entropy"), "constant steps should have low entropy");
        assertTrue(hits.contains("iqr"), "constant steps should have tiny IQR");
        assertTrue(hits.contains("kurtosis"), "constant steps should have very negative kurtosis");
    }

    @Test
    void repeatingPattern_flagsJiff() {
        List<String> hits = evaluate(repeatingPattern());
        assertTrue(hits.contains("jiff"), "repeating 3-step pattern should flag jiff");
    }

    @Test
    void uniformized_flagsKs() {
        List<String> hits = evaluate(uniformized());
        assertTrue(hits.contains("ks"), "uniform spread should flag ks (too uniform)");
    }

    @Test
    void organicAim_rarelyFlags() {
        int flagged = 0;
        for (int round = 0; round < 10; round++) {
            List<String> hits = evaluate(organic(true));
            if (!hits.isEmpty()) {
                flagged++;
            }
        }
        assertTrue(flagged <= 2, "organic aim should rarely trigger stat signals, got " + flagged);
    }

    @Test
    void organic_neverFlaggedAsKs() {
        assertFalse(evaluate(organic(true)).contains("ks"),
                "peaked human aim should not look uniform");
    }
}