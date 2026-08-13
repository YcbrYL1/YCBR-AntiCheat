package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.util.MathUtil;

public final class WrongTurnCheck extends Check {

    public WrongTurnCheck(AntiCheatManager manager) {
        super(CheckType.WRONGTURN, manager);
    }

    public void checkRotation(PlayerData data, float yaw, float pitch) {
        if (!isEnabled()) {
            return;
        }
        if (data.creative || data.flying || data.inVehicle || data.dead) {
            return;
        }
        if (Float.isNaN(pitch) || pitch > 90F || pitch < -90F
                || Float.isNaN(yaw) || Float.isInfinite(yaw) || Float.isInfinite(pitch)) {
            if (bump(data, "wrongturn", 1D, i("vl-before-flag", 1))) {
                flag(data, "WrongTurn", "pitch=" + (Float.isNaN(pitch) ? "NaN" : MathUtil.round(pitch, 1))
                        + " yaw=" + (Float.isNaN(yaw) ? "NaN"
                                : Float.isInfinite(yaw) ? "Inf" : MathUtil.round(yaw, 1)));
            }
        } else {
            drain(data, "wrongturn", 0.1D);
        }
    }
}
