package com.ycbr.anticheat.simulation;

/**
 * DDA 体素射线步进（学 NCP Passable 多轴序语义）：沿射线逐格枚举穿过的方块，
 * 对每个 occluding 方块计算射线在其内部的弦长（弧长单位），弦长超过阈值才是
 * "实挡"；弦长短于阈值视为擦角（取最宽松轴序），放行。起点格不判（起步已在
 * 方块内不算穿墙）。
 *
 * <p>纯几何，无 Bukkit 依赖，可单测。坐标/方向为世界坐标，t 为弧长（米）。</p>
 */
public final class RayMarchUtil {

    /** 遮挡判定回调（生产：NmsUtil.isOccluding || isSolid；测试：内存 map）。 */
    public interface OcclusionChecker {
        boolean occluding(int x, int y, int z);
    }

    public static final class Result {
        public final boolean blocked;
        /** 第一个实挡方块的入口弧长（起点起，米）；未挡为 0。 */
        public final double blockedAt;

        Result(boolean blocked, double blockedAt) {
            this.blocked = blocked;
            this.blockedAt = blockedAt;
        }
    }

    private RayMarchUtil() {}

    /**
     * DDA 步进（Amanatides &amp; Woo voxel traversal），tMax 最小轴优先、不跳格。
     *
     * @param checker       遮挡判定
     * @param sx sy sz      起点（世界坐标）
     * @param dx dy dz      方向向量（长度任意，会归一化）
     * @param maxLen        最大探测距离（弧长，米）
     * @param minSolidChord 实挡弦长阈值（米）
     */
    public static Result march(OcclusionChecker checker, double sx, double sy, double sz,
            double dx, double dy, double dz, double maxLen, double minSolidChord) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-9D) {
            return new Result(false, 0D);
        }
        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;
        int bx = (int) Math.floor(sx);
        int by = (int) Math.floor(sy);
        int bz = (int) Math.floor(sz);
        int stepX = ux > 0D ? 1 : (ux < 0D ? -1 : 0);
        int stepY = uy > 0D ? 1 : (uy < 0D ? -1 : 0);
        int stepZ = uz > 0D ? 1 : (uz < 0D ? -1 : 0);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? (bx + 1D - sx) : (sx - bx)) / Math.abs(ux);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : (stepY > 0 ? (by + 1D - sy) : (sy - by)) / Math.abs(uy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? (bz + 1D - sz) : (sz - bz)) / Math.abs(uz);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(ux);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(uy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(uz);

        double tEnter = 0D;
        boolean firstCell = true;
        while (true) {
            double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tEnter >= maxLen) {
                return new Result(false, 0D);
            }
            if (!firstCell && checker.occluding(bx, by, bz)) {
                double chord = tExit - tEnter;
                if (chord > minSolidChord) {
                    return new Result(true, tEnter);
                }
            }
            firstCell = false;
            if (tExit >= maxLen || Double.isInfinite(tExit)) {
                return new Result(false, 0D);
            }
            if (tMaxX <= tExit) {
                bx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tExit) {
                by += stepY;
                tMaxY += tDeltaY;
            } else {
                bz += stepZ;
                tMaxZ += tDeltaZ;
            }
            tEnter = tExit;
        }
    }
}