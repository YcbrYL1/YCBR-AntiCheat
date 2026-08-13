package com.ycbr.anticheat.data.context;

import com.ycbr.anticheat.data.PlayerData;

public final class PlaceContext {

    public final PlayerData data;
    public final int blockX;
    public final int blockY;
    public final int blockZ;
    public final int direction;
    public final long time;
    public final boolean hasCursor;
    public final double cursorX;
    public final double cursorY;
    public final double cursorZ;

    public PlaceContext(PlayerData data, int blockX, int blockY, int blockZ, int direction, long time,
            boolean hasCursor, double cursorX, double cursorY, double cursorZ) {
        this.data = data;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.direction = direction;
        this.time = time;
        this.hasCursor = hasCursor;
        this.cursorX = cursorX;
        this.cursorY = cursorY;
        this.cursorZ = cursorZ;
    }
}