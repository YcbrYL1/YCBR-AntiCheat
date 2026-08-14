package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class SprintCheck extends Check {

    private static final int ACTION_START_SPRINT = 3;
    private static final int ACTION_STOP_SPRINT = 4;

    public SprintCheck(AntiCheatManager manager) {
        super(CheckType.SPRINT, manager);
    }

    public void checkAction(PlayerData data, int action, int blockedStates) {
        if (!isEnabled()) {
            return;
        }
        if (data.creative || data.flying || data.inVehicle || data.dead) {
            return;
        }
        if (action != ACTION_START_SPRINT && action != ACTION_STOP_SPRINT) {
            return;
        }
        long now = System.currentTimeMillis();
        if (action == ACTION_START_SPRINT && SprintLogic.isIllegalFlip(blockedStates)) {
            if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
                flag(data, "Sprint", "sprint in blocked state");
            }
        }
        if (data.lastSprintAction != 0 && data.lastSprintAction != action
                && now - data.lastSprintActionTime < si("max-flip-gap-ms", 40, 30)
                && SprintLogic.isIllegalFlip(blockedStates)) {
            if (++data.sprintFlipCount >= si("flips-to-flag", 3, 2)) {
                data.sprintFlipCount = 0;
                if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
                    flag(data, "Sprint", "sprint state flips x" + si("flips-to-flag", 3, 2));
                }
            }
        } else {
            data.sprintFlipCount = 0;
            drain(data, "sprint", 0.1D);
        }
        data.lastSprintAction = action;
        data.lastSprintActionTime = now;
    }
}
