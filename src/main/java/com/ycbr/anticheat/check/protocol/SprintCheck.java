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
        // 直判路径只认"权威状态"（饥饿/潜行/用物品/失明）：客户端在这些状态下不可能
        // 合法发 START_SPRINT。HeadBlocked/InLiquid 来自主线程探测（滞后敏感），只走
        // 下方连续翻转路径，避免"走出水面/矮隧道瞬间"被滞后数据单次误判。
        if (action == ACTION_START_SPRINT && SprintLogic.isIllegalFlip(blockedStates)
                && SprintLogic.isAuthoritative(blockedStates)) {
            if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
                flag(data, "Sprint", "sprint in blocked state: "
                        + SprintLogic.blockedStateName(blockedStates));
            }
        }
        if (data.lastSprintAction != 0 && data.lastSprintAction != action
                && now - data.lastSprintActionTime < si("max-flip-gap-ms", 40, 30)
                && SprintLogic.isIllegalFlip(blockedStates)) {
            if (++data.sprintFlipCount >= si("flips-to-flag", 3, 2)) {
                data.sprintFlipCount = 0;
                if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
                    flag(data, "Sprint", "sprint state flips x"
                            + si("flips-to-flag", 3, 2) + ": "
                            + SprintLogic.blockedStateName(blockedStates));
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
