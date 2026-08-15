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

    /**
     * 权威禁止状态（客户端在该状态下不可能合法发出 START_SPRINT）：
     * 饥饿/潜行/使用物品/水中——对齐 1.8 客户端 EntityPlayerSP 的疾跑取消条件
     * （food≤6 / sneaking / isUsingItem / isInWater）。
     *
     * <p>【误判修复】1.8 中**头顶挡（blockBoxedIn）与失明（BLINDNESS）不禁疾跑**——
     * 2 格高走廊疾跑、失明时疾跑均合法（客户端无此限制），故从权威集合移除。
     * 头顶挡若曾由探测滞后触发，也无需 flip 路径兜底（它本就是合法状态）。</p>
     */
    public static boolean isAuthoritative(int blockedStates) {
        return (blockedStates
                & (STATE_HUNGRY | STATE_SNEAKING | STATE_USING_ITEM | STATE_IN_LIQUID)) != 0;
    }

    /** blocked 状态名（flag 详情，实机日志可定位具体触发源）。 */
    public static String blockedStateName(int blockedStates) {
        StringBuilder sb = new StringBuilder();
        if ((blockedStates & STATE_HUNGRY) != 0) {
            sb.append("Hungry,");
        }
        if ((blockedStates & STATE_SNEAKING) != 0) {
            sb.append("Sneaking,");
        }
        if ((blockedStates & STATE_USING_ITEM) != 0) {
            sb.append("UsingItem,");
        }
        if ((blockedStates & STATE_BLINDED) != 0) {
            sb.append("Blinded,");
        }
        if ((blockedStates & STATE_HEAD_BLOCKED) != 0) {
            sb.append("HeadBlocked,");
        }
        if ((blockedStates & STATE_IN_LIQUID) != 0) {
            sb.append("InLiquid,");
        }
        return sb.length() == 0 ? "None" : sb.substring(0, sb.length() - 1);
    }
}