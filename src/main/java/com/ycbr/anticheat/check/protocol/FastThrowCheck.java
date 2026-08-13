package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class FastThrowCheck extends Check {

    private static final long SAME_TICK_MS = 50L;

    public FastThrowCheck(AntiCheatManager manager) {
        super(CheckType.FASTTHROW, manager);
    }

    @Override
    protected void onThrow(PlayerData data, long now) {
        if (!isEnabled()) {
            return;
        }
        if (data.ping > i("max-ping", 300)) {
            data.fastThrowCount = 0;
            data.lastThrowTime = 0L;
            return;
        }
        if (data.lastThrowTime > 0L && now - data.lastThrowTime < SAME_TICK_MS) {
            data.fastThrowCount++;
        } else {
            data.fastThrowCount = 1;
        }
        data.lastThrowTime = now;
        if (data.fastThrowCount >= si("count", 3, 2)) {
            long flagTime = data.lastFastThrowFlagTime;
            if (now - flagTime > si("cooldown-ms", 5000, 3000)) {
                data.lastFastThrowFlagTime = now;
                if (bump(data, "fastthrow", 1D, i("vl-before-flag", 1))) {
                    flag(data, "FastThrow", "repeated throw within " + SAME_TICK_MS + "ms");
                }
            }
            data.fastThrowCount = 0;
        }
    }
}