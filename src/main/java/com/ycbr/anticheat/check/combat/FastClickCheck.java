package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;

public final class FastClickCheck extends Check {

    private static final long CLEANUP_WINDOW_MS = 1000L;

    public FastClickCheck(AntiCheatManager manager) {
        super(CheckType.FASTCLICK, manager);
    }

    @Override
    protected void onAttack(AttackContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        long now = ctx.time;
        if (data.ping > i("max-ping", 200)) {
            return;
        }
        data.attackTimes.add(now);
        data.attackTimes.removeIf(t -> t < now - CLEANUP_WINDOW_MS);
        int window = si("burst-window-ms", 200, 250);
        int burst = 0;
        for (Long t : data.attackTimes) {
            if (now - t <= window) {
                burst++;
            }
        }
        int maxBurst = si("burst-count", 6, 5);
        if (burst >= maxBurst) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - data.lastFastClickFlagTime >= si("cooldown-ms", 5000, 3000)) {
                data.lastFastClickFlagTime = nowMs;
                if (bump(data, "fastclick", 1D, i("vl-before-flag", 2))) {
                    flag(data, "FastClick", burst + " attacks in " + window + "ms (cap " + maxBurst + ")");
                }
            }
        } else {
            drain(data, "fastclick", 0.1D);
        }
    }
}