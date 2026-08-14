package com.ycbr.anticheat.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimpleMLPTest {

    @Test
    void forward_outputInSigmoidRange() {
        SimpleMLP mlp = new SimpleMLP(8, 8);
        double out = mlp.forward(new double[] { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0 });
        assertTrue(out > 0.0D && out < 1.0D, "sigmoid output must be in (0,1), got " + out);
    }

    @Test
    void loadWeights_changesOutput() {
        SimpleMLP mlp = new SimpleMLP(2, 4);
        double before = mlp.forward(new double[] { 1.0, 1.0 });
        mlp.loadWeights(
                new double[][] { { 10.0, 0.0 }, { 0.0, 10.0 }, { 10.0, 10.0 }, { -10.0, -10.0 } },
                new double[] { 0.0, 0.0, 0.0, 0.0 },
                new double[] { 1.0, 1.0, 1.0, -1.0 },
                0.0);
        double after = mlp.forward(new double[] { 1.0, 1.0 });
        assertEquals(1.0D, after, 1e-9, "strong positive features should saturate to 1");
        assertTrue(Math.abs(after - before) > 1e-6, "weights must change output");
    }

    @Test
    void loadFromFile_parsesWeights(@TempDir File dir) throws Exception {
        File w = new File(dir, "weights.txt");
        Files.write(w.toPath(),
                ("1.0,0.0\n0.0,1.0\n1.0,1.0\n-1.0,-1.0\n" // w1: 4x2
                        + "0.0,0.0,0.0,0.0\n"              // b1: 4
                        + "1.0,1.0,1.0,-1.0\n"              // w2: 4
                        + "0.0\n").getBytes(StandardCharsets.UTF_8));
        SimpleMLP mlp = new SimpleMLP(2, 4);
        assertTrue(mlp.loadFromFile(w));
        // h = ReLU(1), ReLU(1), ReLU(2), ReLU(-2) → out = 1+1+2 = 4 → sigmoid(4) ≈ 0.982
        assertEquals(0.9820137900379085D, mlp.forward(new double[] { 1.0, 1.0 }), 1e-9);
    }

    @Test
    void loadFromFile_missingOrMalformed_returnsFalse() {
        SimpleMLP mlp = new SimpleMLP(2, 4);
        assertFalse(mlp.loadFromFile(new File("Z:/definitely/missing.txt")));
    }
}