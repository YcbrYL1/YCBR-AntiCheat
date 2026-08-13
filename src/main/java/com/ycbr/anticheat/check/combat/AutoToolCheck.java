package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class AutoToolCheck extends Check {

    public AutoToolCheck(AntiCheatManager manager) {
        super(CheckType.AUTOTOOL, manager);
    }

    public void onSlotChange(PlayerData data) {
        if (!isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!data.digging) {
            return;
        }
        if (now - data.lastDigStartTime >= si("dig-switch-window-ms", 200, 400)) {
            return;
        }
        if (now - data.lastAutoToolFlagTime < si("cooldown-ms", 150000, 60000)) {
            return;
        }
        if (bump(data, "autotool", 1D, i("vl-before-flag", 3))) {
            data.lastAutoToolFlagTime = now;
            flag(data, "AutoTool", "slot switch " + (now - data.lastDigStartTime)
                    + "ms after dig start");
        }
    }
}