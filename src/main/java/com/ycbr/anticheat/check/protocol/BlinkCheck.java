package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class BlinkCheck extends Check {

    private final BlinkLogic replayLogic = new BlinkLogic(i("replay-burst.window", 40),
            si("replay-burst.min-silence-ms", 1000, 1000),
            i("replay-burst.min-burst-packets", 8),
            si("replay-burst.max-interval-ms", 25, 25));
    private long logicLastSeen;

    public BlinkCheck(AntiCheatManager manager) {
        super(CheckType.BLINK, manager);
    }

    public void onTick(PlayerData data, long now) {
        if (!isEnabled()) {
            return;
        }
        if (data.dead || data.inVehicle || data.creative) {
            return;
        }
        long joined = data.joinedMillis == 0L ? now : data.joinedMillis;
        if (now - joined < 5000L) {
            return;
        }
        if (now - data.lastTeleportTime < 1000L) {
            return;
        }
        if (manager.getMainHandler().getTps() < d("min-tps", 15D)) {
            return;
        }
        if (data.lastPositionMillis <= 0L) {
            return;
        }

        long silence = now - data.lastPositionMillis;
        boolean livePong = data.transaction != null
                && data.transaction.lastPongTime() > data.lastPositionMillis
                && now - data.transaction.lastPongTime() < si("pong-live-ms", 1500, 1000);

        long maxSilence;
        if (livePong) {
            // 核心判定：客户端持续回复事务 pong（网络活着）却超过阈值不发移动包
            // = 囤包重放（Blink），与 ping 完全解耦——高 ping 玩家照样有连续 pong。
            maxSilence = si("max-silence-ms", 2000, 1000);
        } else {
            // 兜底：事务未初始化或 pong 也已停止（客户端整体断流），
            // 沿用超时 + ping 补偿的老逻辑。
            maxSilence = si("max-silence-ms", 3000, 2000) + data.ping;
        }
        if (isSubEnabled("replay-burst")) {
            if (data.lastPositionMillis != logicLastSeen) {
                // 有位置包到达：喂到达间隔（-1 = 首包，跳过 burst 判定）
                if (data.lastMoveIntervalMs > 0L
                        && replayLogic.feed(data.lastMoveIntervalMs, livePong)) {
                    data.lastBlinkFlagTime = now;
                    if (bump(data, "blink-replay", 1D, i("replay-burst.vl-before-flag", 6))) {
                        flag(data, "BlinkReplay", "silence+burst replay, interval="
                                + data.lastMoveIntervalMs + "ms");
                    }
                }
                logicLastSeen = data.lastPositionMillis;
            } else {
                replayLogic.tick(50L, livePong);
            }
        }
        if (silence <= maxSilence) {
            checkInteractSilence(data, now, silence);
            return;
        }
        if (now - data.lastBlinkFlagTime < si("cooldown-ms", 5000, 3000)) {
            return;
        }
        data.lastBlinkFlagTime = now;
        if (bump(data, "blink", 1D, i("vl-before-flag", 2))) {
            flag(data, "Blink", (livePong ? "silent=" : "no packet for ") + silence + "ms"
                    + (livePong ? " with live pong" : ""));
        }
    }

    /**
     * P2-3 Blink 包序扩展（对齐 Grim PacketOrder* 场景之一）：交互包活着
     * （攻击/放置/挖掘持续到达）但移动包断流 = 只囤移动包的选择性 Blink。
     *
     * <p>安全性：原版 1.8 客户端有 20-tick 位置心跳（静止也每秒强制发 POSITION），
     * 阈值取心跳周期的 1.5 倍（1500ms）确保正常客户端永不命中；交互活跃窗口取
     * 300ms（两次攻击/放置间隔内），要求交互密集才计数。静止站桩玩家位置心跳
     * 每 1000ms 刷新 lastPositionMillis → silence 永远到不了阈值。</p>
     */
    private void checkInteractSilence(PlayerData data, long now, long silence) {
        if (!isSubEnabled("interact-silence")) {
            return;
        }
        if (data.lastInteractMillis <= 0L) {
            return;
        }
        long interactLive = si("interact-silence.interact-live-ms", 300, 300);
        if (now - data.lastInteractMillis > interactLive) {
            return; // 交互也停了：整体断流，交给核心判定
        }
        long minSilence = si("interact-silence.min-silence-ms", 1500, 1200);
        if (silence < minSilence) {
            return;
        }
        if (now - data.lastBlinkFlagTime < si("cooldown-ms", 5000, 3000)) {
            return;
        }
        data.lastBlinkFlagTime = now;
        if (bump(data, "blink-interact", 1D, i("interact-silence.vl-before-flag", 3))) {
            flag(data, "BlinkInteract", "interact alive but no move for " + silence + "ms");
        }
    }
}