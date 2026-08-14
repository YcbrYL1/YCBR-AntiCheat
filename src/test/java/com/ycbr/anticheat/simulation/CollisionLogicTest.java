package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CollisionLogicTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    private static void assertPair(double expectedX, double expectedZ, double[] actual, String msg) {
        assertEquals(expectedX, actual[0], 1e-9, msg + " (dx)");
        assertEquals(expectedZ, actual[1], 1e-9, msg + " (dz)");
    }

    @Test
    void noWalls_unchanged() {
        double[] r = PredictionEngine.applyCollision(0.3, 0.1, 0.0f, INF, INF, INF);
        assertPair(0.3, 0.1, r, "无墙应原样返回");
    }

    @Test
    void forwardWall_truncatesToWall() {
        // yaw=0：前进=+X
        double[] r = PredictionEngine.applyCollision(0.5, 0.0, 0.0f, 0.2, INF, INF);
        assertPair(0.2, 0.0, r, "前方墙应把前进分量截断到墙距");
    }

    @Test
    void rightWall_truncatesRight() {
        // yaw=0：右=+Z
        double[] r = PredictionEngine.applyCollision(0.0, 0.4, 0.0f, INF, INF, 0.15);
        assertPair(0.0, 0.15, r, "右侧墙应截断右向分量");
    }

    @Test
    void leftWall_truncatesLeft() {
        // yaw=0：左=-Z
        double[] r = PredictionEngine.applyCollision(0.0, -0.4, 0.0f, INF, 0.15, INF);
        assertPair(0.0, -0.15, r, "左侧墙应截断左向分量");
    }

    @Test
    void diagonalCorner_bothAxesTruncated() {
        // 斜向撞墙角：两轴同时截断（滑动）
        double[] r = PredictionEngine.applyCollision(0.5, 0.5, 0.0f, 0.3, INF, 0.2);
        assertPair(0.3, 0.2, r, "斜撞墙角应两轴都截断");
    }

    @Test
    void negativeForward_notAffectedByForwardWall() {
        // 后退（fwd<0）不受前方墙影响
        double[] r = PredictionEngine.applyCollision(-0.3, 0.0, 0.0f, 0.2, INF, INF);
        assertPair(-0.3, 0.0, r, "后退不应被前方墙截断");
    }

    @Test
    void wallBeyondLimit_ignored() {
        // 探测滞后安全：墙距 ≥ 0.65 视为无墙，不截断
        double[] r = PredictionEngine.applyCollision(0.5, 0.0, 0.0f, 0.65, INF, INF);
        assertPair(0.5, 0.0, r, "墙距达到上限应忽略");
        double[] r2 = PredictionEngine.applyCollision(0.7, 0.0, 0.0f, 0.64, INF, INF);
        assertPair(0.64, 0.0, r2, "墙距在上限内应截断");
    }

    @Test
    void rotatedYaw_truncatesAlongFacing() {
        // yaw=90：前进=+Z，右=-X
        double[] r = PredictionEngine.applyCollision(0.2, 0.5, 90.0f, 0.3, INF, INF);
        assertPair(0.2, 0.3, r, "yaw=90 时前方墙应截断 +Z 分量");
        double[] r2 = PredictionEngine.applyCollision(-0.5, 0.2, 90.0f, INF, INF, 0.1);
        assertTrue(r2[0] > -0.2 && r2[0] < 0.0, "yaw=90 右侧墙应截断 -X 分量，实际 " + r2[0]);
    }
}