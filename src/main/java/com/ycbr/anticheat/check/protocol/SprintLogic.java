package com.ycbr.anticheat.check.protocol;

/** Sprint 状态合规判定（对齐 Grim 7 类禁止疾跑场景，1.8.8 无鞘翅故 6 类）。 */
public final class SprintLogic {

    public static final int STATE_HUNGRY = 1;
    public static final int STATE_SNEAKING = 2;
    public static final int STATE_USING_ITEM = 4;
    public static final int STATE_BLINDED = 8;
    public static final int STATE_HEAD_BLOCKED = 16;
    public static final int STATE_IN_LIQUID = 32;

    private SprintLogic() {
    }

    public static boolean canSprint(int blockedStates) {
        return blockedStates == 0;
    }

    public static boolean isIllegalFlip(int blockedStates) {
        return !canSprint(blockedStates);
    }
}