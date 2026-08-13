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
        long silence = now - data.lastPositionMillis;
        long maxSilence = si("max-silence-ms", 2000, 1500) + data.ping;
        if (data.lastPositionMillis > 0L && silence > maxSilence) {
            if (now - data.lastBlinkFlagTime < si("cooldown-ms", 5000, 3000)) {
                return;
            }
            data.lastBlinkFlagTime = now;
            if (bump(data, "blink", 1D, i("vl-before-flag", 2))) {
                flag(data, "Blink", "no position packet for " + silence + "ms");
            }
        }
    }
}
