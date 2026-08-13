package com.ycbr.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SensitivityProcessorTest {

    private List<Double> syntheticRotations(int sensitivity, int n) {
        List<Double> out = new ArrayList<Double>();
        double step = Math.PI * 2 / sensitivity;
        double cur = 0.0;
        for (int i = 0; i < n; i++) {
            cur += step;
            out.add(cur);
        }
        return out;
    }

    @Test
    void calculateSensitivity_recoversIntensity() {
        SensitivityProcessor sp = new SensitivityProcessor();
        for (int sens = 30; sens <= 150; sens += 20) {
            double s = sp.calculateSensitivity(syntheticRotations(sens, 40));
            assertTrue(Math.abs(s - sens) <= sens * 0.2,
                    "sens=" + sens + " got=" + s);
        }
    }

    @Test
    void inRange_only20to150() {
        SensitivityProcessor sp = new SensitivityProcessor();
        assertTrue(sp.inRange(30));
        assertTrue(sp.inRange(150));
        assertFalse(sp.inRange(10));
        assertFalse(sp.inRange(200));
    }
}
