package com.ycbr.anticheat.check.protocol;

/**
 * Blink 重放 burst 纯逻辑（无 Bukkit 依赖，可单测）。
 *
 * <p>判定：静默期（有活体 pong 但无移动包，由 BlinkCheck 每 tick 喂入）
 * 结束后突发补发位置包（间隔 &lt; burst-max-interval-ms 且连续 N 包）=
 * 囤包重放确认。静默期是前提：网络拥塞恢复的 burst 无静默期，不算。</p>
 */
public final class BlinkLogic {

    private final long[] intervals;
    private final int window;
    private final long minSilenceMs;
    private final int minBurstPackets;
    private final long maxIntervalMs;
    private int head;
    private int count;
    private long silentMs;
    private int burstWindowRemaining;

    public BlinkLogic(int window) {
        this(window, 1000L, 8, 25L);
    }

    public BlinkLogic(int window, long minSilenceMs, int minBurstPackets, long maxIntervalMs) {
        this.window = window;
        this.intervals = new long[window];
        this.minSilenceMs = minSilenceMs;
        this.minBurstPackets = minBurstPackets;
        this.maxIntervalMs = maxIntervalMs;
    }

    /** 无移动包的一 tick：累计静默时长（pongActive=true 表示网络活着）。 */
    public void tick(long tickMs, boolean pongActive) {
        if (pongActive) {
            silentMs += tickMs;
        } else {
            silentMs = 0L; // pong 也停了 → 整体断流，不视为囤包静默
        }
    }

    /**
     * 记录一次位置包到达间隔（ms）。
     * 返回 true = 判定为重放 burst（静默达标 + 突发补发达标）。
     */
    public boolean feed(long intervalMs, boolean pongActive) {
        long silentBefore = silentMs;
        silentMs = 0L; // 位置包到达 → 静默期结束
        if (silentBefore >= minSilenceMs) {
            // 静默达标后，接下来若干包视为可能的补发窗口（burst 判定需连续 N 包）
            burstWindowRemaining = Math.max(burstWindowRemaining, minBurstPackets);
        }
        intervals[head] = intervalMs;
        head = (head + 1) % window;
        if (count < window) {
            count++;
        }

        if (burstWindowRemaining <= 0) {
            return false;
        }
        burstWindowRemaining--;
        if (count < minBurstPackets) {
            return false;
        }
        // 最近 min-burst-packets 个包间隔全部低于阈值
        for (int i = 0; i < minBurstPackets; i++) {
            long iv = intervals[(head - 1 - i + window) % window];
            if (iv > maxIntervalMs) {
                return false;
            }
        }
        return true;
    }
}