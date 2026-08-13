package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.PlaceContext;
import com.ycbr.anticheat.util.MathUtil;

public final class ScaffoldCheck extends Check {

    public ScaffoldCheck(AntiCheatManager manager) {
        super(CheckType.SCAFFOLD, manager);
    }

    @Override
    protected void onPlace(PlaceContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.inVehicle || data.ping > cfg.maxPing()) {
            return;
        }
        if (!data.movement.initialized) {
            return;
        }

        checkPlaceFields(ctx);
        if (ctx.direction == 255) {
            return;
        }
        checkFootClick(ctx);
        checkPlaceAim(ctx);
        checkFastPlace(ctx);
        if (data.creative) {
            return;
        }
        checkMovePlace(ctx);
        checkRotation(ctx);
        checkCadence(ctx);
        checkColinear(ctx);
        checkGrid45(ctx);
        checkDupRot(ctx);
        int batchSize = si("cadence.batch-size", 8, 6);
        if (data.placePoints.size() >= batchSize) {
            data.placePoints.clear();
        }
    }

    private void checkPlaceFields(PlaceContext ctx) {
        PlayerData data = ctx.data;
        int face = ctx.direction;
        boolean faceOk = (face >= 0 && face <= 5) || face == 255;
        if (!faceOk) {
            if (isSubEnabled("invalid-place")
                    && bump(data, "invalid-place", 1D, i("invalid-place.vl-before-flag", 2))) {
                flag(data, "InvalidPlace", "bad face=" + face);
            }
            return;
        }
        if (!ctx.hasCursor) {
            return;
        }
        double cx = ctx.cursorX;
        double cy = ctx.cursorY;
        double cz = ctx.cursorZ;
        if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz)) {
            if (isSubEnabled("invalid-place")
                    && bump(data, "invalid-place", 1D, i("invalid-place.vl-before-flag", 2))) {
                flag(data, "InvalidPlace", "NaN/Inf cursor");
            }
            return;
        }
        double eps = 1.0E-7D;
        if (cx < -eps || cx > 1.0D + eps || cz < -eps || cz > 1.0D + eps
                || cy < -eps || cy > 1.5D + eps) {
            if (isSubEnabled("fabricated")
                    && bump(data, "fabricated", 1D, i("fabricated.vl-before-flag", 2))) {
                flag(data, "FabricatedPlace", "cursor out of bounds");
            }
        }
    }

    private void checkFootClick(PlaceContext ctx) {
        if (!isSubEnabled("footclick")) {
            return;
        }
        PlayerData data = ctx.data;
        if (ctx.direction != 0) {
            return;
        }
        if (System.currentTimeMillis() - data.lastTeleportTime < 500L) {
            return;
        }
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(data.getUuid());
        if (player == null || player.getWorld() == null) {
            return;
        }
        int by = (int) Math.floor(data.movement.lastY) - 1;
        if (ctx.blockY != by) {
            return;
        }
        int bx = (int) Math.floor(data.movement.lastX);
        int bz = (int) Math.floor(data.movement.lastZ);
        if (Math.abs(ctx.blockX - bx) > 1 || Math.abs(ctx.blockZ - bz) > 1) {
            return;
        }
        if (!player.getWorld().getBlockAt(ctx.blockX, ctx.blockY, ctx.blockZ).getType().isSolid()) {
            return;
        }
        if (bump(data, "footclick", 1D, i("footclick.vl-before-flag", 1))) {
            flag(data, "FootClick", "placed against bottom face of block under feet");
        }
    }

    private void checkPlaceAim(PlaceContext ctx) {
        PlayerData data = ctx.data;
        if (!isSubEnabled("place-aim")) {
            return;
        }
        if (!data.movement.initialized) {
            return;
        }
        double dx = (ctx.blockX + 0.5D) - data.movement.lastX;
        double dy = (ctx.blockY + 0.5D) - data.movement.lastY;
        double dz = (ctx.blockZ + 0.5D) - data.movement.lastZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > 20D || Math.abs(dy) > 20D) {
            return;
        }
        double maxH = sd("place-aim.max-horizontal", 6.5D, 5.0D)
                + data.ping * d("place-aim.ping-expand", 0.004D);
        double maxV = sd("place-aim.max-vertical", 4.0D, 3.5D);
        if (horiz > maxH || Math.abs(dy) > maxV) {
            if (bump(data, "place-aim", 1D, i("place-aim.vl-before-flag", 4))) {
                flag(data, "PlaceAim", "block unreachable horiz=" + MathUtil.round(horiz, 2)
                        + " dy=" + MathUtil.round(dy, 2) + " at=" + ctx.blockX + "," + ctx.blockY + ","
                        + ctx.blockZ + " player=" + MathUtil.round(data.movement.lastX, 1) + ","
                        + MathUtil.round(data.movement.lastY, 1) + "," + MathUtil.round(data.movement.lastZ, 1));
            }
        } else {
            drain(data, "place-aim", 0.05D);
        }
    }

    private void checkFastPlace(PlaceContext ctx) {
        if (!isSubEnabled("fast-place")) {
            return;
        }
        PlayerData data = ctx.data;
        long window = i("fast-place.window-ms", 1000);
        data.placeTimes.add(ctx.time);
        data.placeTimes.removeIf(t -> t < ctx.time - window);
        int placements = data.placeTimes.size();
        int maxCps = si("fast-place.max-cps", 13, 10) + (data.creative ? 5 : 0);
        if (placements > maxCps) {
            if (bump(data, "fast-place", 1D, i("fast-place.vl-before-flag", 6))) {
                flag(data, "FastPlace", "placements/s=" + placements + " max=" + maxCps);
            }
        } else {
            drain(data, "fast-place", 0.05D);
        }
    }

    private void checkMovePlace(PlaceContext ctx) {
        if (!isSubEnabled("move-place")) {
            return;
        }
        PlayerData data = ctx.data;
        if (!data.movement.onGround || data.movement.jumpedThisTick) {
            return;
        }
        double speed = data.movement.distanceXZ;
        double maxSpeed = sd("move-place.max-speed", 0.55D, 0.45D) + data.velocity.horizontal()
                + data.speedLevel * 0.1D;
        if (speed > maxSpeed) {
            if (bump(data, "move-place", 1D, i("move-place.vl-before-flag", 5))) {
                flag(data, "MovePlace", "speed=" + MathUtil.round(speed, 3) + " max=" + MathUtil.round(maxSpeed, 3));
            }
        } else {
            drain(data, "move-place", 0.05D);
        }
    }

    private void checkRotation(PlaceContext ctx) {
        if (!isSubEnabled("rotation")) {
            return;
        }
        PlayerData data = ctx.data;
        if (!data.hasRotation) {
            return;
        }
        int feetY = (int) Math.floor(data.movement.lastY);
        if (ctx.blockY != feetY) {
            drain(data, "rotation", 0.05D);
            return;
        }
        double dx = ctx.blockX + 0.5D - data.movement.lastX;
        double dz = ctx.blockZ + 0.5D - data.movement.lastZ;
        if (Math.sqrt(dx * dx + dz * dz) <= d("rotation.eye-exempt-distance", 0.25D)) {
            drain(data, "rotation", 0.05D);
            return;
        }
        double yawToBlock = MathUtil.yawToTarget(dx, dz);
        double yawDiff = Math.abs(MathUtil.normalizeYaw(data.lastYaw - yawToBlock));
        boolean lookingAway = yawDiff > sd("rotation.max-yaw-diff", 120D, 90D);
        if (lookingAway) {
            int need = Math.max(1, si("rotation.consecutive-away", 8, 4));
            if (++data.rotationAwayStreak < need) {
                drain(data, "rotation", 0.05D);
                return;
            }
            data.rotationAwayStreak = 0;
            if (bump(data, "rotation", 1D, i("rotation.vl-before-flag", 6))) {
                flag(data, "Rotation", "not looking at placed block (" + MathUtil.round(yawDiff, 0)
                        + " deg) x" + need);
            }
        } else {
            data.rotationAwayStreak = 0;
            drain(data, "rotation", 0.05D);
        }
    }

    private void checkCadence(PlaceContext ctx) {
        if (!isSubEnabled("cadence")) {
            return;
        }
        PlayerData data = ctx.data;
        data.placePoints.add(ctx.time, ctx.blockX, ctx.blockZ);
        int batchSize = si("cadence.batch-size", 8, 6);
        if (data.placePoints.size() < batchSize) {
            return;
        }
        long[][] points = data.placePoints.copy();
        long tolerance = si("cadence.tolerance-ms", 10, 5);
        boolean allGrid = true;
        for (int k = 1; k < points.length; k++) {
            long gap = points[k][0] - points[k - 1][0];
            if (Math.abs(gap - 50L) > tolerance) {
                allGrid = false;
                break;
            }
        }
        if (allGrid) {
            if (bump(data, "cadence", 1D, i("cadence.vl-before-flag", 4))) {
                flag(data, "Cadence", "every-tick placements x" + (points.length - 1));
            }
        } else {
            drain(data, "cadence", 0.05D);
        }
    }

    private void checkColinear(PlaceContext ctx) {
        if (!isSubEnabled("colinear")) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.placePoints.size() < (isStrict() ? 4 : 6)) {
            return;
        }
        long[][] points = data.placePoints.copy();
        int x0 = (int) points[0][1];
        int z0 = (int) points[0][2];
        boolean tower = true;
        for (long[] p : points) {
            if (p[1] != x0 || p[2] != z0) {
                tower = false;
                break;
            }
        }
        if (tower) {
            return;
        }
        double firstDx = points[1][1] - points[0][1];
        double firstDz = points[1][2] - points[0][2];
        double firstLen = Math.sqrt(firstDx * firstDx + firstDz * firstDz);
        if (firstLen < 0.85D || firstLen > 1.55D) {
            return;
        }
        boolean colinear = true;
        boolean grid = true;
        for (int k = 2; k < points.length; k++) {
            long gap = points[k][0] - points[k - 1][0];
            if (Math.abs(gap - 50L) > 10L) {
                grid = false;
            }
            double dx = points[k][1] - points[k - 1][1];
            double dz = points[k][2] - points[k - 1][2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.85D || len > 1.55D) {
                colinear = false;
                break;
            }
            double dot = (firstDx * dx + firstDz * dz) / (firstLen * len);
            if (dot < 0.99D) {
                colinear = false;
                break;
            }
        }
        if (colinear && grid) {
            if (bump(data, "colinear", 1D, i("colinear.vl-before-flag", 4))) {
                flag(data, "Colinear", "linear grid bridge x" + points.length);
            }
        } else {
            drain(data, "colinear", 0.05D);
        }
    }

    private void checkGrid45(PlaceContext ctx) {
        PlayerData data = ctx.data;
        if (!isSubEnabled("grid45")) {
            return;
        }
        data.placeYaws.add(data.lastYaw);
        data.placePitches.add(data.lastPitch);
        if (data.placeYaws.size() > 8) {
            data.placeYaws.remove(0);
            data.placePitches.remove(0);
        }
        if (data.placeYaws.size() < 6) {
            return;
        }
        int gridHits = 0;
        int pitchHits = 0;
        for (int k = 0; k < data.placeYaws.size(); k++) {
            double yaw = data.placeYaws.get(k);
            double mod = Math.abs(yaw % 45D);
            double maxMod = sd("grid45.max-yaw-mod", 0.02D, 0.05D);
            if (mod < maxMod || mod > 45D - maxMod) {
                gridHits++;
            }
            double pitch = data.placePitches.get(k);
            if (pitch >= 75D && pitch <= 85D) {
                pitchHits++;
            }
        }
        double pitchStd = MathUtil.stdDev(data.placePitches);
        int size = data.placeYaws.size();
        if (gridHits == size && pitchHits >= size - 1 && pitchStd < sd("grid45.max-pitch-std", 0.15D, 0.25D)) {
            if (bump(data, "grid45", 1D, i("grid45.vl-before-flag", 4))) {
                flag(data, "Grid45", "yaw-45-grid=" + gridHits + "/" + size
                        + " pitch-std=" + MathUtil.round(pitchStd, 3));
            }
        } else {
            drain(data, "grid45", 0.05D);
        }
    }

    private void checkDupRot(PlaceContext ctx) {
        PlayerData data = ctx.data;
        if (!isSubEnabled("duprot")) {
            return;
        }
        data.placeYawDeltas.add(data.lastYawDelta);
        if (data.placeYawDeltas.size() > 8) {
            data.placeYawDeltas.remove(0);
        }
        if (data.placeYawDeltas.size() < 3) {
            return;
        }
        double last = Math.abs(data.placeYawDeltas.get(data.placeYawDeltas.size() - 1));
        double prev = Math.abs(data.placeYawDeltas.get(data.placeYawDeltas.size() - 2));
        double prevPrev = Math.abs(data.placeYawDeltas.get(data.placeYawDeltas.size() - 3));
        if (last > 2D && Math.abs(last - prev) < 0.0001D && Math.abs(prev - prevPrev) < 0.0001D) {
            if (bump(data, "duprot", 1D, i("duprot.vl-before-flag", 4))) {
                flag(data, "DupRot", "identical pre-place delta x3");
            }
        } else {
            drain(data, "duprot", 0.05D);
        }
    }
}