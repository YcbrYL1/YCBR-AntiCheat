package com.ycbr.anticheat.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java AABB 逐轴碰撞解析器（1.8 客户端驱动移动模型的碰撞语义）。
 *
 * <p>用法：给定玩家脚底位置 (x,y,z) 与原始位移 (dx,dy,dz)，结合 {@link VoxelGrid}，
 * 按"X → Z → Y"逐轴做碰撞盒扫掠，把位移截断到碰撞面（面距），并派生：
 * <ul>
 *   <li>{@link Resolution#hitGround}/{@link Resolution#hitCeiling}：落地/撞顶（垂直轴被地面/天花板截断）；</li>
 *   <li>{@link Resolution#hitWallX}/{@link Resolution#hitWallZ}：水平轴被截断（滑墙）；</li>
 *   <li>{@link Resolution#stepped}：台阶步进——阻挡格均为 STEP（顶高 0.5）时，恢复水平位移并抬升脚底到台阶面；</li>
 *   <li>{@link Resolution#landedOnSlime}：落在粘液块上（供调用方做弹跳包络）。</li>
 * </ul>
 *
 * <p>网格外（越界/过期）任何一格返回未知 → 整个解析返回 {@code null}，调用方回退旧
 * （墙距+豁免）路径，保证网格缺失时零误判。解析结果是"合法位移上限"，过预测安全。
 */
public final class CollisionResolver {

    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    /** 玩家步高（Entity.stepHeight，1.8 为 0.5，可自动走上半砖/楼梯段）。 */
    public static final double STEP_HEIGHT = 0.5;
    private static final double EPS = 1e-9;

    private CollisionResolver() {}

    public static final class Resolution {
        public double dx;
        public double dy;
        public double dz;
        public boolean hitGround;
        public boolean hitCeiling;
        public boolean hitWallX;
        public boolean hitWallZ;
        public boolean landedOnSlime;
        public boolean stepped;

        @Override
        public String toString() {
            return "Resolution{dx=" + String.format("%.3f", dx) + ", dy="
                    + String.format("%.3f", dy) + ", dz=" + String.format("%.3f", dz)
                    + ", ground=" + hitGround + ", ceiling=" + hitCeiling
                    + ", wallX=" + hitWallX + ", wallZ=" + hitWallZ
                    + ", slime=" + landedOnSlime + ", stepped=" + stepped + '}';
        }
    }

    /**
     * 逐轴碰撞解析。
     *
     * @param x 移动前脚底 X
     * @param y 移动前脚底 Y
     * @param z 移动前脚底 Z
     * @return 解析结果；网格数据不足（越界）返回 null
     */
    public static Resolution resolve(double x, double y, double z,
            double dx, double dy, double dz, VoxelGrid grid) {
        if (grid == null) {
            return null;
        }
        Resolution r = new Resolution();
        List<int[]> blockers = new ArrayList<int[]>();
        boolean fullBlocker = false;

        // ---- X 轴 ----
        if (dx > EPS) {
            int bx0 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
            int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH + dx);
            int by0 = (int) Math.floor(y);
            int by1 = (int) Math.floor(y + PLAYER_HEIGHT);
            int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
            int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
            double best = dx;
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int by = by0; by <= by1; by++) {
                    for (int bz = bz0; bz <= bz1; bz++) {
                        double top = grid.topAt(bx, by, bz);
                        if (top < 0) {
                            return null;
                        }
                        if (top <= 0) {
                            continue;
                        }
                        if ((bx + 1) > x + PLAYER_HALF_WIDTH + EPS
                                && bx < x + PLAYER_HALF_WIDTH + dx - EPS) {
                            double face = bx - (x + PLAYER_HALF_WIDTH);
                            if (face < best) {
                                best = face;
                                blockers.add(new int[] { bx, by, bz });
                                if (top >= 1.0 - EPS) {
                                    fullBlocker = true;
                                }
                            }
                        }
                    }
                }
            }
            r.dx = Math.max(0.0, best);
            r.hitWallX = best < dx - EPS;
        } else if (dx < -EPS) {
            int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH + dx);
            int bx1 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
            int by0 = (int) Math.floor(y);
            int by1 = (int) Math.floor(y + PLAYER_HEIGHT);
            int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
            int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
            double best = dx;
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int by = by0; by <= by1; by++) {
                    for (int bz = bz0; bz <= bz1; bz++) {
                        double top = grid.topAt(bx, by, bz);
                        if (top < 0) {
                            return null;
                        }
                        if (top <= 0) {
                            continue;
                        }
                        if (bx < x - PLAYER_HALF_WIDTH - EPS
                                && (bx + 1) > x - PLAYER_HALF_WIDTH + dx + EPS) {
                            // -X：可移动到位 = 格右缘 - 当前左缘（负值），取最近阻挡面（最大）
                            double face = (bx + 1) - (x - PLAYER_HALF_WIDTH);
                            if (face > best) {
                                best = face;
                                blockers.add(new int[] { bx, by, bz });
                                if (top >= 1.0 - EPS) {
                                    fullBlocker = true;
                                }
                            }
                        }
                    }
                }
            }
            r.dx = Math.min(0.0, best);
            r.hitWallX = best > dx + EPS;
        } else {
            r.dx = 0.0;
        }

        // ---- Z 轴 ----
        if (dz > EPS) {
            int bz0 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
            int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH + dz);
            int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
            int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
            int by0 = (int) Math.floor(y);
            int by1 = (int) Math.floor(y + PLAYER_HEIGHT);
            double best = dz;
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        double top = grid.topAt(bx, by, bz);
                        if (top < 0) {
                            return null;
                        }
                        if (top <= 0) {
                            continue;
                        }
                        if ((bz + 1) > z + PLAYER_HALF_WIDTH + EPS
                                && bz < z + PLAYER_HALF_WIDTH + dz - EPS) {
                            double face = bz - (z + PLAYER_HALF_WIDTH);
                            if (face < best) {
                                best = face;
                                blockers.add(new int[] { bx, by, bz });
                                if (top >= 1.0 - EPS) {
                                    fullBlocker = true;
                                }
                            }
                        }
                    }
                }
            }
            r.dz = Math.max(0.0, best);
            r.hitWallZ = best < dz - EPS;
        } else if (dz < -EPS) {
            int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH + dz);
            int bz1 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
            int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
            int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
            int by0 = (int) Math.floor(y);
            int by1 = (int) Math.floor(y + PLAYER_HEIGHT);
            double best = dz;
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        double top = grid.topAt(bx, by, bz);
                        if (top < 0) {
                            return null;
                        }
                        if (top <= 0) {
                            continue;
                        }
                        if (bz < z - PLAYER_HALF_WIDTH - EPS
                                && (bz + 1) > z - PLAYER_HALF_WIDTH + dz + EPS) {
                            // -Z：可移动到位 = 格右缘 - 当前后缘（负值），取最近阻挡面（最大）
                            double face = (bz + 1) - (z - PLAYER_HALF_WIDTH);
                            if (face > best) {
                                best = face;
                                blockers.add(new int[] { bx, by, bz });
                                if (top >= 1.0 - EPS) {
                                    fullBlocker = true;
                                }
                            }
                        }
                    }
                }
            }
            r.dz = Math.min(0.0, best);
            r.hitWallZ = best > dz + EPS;
        } else {
            r.dz = 0.0;
        }

        // ---- 台阶步进（仅当水平被挡、阻挡格全为 STEP、垂直未抬升） ----
        if ((r.hitWallX || r.hitWallZ) && dy <= STEP_EPS_CMP && !fullBlocker && !blockers.isEmpty()) {
            double stepSurface = -1.0;
            boolean blockedAbove = false;
            for (int[] b : blockers) {
                double top = grid.topAt(b[0], b[1], b[2]);
                if (top < 0) {
                    return null;
                }
                if (top <= 0) {
                    continue;
                }
                double surface = b[1] + top;
                if (surface > stepSurface) {
                    stepSurface = surface;
                }
                if (grid.topAt(b[0], b[1] + 1, b[2]) > 0
                        || grid.topAt(b[0], b[1] + 2, b[2]) > 0) {
                    blockedAbove = true;
                }
            }
            if (!blockedAbove && stepSurface >= 0.0) {
                double lift = stepSurface - y;
                if (lift > 0.0 && lift <= STEP_HEIGHT + EPS) {
                    r.dx = dx;
                    r.dz = dz;
                    r.dy = lift;
                    r.stepped = true;
                    r.hitWallX = false;
                    r.hitWallZ = false;
                }
            }
        }

        // ---- Y 轴（若已步进则跳过，dy 已由步进决定） ----
        if (!r.stepped) {
            if (dy < -EPS) {
                int by0 = (int) Math.floor(y + dy);
                int by1 = (int) Math.floor(y);
                int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
                int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
                int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
                int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
                double best = dy;
                double landingSurface = Double.NaN;
                boolean landingSlime = false;
                boolean embedded = false;
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        for (int bz = bz0; bz <= bz1; bz++) {
                            int f = grid.flagAt(bx, by, bz);
                            if (f < 0) {
                                return null;
                            }
                            if ((f & (VoxelGrid.SOLID | VoxelGrid.STEP | VoxelGrid.SLIME
                                    | VoxelGrid.SOUL)) == 0) {
                                continue;
                            }
                            if (bx + 1 > x - PLAYER_HALF_WIDTH + EPS
                                    && bx < x + PLAYER_HALF_WIDTH - EPS
                                    && bz + 1 > z - PLAYER_HALF_WIDTH + EPS
                                    && bz < z + PLAYER_HALF_WIDTH - EPS) {
                                double top = (f & VoxelGrid.STEP) != 0 ? 0.5
                                        : (f & VoxelGrid.SOUL) != 0
                                                ? VoxelGrid.SOUL_SAND_HEIGHT : 1.0;
                                double surface = by + top;
                                double dist = surface - y;
                                if (dist > 0.0) {
                                    embedded = true;
                                } else if (dist > best) {
                                    best = dist;
                                    landingSurface = surface;
                                    landingSlime = (f & VoxelGrid.SLIME) != 0;
                                }
                            }
                        }
                    }
                }
                if (embedded) {
                    r.dy = 0.0;
                    r.hitGround = true;
                } else {
                    r.dy = best;
                    r.hitGround = best > dy + EPS;
                    r.landedOnSlime = r.hitGround && landingSlime
                            && !Double.isNaN(landingSurface);
                }
            } else if (dy > EPS) {
                int by0 = (int) Math.floor(y + PLAYER_HEIGHT);
                int by1 = (int) Math.floor(y + PLAYER_HEIGHT + dy);
                int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
                int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
                int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
                int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
                double best = dy;
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        for (int bz = bz0; bz <= bz1; bz++) {
                            double top = grid.topAt(bx, by, bz);
                            if (top < 0) {
                                return null;
                            }
                            if (top <= 0) {
                                continue;
                            }
                            if (bx + 1 > x - PLAYER_HALF_WIDTH + EPS
                                    && bx < x + PLAYER_HALF_WIDTH - EPS
                                    && bz + 1 > z - PLAYER_HALF_WIDTH + EPS
                                    && bz < z + PLAYER_HALF_WIDTH - EPS) {
                                double face = by - (y + PLAYER_HEIGHT);
                                if (face < best) {
                                    best = face;
                                }
                            }
                        }
                    }
                }
                r.dy = Math.max(0.0, best);
                r.hitCeiling = best < dy - EPS;
            } else {
                r.dy = 0.0;
            }
        }
        return r;
    }

    /** 步进判定的垂直位移上限（允许轻微抬升即触发）。 */
    private static final double STEP_EPS_CMP = 0.05;

    /** 支撑面判定深度：脚底下方该距离内的方块顶面视为接触（吸收浮点漂移）。 */
    public static final double STAND_DEPTH = 0.001;

    /**
     * 脚底支撑检测：位置 (x,y,z) 的碰撞盒足迹（±{@link #PLAYER_HALF_WIDTH}）下方
     * 是否存在支撑面（SOLID/STEP/SLIME 顶面位于脚底下方 {@link #STAND_DEPTH} 内）。
     * 供多 tick 重演推进 onGround 状态：持续贴地行走保持地面物理（摩擦/加速度），
     * 走出边缘转为空中（重力接管）。
     *
     * @return 支撑面 Y 坐标（无支撑 = -1）；足迹任何一格网格未知 = NaN（调用方应
     *         保持原 onGround 状态，贴地过预测方向安全）
     */
    public static double standingSurface(double x, double y, double z, VoxelGrid grid) {
        if (grid == null) {
            return Double.NaN;
        }
        int bx0 = (int) Math.floor(x - PLAYER_HALF_WIDTH);
        int bx1 = (int) Math.floor(x + PLAYER_HALF_WIDTH);
        int bz0 = (int) Math.floor(z - PLAYER_HALF_WIDTH);
        int bz1 = (int) Math.floor(z + PLAYER_HALF_WIDTH);
        int byFloor = (int) Math.floor(y);
        double best = Double.NaN;
        boolean unknown = false;
        for (int by = byFloor - 1; by <= byFloor && !unknown; by++) {
            for (int bx = bx0; bx <= bx1 && !unknown; bx++) {
                for (int bz = bz0; bz <= bz1 && !unknown; bz++) {
                    double top = grid.topAt(bx, by, bz);
                    if (top < 0) {
                        unknown = true;
                        break;
                    }
                    if (top <= 0) {
                        continue;
                    }
                    if (bx + 1 > x - PLAYER_HALF_WIDTH + EPS
                            && bx < x + PLAYER_HALF_WIDTH - EPS
                            && bz + 1 > z - PLAYER_HALF_WIDTH + EPS
                            && bz < z + PLAYER_HALF_WIDTH - EPS) {
                        double surface = by + top;
                        if (surface <= y + EPS && surface >= y - STAND_DEPTH) {
                            if (Double.isNaN(best) || surface > best) {
                                best = surface;
                            }
                        }
                    }
                }
            }
        }
        return unknown ? Double.NaN : (Double.isNaN(best) ? -1.0 : best);
    }
}
