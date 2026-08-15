package com.ycbr.anticheat.simulation;

/**
 * 移动穿墙（Phase）穿透深度计算（纯逻辑，无 Bukkit 依赖，可单测）。
 *
 * <p>原版客户端碰撞解析保证玩家 AABB 与实心方块盒永不重叠（最多浮点级贴合误差）。
 * 本类计算玩家碰撞盒在体素网格上的<b>最大穿透深度</b>（推出距离 = 三轴重叠的最小值），
 * 供 PhaseCheck 判定"客户端不可能出现的嵌入"。</p>
 *
 * <p>网格外（越界/未知格）返回 {@code NaN}——调用方必须跳过判定（网格缺失零误判）。</p>
 */
public final class PhaseLogic {

    /** 贴合容差：浮点误差级别的重叠不算穿透（客户端碰撞面贴合 ~1e-7 级）。 */
    public static final double TOUCH_EPS = 1e-4;

    private PhaseLogic() {}

    /**
     * 玩家碰撞盒（脚底 (x,y,z) ± {@link CollisionResolver#PLAYER_HALF_WIDTH} ×
     * {@link CollisionResolver#PLAYER_HEIGHT}）与网格实心格盒的最大穿透深度。
     *
     * @param x 脚底 X
     * @param y 脚底 Y
     * @param z 脚底 Z
     * @param grid 体素网格（null → NaN）
     * @return 最大穿透深度（米；无穿透 = 0；任何覆盖格未知 = NaN）
     */
    public static double maxPenetration(double x, double y, double z, VoxelGrid grid) {
        if (grid == null) {
            return Double.NaN;
        }
        double hw = CollisionResolver.PLAYER_HALF_WIDTH;
        double h = CollisionResolver.PLAYER_HEIGHT;
        int bx0 = (int) Math.floor(x - hw);
        int bx1 = (int) Math.floor(x + hw);
        int by0 = (int) Math.floor(y);
        int by1 = (int) Math.floor(y + h);
        int bz0 = (int) Math.floor(z - hw);
        int bz1 = (int) Math.floor(z + hw);
        double worst = 0.0;
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    double top = grid.topAt(bx, by, bz);
                    if (top < 0) {
                        return Double.NaN;
                    }
                    if (top <= 0) {
                        continue;
                    }
                    double ovX = Math.min(x + hw, bx + 1.0) - Math.max(x - hw, bx);
                    double ovY = Math.min(y + h, by + top) - Math.max(y, by);
                    double ovZ = Math.min(z + hw, bz + 1.0) - Math.max(z - hw, bz);
                    if (ovX > TOUCH_EPS && ovY > TOUCH_EPS && ovZ > TOUCH_EPS) {
                        // 推出距离 = 最小重叠轴
                        double depth = Math.min(ovX, Math.min(ovY, ovZ));
                        if (depth > worst) {
                            worst = depth;
                        }
                    }
                }
            }
        }
        return worst;
    }

    /**
     * 活塞邻域豁免：玩家盒扩张 {@code expand} 格范围内存在 PISTON 标志格。
     * 活塞推动的方块实体不在网格里（服务端只对最终方块建模），被活塞推进墙的
     * 玩家在网格上显示为"嵌入"——邻域有活塞活动时一律跳过判定。
     *
     * @return true = 豁免（跳过判定）；任何邻域格未知 = true（保守豁免）
     */
    public static boolean nearPiston(double x, double y, double z, VoxelGrid grid, int expand) {
        if (grid == null) {
            return true;
        }
        double hw = CollisionResolver.PLAYER_HALF_WIDTH;
        double h = CollisionResolver.PLAYER_HEIGHT;
        int bx0 = (int) Math.floor(x - hw) - expand;
        int bx1 = (int) Math.floor(x + hw) + expand;
        int by0 = (int) Math.floor(y) - expand;
        int by1 = (int) Math.floor(y + h) + expand;
        int bz0 = (int) Math.floor(z - hw) - expand;
        int bz1 = (int) Math.floor(z + hw) + expand;
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    int f = grid.flagAt(bx, by, bz);
                    if (f < 0) {
                        return true;
                    }
                    if ((f & VoxelGrid.PISTON) != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
