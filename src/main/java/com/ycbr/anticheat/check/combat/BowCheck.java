package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class BowCheck extends Check {

    private static final double CHARGE_MS = 800.0D;

    public BowCheck(AntiCheatManager manager) {
        super(CheckType.INSTANTBOW, manager);
    }

    @Override
    protected void onBowRelease(PlayerData data, float force, long now) {
        if (!isEnabled()) {
            return;
        }
        long pull = data.bowPullTime;
        if (pull <= 0L) {
            return;
        }
        if (force < d("bow-force-min", 0.15D)) {
            data.bowPullTime = 0L;
            return;
        }
        long elapsed = now - pull;
        double decayed = 1.0D - (double) force;
        double required = CHARGE_MS * (1.0D - decayed * decayed) - si("tolerance-ms", 130, 60);
        if (elapsed < required) {
            if (now - data.lastBowFlagTime >= si("cooldown-ms", 5000, 3000)) {
                data.lastBowFlagTime = now;
                if (bump(data, "instantbow", 1D, i("vl-before-flag", 2))) {
                    flag(data, "InstantBow", "released in " + elapsed + "ms, min " + (long) Math.ceil(required)
                            + "ms at force=" + force);
                }
            }
        }
        data.bowPullTime = 0L;
    }
}