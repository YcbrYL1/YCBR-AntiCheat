package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.snapshot.EntitySnapshot;
import com.ycbr.anticheat.util.MathUtil;

public final class ReachCheck extends Check {

    public ReachCheck(AntiCheatManager manager) {
        super(CheckType.REACH, manager);
    }

    @Override
    protected void onAttack(AttackContext ctx) {
        if (!isEnabled() || ctx.data.creative) {
            return;
        }
        PlayerData data = ctx.data;
        EntitySnapshot target = manager.getEntitySnapshots().get(ctx.targetId);
        if (target == null) {
            return;
        }
        double maxReach = sd("max-reach", 3.1D, 3.0D);
        double leniency = sd("leniency", 0.03D, 0.0D);
        double dx = target.x - data.movement.lastX;
        double dz = target.z - data.movement.lastZ;
        double halfWidth = Math.max(0.1D, target.width / 2.0D);
        double hDist = Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - halfWidth);
        double eyeY = data.movement.lastY + 1.62D;
        double vDist;
        double top = target.y + target.height;
        if (eyeY > top) {
            vDist = eyeY - top;
        } else if (eyeY < target.y) {
            vDist = target.y - eyeY;
        } else {
            vDist = 0.0D;
        }
        double distance = Math.sqrt(hDist * hDist + vDist * vDist);
        long now = System.currentTimeMillis();
        long moveAgeMs = Math.max(0L, now - data.movement.lastMoveTime);
        double attackTicks = Math.min(4.0D, moveAgeMs / 50.0D);
        double attackSpeed = Math.max(0.05D,
                data.movement.distanceXZ + Math.abs(data.movement.lastMoveY));
        double attackAllowance = Math.min(isStrict() ? 0.25D : 0.4D, attackTicks * attackSpeed);
        long snapAgeMs = Math.max(0L, now - target.createdMillis);
        double snapTicks = Math.min(8.0D, snapAgeMs / 50.0D);
        double closing = Math.sqrt(target.vx * target.vx + target.vy * target.vy + target.vz * target.vz);
        double victimAllowance = Math.min(isStrict() ? 0.25D : 0.4D, snapTicks * Math.max(closing, 0.2D));
        double allowance = attackAllowance + victimAllowance;
        if (distance - allowance <= maxReach + leniency) {
            drain(data, "overreach", 0.5D);
            return;
        }

        // 多帧视角枚举：任一帧射线命中实体插值碰撞盒 → 视为合法命中（防误杀擦边）
        if (i("multi-frame.enabled", 1) == 1 && hitsFromAnyFrame(data, target, maxReach + leniency)) {
            drain(data, "overreach", 0.3D);
            return;
        }

        if (bump(data, "overreach", 1.0D, i("vl-before-flag", 2))) {
            flag(data, "Reach", "dist=" + MathUtil.round(distance, 2) + " real="
                    + MathUtil.round(distance - allowance, 2) + " limit="
                    + MathUtil.round(maxReach + leniency, 2));
        }
    }

    /** 从最近 N 帧视角发射射线，命中目标插值碰撞盒即返回 true。 */
    private boolean hitsFromAnyFrame(PlayerData data, EntitySnapshot target, double maxReach) {
        double eyeX = data.movement.lastX;
        double eyeY = data.movement.lastY + 1.62D;
        double eyeZ = data.movement.lastZ;

        // 攻击时刻的实体插值位置：当前快照位置 - 速度 * 快照年龄（回退到攻击时刻）
        double ageTicks = Math.min(4.0D, Math.max(0.0D,
                (System.currentTimeMillis() - target.createdMillis) / 50.0D));
        double tx = target.x - target.vx * ageTicks;
        double ty = target.y - target.vy * ageTicks;
        double tz = target.z - target.vz * ageTicks;

        double halfWidth = Math.max(0.1D, target.width / 2.0D);
        double expand = sd("multi-frame.expand", 0.05D, 0.0D);
        double minX = tx - halfWidth - expand;
        double minY = ty - expand;
        double minZ = tz - halfWidth - expand;
        double maxX = tx + halfWidth + expand;
        double maxY = ty + target.height + expand;
        double maxZ = tz + halfWidth + expand;

        int windowTicks = i("multi-frame.window-ticks", 2);
        int nowTick = manager.getMainHandler().currentServerTick();
        int frames = 0;
        for (int step = 0; step < PlayerData.ROT_HIST_SIZE && frames < windowTicks; step++) {
            int idx = (data.rotHistHead - step + PlayerData.ROT_HIST_SIZE) % PlayerData.ROT_HIST_SIZE;
            if (data.rotHistTick[idx] == 0) {
                continue;
            }
            if (nowTick - data.rotHistTick[idx] > windowTicks) {
                continue;
            }
            frames++;
            if (MathUtil.rayIntersectsAabb(eyeX, eyeY, eyeZ,
                    (float) data.rotHistYaw[idx], (float) data.rotHistPitch[idx],
                    minX, minY, minZ, maxX, maxY, maxZ, 0.0D, maxReach)) {
                return true;
            }
        }
        return false;
    }
}