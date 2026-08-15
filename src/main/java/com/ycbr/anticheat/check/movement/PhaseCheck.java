package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.PhaseLogic;
import com.ycbr.anticheat.simulation.VoxelGrid;
import com.ycbr.anticheat.util.MathUtil;

/**
 * 移动穿墙检测（P2-1，对齐 Grim Phase）：玩家 AABB 与实心方块盒重叠（穿透）
 * = 客户端绕过了碰撞解析。原版客户端移动包的位置永远是碰撞解析后的合法位置，
 * 任何超过贴合容差的嵌入都是 NoClip/Phase/卡墙作弊。
 *
 * <p>误判防线：网格缺失/过期/越界（NaN）跳过；传送/挖掘/活塞邻域豁免；
 * 穿透深度阈值 + 连续 streak + VL 缓冲三重门槛。</p>
 */
public final class PhaseCheck extends Check {

    public PhaseCheck(AntiCheatManager manager) {
        super(CheckType.PHASE, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.dead
                || data.ping > cfg.maxPing()) {
            return;
        }
        long now = System.currentTimeMillis();
        // 传送后客户端确认坐标可能出现瞬时嵌入；挖掘同理（方块客户端已视为空气，
        // 服务器网格快照仍显示 SOLID 的窗口）
        if (now - data.lastTeleportTime < i("teleport-exempt-ms", 1000)) {
            return;
        }
        if (data.digging || now - data.lastDigStartTime < i("dig-exempt-ms", 500)) {
            return;
        }
        VoxelGrid grid = data.voxelGrid;
        if (grid == null
                || !grid.isFresh(ctx.arrivalTime, cfg.raw().getLong(
                        "checks.simulation.grid-max-age-ms", 250L))) {
            return;
        }
        // 活塞推动的方块实体不在网格里：被推入墙在网格上显示为嵌入 → 邻域豁免
        if (PhaseLogic.nearPiston(ctx.x, ctx.y, ctx.z, grid, i("piston-expand", 1))) {
            return;
        }
        double depth = PhaseLogic.maxPenetration(ctx.x, ctx.y, ctx.z, grid);
        if (Double.isNaN(depth)) {
            return;
        }
        double minPen = sd("min-penetration", 0.05D, 0.03D);
        if (depth > minPen) {
            if (++data.phaseStreak >= si("streak", 2, 1)) {
                data.phaseStreak = 0;
                if (bump(data, "phase", 1D, i("vl-before-flag", 3))) {
                    flag(data, "Phase", "penetration=" + MathUtil.round(depth, 3)
                            + " x=" + MathUtil.round(ctx.x, 2)
                            + " y=" + MathUtil.round(ctx.y, 2)
                            + " z=" + MathUtil.round(ctx.z, 2));
                }
            }
        } else {
            data.phaseStreak = 0;
            drain(data, "phase", 0.1D);
        }
    }
}
