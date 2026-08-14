package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class BlinkCheck extends Check {

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
        if (silence <= maxSilence) {
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
}