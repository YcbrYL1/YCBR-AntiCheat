package com.ycbr.anticheat.data.context;

import com.ycbr.anticheat.data.PlayerData;

public final class MoveContext {

    public final PlayerData data;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final long arrivalTime;

    public MoveContext(PlayerData data, double x, double y, double z, float yaw, float pitch, long arrivalTime) {
        this.data = data;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.arrivalTime = arrivalTime;
    }
}