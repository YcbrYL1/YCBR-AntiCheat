package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class ProtocolCheck extends Check {

    public static final int ACTION_RIDING_JUMP = 5;

    public ProtocolCheck(AntiCheatManager manager) {
        super(CheckType.BADPACKET, manager);
    }

    public void reportInvalidPosition(PlayerData data, String x, String y, String z) {
        if (!isEnabled()) {
            return;
        }
        flag(data, "InvalidPosition", "position " + x + "," + y + "," + z);
    }

    public void onSteerVehicle(PlayerData data, float forward, float sideways, boolean jump, boolean unmount) {
        if (!isEnabled()) {
            return;
        }
        if (Math.abs(forward) > 0.98F || Math.abs(sideways) > 0.98F) {
            flag(data, "Vehicle", "impossible input forward=" + forward + " sideways=" + sideways);
            data.steerVehicleStreak = 0;
            return;
        }
        long graceMs = si("vehicle-grace-ms", 2000, 1500);
        if (data.lastVehicleTime > 0L && System.currentTimeMillis() - data.lastVehicleTime < graceMs) {
            data.steerVehicleStreak = 0;
            return;
        }
        if (unmount || data.inVehicle) {
            data.steerVehicleStreak = 0;
            return;
        }
        data.steerVehicleStreak++;
        if (data.steerVehicleStreak >= (isStrict() ? 2 : 3)) {
            data.steerVehicleStreak = 0;
            flag(data, "Vehicle", "steer while not in vehicle");
        }
    }

    public void onRidingJump(PlayerData data, long now) {
        if (!isEnabled()) {
            return;
        }
        if (data.inVehicle) {
            data.lastRidingJumpTime = 0L;
            return;
        }
        if (data.lastVehicleTime > 0L && now - data.lastVehicleTime < si("vehicle-grace-ms", 2000, 1500)) {
            data.lastRidingJumpTime = 0L;
            return;
        }
        if (data.lastRidingJumpTime > 0L && now - data.lastRidingJumpTime > 100L) {
            data.lastRidingJumpTime = 0L;
            flag(data, "Vehicle", "horse jump while not riding");
            return;
        }
        data.lastRidingJumpTime = now;
    }

    public void onDigFace(PlayerData data, int status, int face) {
        if (!isEnabled()) {
            return;
        }
        if (status == 0 && (face < 0 || face > 5)) {
            flag(data, "InvalidBreak", "face=" + face);
        }
    }

    public void onHeldItemSlot(PlayerData data, int slot) {
        if (!isEnabled()) {
            return;
        }
        if (slot < 0) {
            flag(data, "BadPacketsO", "negative slot=" + slot);
        } else if (slot > 8) {
            flag(data, "BadPacketsQ", "slot=" + slot);
        }
    }

    public void onKeepAlive(PlayerData data, long id) {
        if (!isEnabled()) {
            return;
        }
        if (data.lastKeepAliveId >= 0L && id == data.lastKeepAliveId) {
            flag(data, "BadPacketsT", "duplicate id=" + id);
        } else if (data.lastKeepAliveId > 0L && id == 0L) {
            flag(data, "BadPacketsU", "zero id");
        }
        data.lastKeepAliveId = id;
    }
}