package com.ycbr.anticheat.snapshot;

import java.util.UUID;

public final class EntitySnapshot {

    public final int id;
    public final UUID uuid;
    public final String entityType;
    public final double x;
    public final double y;
    public final double z;
    public final double width;
    public final double height;
    public final long createdMillis;
    public final double vx;
    public final double vy;
    public final double vz;

    public EntitySnapshot(int id, UUID uuid, String entityType, double x, double y, double z, double width,
            double height, long createdMillis, double vx, double vy, double vz) {
        this.id = id;
        this.uuid = uuid;
        this.entityType = entityType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        this.createdMillis = createdMillis;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }
}