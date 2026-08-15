package com.ycbr.anticheat.check.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.MovementTracker;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.RayMarchUtil;
import com.ycbr.anticheat.snapshot.EntitySnapshot;
import com.ycbr.anticheat.util.MathUtil;
import com.ycbr.anticheat.util.NmsUtil;

public final class KillAuraCheck extends Check {

    private static final double EYE_STANDING = 1.62D;
    private static final double EYE_SNEAKING = 1.54D;

    /**
     * 需要与统计层（aim-stat 信号）交叉验证的启发式子检测。
     * 这些子检测判定"瞄准模式"，对统计信号敏感；其余子检测
     * （selfinteract/autoblock/noswing/post/multiinteract/cps/reach/throughwalls 等）
     * 与瞄准模式无关，保持直判，避免引入假阴性。
     *
     * 注：AimModulo360 已升级为"硬检测"（>320° 单次大转角即直判，
     * 对齐 Grim AimModulo360），不再受统计层门控。
     */
    private static final Set<String> AIM_GATED_SUBS = new java.util.HashSet<String>(
            java.util.Arrays.asList("AimStep", "GcdStable", "GcdGrid",
                    "ConstStep", "AxisAsym", "BigRot", "Angle", "Switch"));

    /**
     * 统计层交叉门：委托 {@link AimGating}（纯逻辑）。
     * <ul>
     *   <li>非门控子检测：直判；</li>
     *   <li>{@code heuristic-soft} 开启：aimstat 交叉命中且新鲜才 punish，否则只投启发式信号
     *       （冷启动同样软降级，彻底消除"aimstat 未就绪时的直判误杀"）；</li>
     *   <li>默认（soft 关）：兼容旧行为——aimstat 关闭或未开交叉时直判。</li>
     * </ul>
     */
    private boolean shouldPunish(PlayerData data, String sub) {
        boolean gated = AIM_GATED_SUBS.contains(sub);
        boolean soft = cfg.raw().getBoolean("checks.killaura.heuristic-soft", false);
        boolean aimstatEnabled = cfg.enabled("aimstat");
        boolean crossEnabled = cfg.raw().getBoolean("checks.killaura.aimstat-cross", true);
        boolean samplesReady = data.statSampleCount
                >= com.ycbr.anticheat.check.combat.aim.AimStatsLogic.MIN_SAMPLES;
        boolean signalHit = signalCount(data, "aim-stat") >= 1;
        boolean fresh = System.currentTimeMillis() - data.aimStatSignalTime
                < cfg.i("checks.aimstat.signal-fresh-ms", 10000);
        return AimGating.shouldPunish(sub, gated, soft, aimstatEnabled, crossEnabled,
                samplesReady, signalHit, fresh);
    }

    /** 启发式 flag 出口：未通过交叉门时只投启发式信号，不 punish。 */
    private void flagGated(PlayerData data, String sub, String info) {
        if (shouldPunish(data, sub)) {
            flag(data, sub, info);
        } else {
            addSignal(data, "heur-" + sub);
        }
    }

    /**
     * 硬检测预检（纯逻辑，监听线程安全调用，供 {@link com.ycbr.anticheat.check.CheckRegistry} 使用）：
     * <ul>
     *   <li>突发取消窗口内（MultiInteract 命中后）：取消任何攻击包；</li>
     *   <li>攻击自己（SelfInteract）：取消攻击包并进入硬惩罚。</li>
     * </ul>
     */
    public static boolean shouldHardCancel(PlayerData data, int targetId, int playerEntityId,
            boolean selfInteractEnabled, boolean hardCancelSelf) {
        if (data.op || data.creative) {
            return false;
        }
        if (data.hardCancelUntil > System.currentTimeMillis()) {
            return true;
        }
        if (targetId != playerEntityId) {
            return false;
        }
        return selfInteractEnabled && hardCancelSelf;
    }

    public KillAuraCheck(AntiCheatManager manager) {
        super(CheckType.KILLAURA, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) {
            return;
        }
        if (ctx.data.attackTight) {
            checkPost(ctx.data, System.currentTimeMillis());
        }
        checkAimStep(ctx);
    }

    @Override
    protected void onClientCommand(PlayerData data, int action) {
        if (!isEnabled() || action != 2) {
            return;
        }
        if (!isSubEnabled("inventorycombo")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (data.lastAttackTime > 0L && now - data.lastAttackTime <= si("inventorycombo.attack-window-ms", 100, 150)
                && data.positionCount == data.lastAttackPositionCount) {
            if (bump(data, "inventorycombo", 1D, i("inventorycombo.vl-before-flag", 1))) {
                flag(data, "InventoryCombo", "attacked while opening inventory");
            }
        }
    }

    private void checkAimStep(MoveContext ctx) {
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.ping > cfg.maxPing()) {
            data.hasPrevRotation = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastAttackTime > 3500L) {
            if (!data.aimDeltas.isEmpty()) {
                data.aimDeltas.clear();
                data.aimPitchDeltas.clear();
            }
            data.pendingReversalTime = 0L;
        } else {
            double dY = Math.abs(MathUtil.normalizeYaw(ctx.yaw - data.prevYaw));
            double dP = Math.abs(ctx.pitch - data.prevPitch);
            data.lastYawDelta = MathUtil.normalizeYaw(ctx.yaw - data.prevYaw);
            if (isSubEnabled("gcd") || isSubEnabled("gcdgrid") || isSubEnabled("conststep")
                    || isSubEnabled("axisasym")) {
                if (dY > 0.1D && dY < 30D && dP < 30D) {
                    data.aimDeltas.add(dY);
                    data.aimPitchDeltas.add(dP);
                    if (data.aimDeltas.size() > 40) {
                        data.aimDeltas.remove(0);
                        data.aimPitchDeltas.remove(0);
                    }
                }
            }
            if (dY > d("bigrot.min-turn-degrees", 60D) && dY <= 180D) {
                data.bigRotQueue.add(now);
                long window = i("bigrot.window-ms", 800);
                while (!data.bigRotQueue.isEmpty()
                        && now - data.bigRotQueue.get(0) > window) {
                    data.bigRotQueue.remove(0);
                }
            }
            if (isSubEnabled("gcd") || isSubEnabled("gcdgrid")) {
                checkGcd(data);
            }
            if (isSubEnabled("conststep")) {
                checkConstStep(data);
            }
            if (isSubEnabled("axisasym")) {
                checkAxisAsym(data);
            }
            checkBigRot(data);
        }
        if (!data.hasPrevRotation) {
            data.prevYaw = ctx.yaw;
            data.prevPitch = ctx.pitch;
            data.hasPrevRotation = true;
            return;
        }
        // 复用上方 else 分支已写入的 lastYawDelta（= normalizeYaw(ctx.yaw - prevYaw)）
        double rawDelta = data.lastYawDelta;
        if (isSubEnabled("modulo360") && data.ping <= i("modulo360.max-ping", 150)
                && now - data.lastAttackTime <= 3500L) {
            double rawAbs = Math.abs(rawDelta);
            boolean hardSnap = rawAbs > 320D && Math.abs(data.lastYawDelta) < 30D;
            if (hardSnap) {
                // 硬检测：单次 >320° 瞬移（上一帧 <30°），真人不可能完成 → 直接 punish（不依赖统计门控）
                data.modulo360Streak = 0;
                if (bump(data, "modulo360", 1D, i("modulo360.vl-before-flag", 2))) {
                    flag(data, "AimModulo360", "hard yaw snap " + MathUtil.round(rawDelta, 1));
                }
            } else if (rawAbs > 170D && Math.abs(data.lastYawDelta) < 30D) {
                if (++data.modulo360Streak >= si("modulo360.min-streak", 2, 1)) {
                    data.modulo360Streak = 0;
                    if (bump(data, "modulo360", 1D, i("modulo360.vl-before-flag", 2))) {
                        flag(data, "AimModulo360", "yaw snap " + MathUtil.round(rawDelta, 1));
                    }
                }
            } else {
                data.modulo360Streak = 0;
            }
        } else {
            data.modulo360Streak = 0;
        }
        if (!isSubEnabled("aimstep")) {
            data.prevYaw = ctx.yaw;
            data.prevPitch = ctx.pitch;
            return;
        }
        double dYaw = Math.abs(MathUtil.normalizeYaw(ctx.yaw - data.prevYaw));
        double dPitch = Math.abs(ctx.pitch - data.prevPitch);
        double noDelta = d("aimstep.max-no-delta", 0.00001D);
        double stepDelta = d("aimstep.min-step-delta", 20D);
        if (dPitch < noDelta && (ctx.pitch == 90F || ctx.pitch == -90F)) {
            dYaw = 0D;
        }
        if (dYaw >= 90D || dPitch >= 90D) {
            data.prevYaw = ctx.yaw;
            data.prevPitch = ctx.pitch;
            return;
        }
        boolean step = (dYaw < noDelta && dPitch > stepDelta) || (dPitch < noDelta && dYaw > stepDelta);
        if (step) {
            if (++data.aimStepStreak >= (isStrict() ? 2 : 3)) {
                if (bump(data, "aimstep", 1D, i("aimstep.vl-before-flag", 8))) {
                    flagGated(data, "AimStep", "dYaw=" + MathUtil.round(dYaw, 4) + " dPitch=" + MathUtil.round(dPitch, 2));
                }
            }
        } else {
            data.aimStepStreak = 0;
            drain(data, "aimstep", 0.05D);
        }
        data.prevYaw = ctx.yaw;
        data.prevPitch = ctx.pitch;
    }

    private void checkGcd(PlayerData data) {
        List<Double> deltas = data.aimDeltas;
        if (deltas.size() < 2) return;
        double last = deltas.get(deltas.size() - 1);
        double prev = deltas.get(deltas.size() - 2);
        long g = MathUtil.gcd((long) (last * MathUtil.EXPANDER), (long) (prev * MathUtil.EXPANDER));
        if (isSubEnabled("gcd") && g > 0L && g < 131072L && MathUtil.stdDev(deltas) < 0.25D) {
            if (++data.gcdStreak >= (isStrict() ? 3 : 6) && MathUtil.mean(deltas) > 1D) {
                if (bump(data, "gcd", 1D, i("gcd.vl-before-flag", 6))) {
                    flagGated(data, "GcdStable", "gcd=" + MathUtil.round(g / MathUtil.EXPANDER, 5) + " streak=" + data.gcdStreak);
                }
            }
        } else {
            data.gcdStreak = 0;
            drain(data, "gcd", 0.05D);
        }
        if (g > 0L) {
            data.gcdBucket.add(g);
            if (data.gcdBucket.size() > 80) {
                data.gcdBucket.remove(0);
            }
        }
        if (isSubEnabled("gcdgrid") && data.gcdBucket.size() >= 20) {
            long[] mc = MathUtil.modeCount(data.gcdBucket);
            long mode = mc[0];
            int count = (int) mc[1];
            if (mode > 0L && count >= 15 && count * 100 / data.gcdBucket.size() >= 30) {
                double modeDeg = mode / MathUtil.EXPANDER;
                if (modeDeg < 0.0005D || modeDeg > 1.0D) {
                    drain(data, "gcdgrid", 0.05D);
                    return;
                }
                int n = Math.min(10, deltas.size());
                if (n >= 5) {
                    int aligned = 0;
                    java.util.Set<Long> dots = new java.util.HashSet<Long>();
                    for (int k = deltas.size() - n; k < deltas.size(); k++) {
                        long d = (long) (deltas.get(k) * MathUtil.EXPANDER);
                        long q = Math.round((double) d / mode);
                        if (Math.abs(d - q * mode) <= mode / 8L) {
                            aligned++;
                            dots.add(q);
                        }
                    }
                    if (aligned >= n - 1 && dots.size() >= 3) {
                        if (bump(data, "gcdgrid", 1D, i("gcdgrid.vl-before-flag", 6))) {
                            flagGated(data, "GcdGrid", "mode=" + MathUtil.round(modeDeg, 5)
                                    + " aligned=" + aligned + "/" + n + " dots=" + dots.size());
                        }
                    } else {
                        drain(data, "gcdgrid", 0.05D);
                    }
                }
            }
        }
    }

    private void checkConstStep(PlayerData data) {
        List<Double> deltas = data.aimDeltas;
        if (deltas.size() >= (isStrict() ? 10 : 20) && MathUtil.stdDev(deltas) < 0.05D && MathUtil.mean(deltas) > 1D) {
            if (bump(data, "conststep", 1D, i("conststep.vl-before-flag", 6))) {
                flagGated(data, "ConstStep", "std=" + MathUtil.round(MathUtil.stdDev(deltas), 4) + " n=" + deltas.size());
            }
        } else {
            drain(data, "conststep", 0.05D);
        }
    }

    private void checkAxisAsym(PlayerData data) {
        List<Double> deltas = data.aimDeltas;
        List<Double> pitches = data.aimPitchDeltas;
        if (deltas.size() < (isStrict() ? 6 : 10)) return;
        double varYaw = MathUtil.variance(deltas);
        double varPitch = MathUtil.variance(pitches);
        double varMax = d("axisasym.var-max", 0.05D);
        double varMin = d("axisasym.var-min", 20D);
        if ((varYaw < varMax && varPitch > varMin) || (varPitch < varMax && varYaw > varMin)) {
            if (bump(data, "axisasym", 1D, i("axisasym.vl-before-flag", 6))) {
                flagGated(data, "AxisAsym", "varYaw=" + MathUtil.round(varYaw, 2) + " varPitch=" + MathUtil.round(varPitch, 2));
            }
        } else {
            drain(data, "axisasym", 0.05D);
        }
    }

    private void checkBigRot(PlayerData data) {
        if (!isSubEnabled("bigrot")) {
            return;
        }
        List<Long> queue = data.bigRotQueue;
        int min = si("bigrot.min-turns", 5, 3);
        if (queue.size() < min) {
            return;
        }
        if (bump(data, "bigrot", 1D, i("bigrot.vl-before-flag", 3))) {
            flagGated(data, "BigRot", "large turns=" + queue.size()
                    + " in " + i("bigrot.window-ms", 800) + "ms");
        }
    }

    @Override
    protected void onAttack(AttackContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (ctx.targetId == ctx.playerEntityId) {
            // 兜底路径：监听线程已取消的（硬检测）不走到这里；走到这里说明
            // 硬取消关闭 → 仍做 VL 惩罚 + 软阻断，避免完全漏过自击。
            if (isSubEnabled("selfinteract")
                    && bump(data, "selfinteract", 1D, i("selfinteract.vl-before-flag", 1))) {
                flag(data, "SelfInteract", "attacked self");
                blockAttacks(data, selfInteractBlockMs());
            }
            return;
        }
        long atkNow = System.currentTimeMillis();
        checkAutoBlock(data, atkNow);
        checkPostAttack(data, atkNow);
        checkSwitch(data, atkNow, ctx.targetId);
        if (data.creative || data.inVehicle || data.ping > cfg.maxPing()) {
            return;
        }
        if (!data.movement.initialized) {
            return;
        }

        EntitySnapshot target = manager.getEntitySnapshots().get(ctx.targetId);
        if (target != null) {
            checkReach(ctx, target);
            checkThroughWalls(ctx, target);
        }
        long now2 = System.currentTimeMillis();
        long prevGap = data.lastAttackTime > 0L ? now2 - data.lastAttackTime : Long.MAX_VALUE;
        data.pendingAngleTargets.add(new Long[] { (long) ctx.targetId, now2, (long) (data.lastYaw * 1000D) });
        while (!data.pendingAngleTargets.isEmpty() && now2 - data.pendingAngleTargets.get(0)[1] > 400L) {
            data.pendingAngleTargets.remove(0);
        }
        checkCps(ctx);
        long now3 = System.currentTimeMillis();
        if (data.lastAttackTime > 0L) {
            long gap = now3 - data.lastAttackTime;
            if (gap > 0L && gap < 2000L) {
                data.attackIntervals.add(gap);
                if (data.attackIntervals.size() > 24) {
                    data.attackIntervals.remove(0);
                }
            }
        }
        data.lastAttackTime = now3;
        checkNoSwing(data, now3, prevGap);
        checkNoSwingSame(data, now3, prevGap);
        checkMultiInteract(data, now3, ctx.targetId);
        data.targetHistory.add(new Long[] { now3, (long) ctx.targetId, (long) (data.lastYaw * 1000D) });
        while (!data.targetHistory.isEmpty() && now3 - data.targetHistory.get(0)[0] > 3000L) {
            data.targetHistory.remove(0);
        }
        checkMultiTarget(data, now3);
        checkInterval(data);
    }

    /**
     * 硬检测入口（在 actor 线程执行，由监听线程预检命中后调度）：
     * 攻击自己（KA 经典痕迹，客户端正常情况不可能发生）→ 即时 flag + 攻击阻断。
     * 与 Grim SelfInteract 的"命中即取消+封禁"对齐。
     */
    public void onSelfInteractHard(PlayerData data) {
        if (bump(data, "selfinteract", 1D, i("selfinteract.vl-before-flag", 1))) {
            flag(data, "SelfInteract", "attacked self (hard-cancel)");
        }
        blockAttacks(data, selfInteractBlockMs());
    }

    private long selfInteractBlockMs() {
        return Math.max(0L, manager.config().raw().getLong(
                "checks.killaura.selfinteract.hard-block-ms", 500L));
    }

    private void checkAutoBlock(PlayerData data, long now) {
        if (!isSubEnabled("autoblock")) {
            return;
        }
        if (data.usingItem) {
            return;
        }
        if (!data.digging) {
            return;
        }
        // 【语义修正】AutoBlock 作弊指纹是"攻击触发的挖掘"：挖掘刚启动（<mine-exempt-ms）
        // 就发生攻击。而"挖掘持续超过 mine-exempt-ms 后的攻击"= 挖矿中反击 / 左键挖右键打，
        // 是 1.8 完全合法的操作 → 豁免。旧逻辑用 dig-exempt 豁免挖掘早期、误判挖矿反击，
        // 恰好把作弊特征豁免、把合法操作判了——已反转。
        if (now - data.lastDigStartTime > i("autoblock.mine-exempt-ms", 300)) {
            data.autoBlockStreak = 0;
            return;
        }
        // 受击豁免：挖矿被攻击→松手→digging 复位有 1 tick 延迟，此时还击会被误判。
        // 收到击退后 hit-exempt-ms 内不判（1.8 PvP 受击必有 KB）。
        if (now - data.lastKbTime < si("autoblock.hit-exempt-ms", 800, 600)) {
            data.autoBlockStreak = 0;
            return;
        }
        // 连续高速连击才 flag：streak-gap-ms 收紧到 150ms（>6.7cps），正常攻击速度
        // （<10cps）会让 streak 断开；挖掘早期单次攻击（如挖矿瞬间被打）也不判。
        if (now - data.lastAutoBlockAttackTime <= si("autoblock.streak-gap-ms", 150, 120)) {
            data.autoBlockStreak++;
        } else {
            data.autoBlockStreak = 1;
        }
        data.lastAutoBlockAttackTime = now;
        if (data.autoBlockStreak < si("autoblock.streak", 3, 2)) {
            drain(data, "autoblock", 0.1D);
            return;
        }
        if (bump(data, "autoblock", 1D, i("autoblock.vl-before-flag", 2))) {
            flag(data, "AutoBlock", "attack right after dig start x" + data.autoBlockStreak);
        }
    }

    private void checkPostAttack(PlayerData data, long now) {
        if (!isSubEnabled("post")) {
            return;
        }
        if (data.lastPacketTime > 0L && now - data.lastPacketTime < 10L) {
            data.attackTight = true;
            data.attackTightTime = now;
        }
    }

    private void checkPost(PlayerData data, long now) {
        if (!isSubEnabled("post")) {
            data.attackTight = false;
            return;
        }
        if (data.ping > si("post.max-ping", 150, 120)) {
            data.attackTight = false;
            return;
        }
        if (manager.getMainHandler().getTps() < d("post.tps-exempt", 15D)) {
            data.attackTight = false;
            return;
        }
        MovementTracker m = data.movement;
        if (m.distanceXZ < 0.02D && m.lastDistanceXZ < 0.02D) {
            data.attackTight = false;
            drain(data, "post", 0.05D);
            return;
        }
        long elapsed = now - data.attackTightTime;
        data.attackTight = false;
        if (elapsed >= si("post.min-gap-ms", 40, 45) && elapsed <= si("post.max-gap-ms", 100, 90)) {
            drain(data, "post", 0.05D);
            return;
        }
        if (bump(data, "post", 1D, i("post.vl-before-flag", 3))) {
            flag(data, "Post", "no position gap after attack " + elapsed + "ms");
        }
    }

    private void checkSwitch(PlayerData data, long now, int targetId) {
        if (!isSubEnabled("switch")) {
            return;
        }
        long switchMs = Long.MAX_VALUE;
        if (data.lastAttackTargetId != 0 && data.lastAttackTargetId != targetId) {
            if (data.lastTargetSwitchTime > 0L) {
                switchMs = now - data.lastTargetSwitchTime;
            }
            data.lastTargetSwitchTime = now;
        }
        data.switchAttackCount++;
        if (data.useEntityCount > i("switch.ratio-reset", 40)) {
            data.useEntityCount = 0;
            data.switchAttackCount = 0;
            return;
        }
        if (data.switchAttackCount < si("switch.min-samples", 20, 10)) {
            return;
        }
        double ratio = data.switchAttackCount / (double) data.useEntityCount;
        if (ratio <= sd("switch.min-ratio", 0.85D, 0.75D)) {
            return;
        }
        if (switchMs > i("switch.window-ms", 5)) {
            return;
        }
        if (!data.hasPrevRotation) {
            return;
        }
        double yawRate = Math.abs(MathUtil.normalizeYaw(data.lastYaw - data.prevYaw));
        if (yawRate <= d("switch.min-yaw-rate", 15D)) {
            return;
        }
        if (bump(data, "switch", 1D, i("switch.vl-before-flag", 2))) {
            flagGated(data, "Switch", "target switch " + switchMs + "ms ratio=" + MathUtil.round(ratio, 2));
        }
    }

    private void checkNoSwing(PlayerData data, long now, long prevGap) {
        if (!isSubEnabled("noswing")) {
            return;
        }
        long window = si("noswing.window-ms", 400, 300);
        if (prevGap > 500L) {
            drain(data, "noswing", 0.05D);
            return;
        }
        if (data.lastSwingTime > 0L && now - data.lastSwingTime <= window) {
            drain(data, "noswing", 0.05D);
            return;
        }
        if (bump(data, "noswing", 1D, i("noswing.vl-before-flag", 3))) {
            flag(data, "NoSwing", "attack without animation");
        }
    }

    private void checkNoSwingSame(PlayerData data, long now, long prevGap) {
        if (!isSubEnabled("noswingsame")) {
            return;
        }
        if (prevGap > 500L) {
            drain(data, "noswingsame", 0.05D);
            return;
        }
        if (data.lastSwingPositionCount == data.positionCount) {
            drain(data, "noswingsame", 0.05D);
            return;
        }
        if (data.lastSwingTime > 0L && now - data.lastSwingTime <= 120L) {
            drain(data, "noswingsame", 0.05D);
            return;
        }
        if (bump(data, "noswingsame", 1D, i("noswingsame.vl-before-flag", 2))) {
            flag(data, "NoSwing", "no swing in same packet batch");
        }
    }

    private void checkMultiInteract(PlayerData data, long now, int targetId) {
        if (!isSubEnabled("multiinteract")) {
            return;
        }
        if (now - data.lastAttackTime > 1000L) {
            drain(data, "multiinteract", 0.1D);
        } else if (data.lastAttackTargetId != 0 && data.lastAttackTargetId != targetId
                && now - data.lastAttackTime <= i("multiinteract.max-gap-ms", 100)
                && data.positionCount == data.lastAttackPositionCount) {
            if (bump(data, "multiinteract", 1D, i("multiinteract.vl-before-flag", 2))) {
                flag(data, "MultiInteract", "two targets without position packet");
                // 硬检测：命中后开突发取消窗口（Grim cancelBuffer 语义）——
                // 监听线程会直接取消窗口内到达的后续攻击包，终止连点多目标。
                long burstMs = Math.max(0L, i("multiinteract.burst-cancel-ms", 400));
                if (burstMs > 0L) {
                    data.hardCancelUntil = Math.max(data.hardCancelUntil,
                            System.currentTimeMillis() + burstMs);
                }
            }
        } else {
            drain(data, "multiinteract", 0.1D);
        }
        data.lastAttackTargetId = targetId;
        data.lastAttackPositionCount = data.positionCount;
    }

    private void checkInterval(PlayerData data) {
        if (!isSubEnabled("interval")) {
            return;
        }
        List<Long> gaps = data.attackIntervals;
        if (gaps.size() < 20) return;
        List<Double> values = new ArrayList<Double>();
        for (long g : gaps) values.add((double) g);
        double mean = MathUtil.mean(values);
        if (mean <= 150D) {
            drain(data, "interval", 0.05D);
            return;
        }
        double cv = MathUtil.stdDev(values) / mean;
        if (cv < sd("interval.max-cv", 0.1D, 0.05D)) {
            if (bump(data, "interval", 1D, i("interval.vl-before-flag", 5))) {
                flag(data, "Interval", "cv=" + MathUtil.round(cv, 3) + " n=" + gaps.size());
            }
        } else {
            drain(data, "interval", 0.05D);
        }
    }

    private void checkMultiTarget(PlayerData data, long now) {
        if (!isSubEnabled("multitarget")) {
            return;
        }
        List<Long[]> hist = data.targetHistory;
        if (hist.size() < 4) {
            return;
        }
        Set<Long> targets = new HashSet<Long>();
        double span = 0D;
        double prevYaw = hist.get(0)[2] / 1000D;
        for (int k = 1; k < hist.size(); k++) {
            double y = hist.get(k)[2] / 1000D;
            span += Math.abs(MathUtil.normalizeYaw(y - prevYaw));
            prevYaw = y;
        }
        for (Long[] h : hist) {
            targets.add(h[1]);
        }
        if (targets.size() >= 2 && span < 45D) {
            int switches = 0;
            long lastSwitchMs = 0L;
            long prevTarget = -1L;
            long maxGap = si("multitarget.switch-gap-ms", 800, 600);
            for (Long[] h : hist) {
                if (prevTarget != -1L && h[1] != prevTarget) {
                    if (lastSwitchMs > 0L && h[0] - lastSwitchMs <= maxGap) {
                        switches++;
                    }
                    lastSwitchMs = h[0];
                }
                prevTarget = h[1];
            }
            if (switches >= 2) {
                if (bump(data, "multitarget", 1D, i("multitarget.vl-before-flag", 5))) {
                    flag(data, "MultiTarget", "targets=" + targets.size() + " yawSpan=" + MathUtil.round(span, 1));
                }
            } else {
                drain(data, "multitarget", 0.05D);
            }
        } else {
            drain(data, "multitarget", 0.05D);
        }
    }

    private boolean exemptReachType(String type) {
        String raw = cfg.s("reach.exempt-entity-types",
                "SLIME,MAGMA_CUBE,GIANT,ENDER_DRAGON,WITHER,GUARDIAN").toUpperCase();
        for (String part : raw.split(",")) {
            if (part.trim().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private void checkReach(AttackContext ctx, EntitySnapshot target) {
        if (!isSubEnabled("reach")) {
            return;
        }
        if (target.entityType != null && exemptReachType(target.entityType)) {
            return;
        }
        PlayerData data = ctx.data;
        double ageTicks = (System.currentTimeMillis() - target.createdMillis) / 50.0D;
        if (target.uuid != null) {
            PlayerData targetData = manager.getDataManager().get(target.uuid);
            if (targetData != null && targetData.movement.initialized) {
                target = new EntitySnapshot(target.id, target.uuid, target.entityType,
                        targetData.movement.lastX, targetData.movement.lastY, targetData.movement.lastZ,
                        target.width, target.height, System.currentTimeMillis(), 0D, 0D, 0D);
                ageTicks = 0D;
            }
        }
        double cap = i("reach.extrapolate-cap-ticks", 10);
        if (ageTicks > cap) {
            ageTicks = cap;
        }
        double tx = target.x + target.vx * ageTicks;
        double ty = target.y + target.vy * ageTicks;
        double tz = target.z + target.vz * ageTicks;
        double halfW = target.width / 2D;
        double targetCenterY = ty + target.height / 2D;
        double rawCenterY = target.y + target.height / 2D;
        double reached = Double.MAX_VALUE;
        double reachedRaw = Double.MAX_VALUE;
        for (double eye : new double[] { EYE_STANDING, EYE_SNEAKING }) {
            reached = Math.min(reached, MathUtil.distanceToAabb(data.movement.lastX,
                    data.movement.lastY + eye, data.movement.lastZ, tx, targetCenterY, tz,
                    halfW, target.height / 2D));
            reachedRaw = Math.min(reachedRaw, MathUtil.distanceToAabb(data.movement.lastX,
                    data.movement.lastY + eye, data.movement.lastZ, target.x, rawCenterY, target.z,
                    halfW, target.height / 2D));
        }
        if (reachedRaw < reached) {
            reached = reachedRaw;
        }
        double ping = data.ping;
        if (target.uuid != null) {
            PlayerData targetData = manager.getDataManager().get(target.uuid);
            ping += targetData.ping;
        }
        double allowed = sd("reach.max-distance", 3.05D, 3.0D)
                + ping * sd("reach.ping-compensation", 0.002D, 0.001D);
        if (reached > allowed) {
            if (bump(data, "reach", 1D, i("reach.vl-before-flag", 5))) {
                flag(data, "Reach", "dist=" + MathUtil.round(reached, 2) + " allow=" + MathUtil.round(allowed, 2));
            }
        } else {
            drain(data, "reach", 0.1D);
        }
    }

    public void checkPendingAngles(PlayerData data, float yaw, float pitch) {
        if (data.pendingAngleTargets.isEmpty()) {
            return;
        }
        if (!isSubEnabled("angle")) {
            return;
        }
        long now = System.currentTimeMillis();
        double exemptDeg = d("angle.turn-exempt-degrees", 25D);
        for (Long[] entry : data.pendingAngleTargets) {
            if (now - entry[1] > 400L) {
                continue;
            }
            double yawAtAttack = entry[2] / 1000D;
            if (Math.abs(MathUtil.normalizeYaw(yaw - yawAtAttack)) > exemptDeg) {
                continue;
            }
            EntitySnapshot target = manager.getEntitySnapshots().get(entry[0].intValue());
            if (target == null) {
                continue;
            }
            if (angleHit(data, target, yaw, pitch)) {
                drain(data, "angle", 0.05D);
            } else if (bump(data, "angle", 1D, i("angle.vl-before-flag", 6))) {
                flagGated(data, "Angle", "crosshair not on hitbox");
            }
        }
        data.pendingAngleTargets.clear();
    }

    private boolean angleHit(PlayerData data, EntitySnapshot target, float yaw, float pitch) {
        if (!data.hasRotation) {
            return true;
        }
        if (target.uuid != null) {
            PlayerData targetData = manager.getDataManager().get(target.uuid);
            if (targetData != null && targetData.movement.initialized) {
                target = new EntitySnapshot(target.id, target.uuid, target.entityType,
                        targetData.movement.lastX, targetData.movement.lastY, targetData.movement.lastZ,
                        target.width, target.height, System.currentTimeMillis(), 0D, 0D, 0D);
            }
        }
        double targetCenterY = target.y + target.height / 2D;
        double halfW = target.width / 2D;
        double halfH = target.height / 2D;
        double pingSum = data.ping;
        if (target.uuid != null) {
            PlayerData targetData = manager.getDataManager().get(target.uuid);
            pingSum += targetData.ping;
        }
        double expand = sd("angle.hit-expand", 0.35D, 0.15D)
                + pingSum * sd("angle.ping-expand", 0.002D, 0.001D);
        for (double eye : new double[] { EYE_STANDING, EYE_SNEAKING }) {
            if (MathUtil.rayIntersectsAabb(data.movement.lastX, data.movement.lastY + eye, data.movement.lastZ,
                    yaw, pitch,
                    target.x - halfW, targetCenterY - halfH, target.z - halfW,
                    target.x + halfW, targetCenterY + halfH, target.z + halfW, expand)) {
                return true;
            }
        }
        return false;
    }

    private void checkThroughWalls(AttackContext ctx, EntitySnapshot target) {
        if (!isSubEnabled("throughwalls")) {
            return;
        }
        PlayerData data = ctx.data;
        if (manager.getMainHandler().getTps() < d("throughwalls.tps-exempt", 15D)) {
            return;
        }
        if (System.currentTimeMillis() - target.createdMillis > 300L) {
            return;
        }
        double speed = Math.sqrt(target.vx * target.vx + target.vz * target.vz);
        if (speed > sd("throughwalls.max-target-speed", 0.6D, 0.4D)) {
            return;
        }
        double ex = data.movement.lastX;
        double ez = data.movement.lastZ;
        double tx = target.x;
        double ty = target.y + target.height / 2D;
        double tz = target.z;
        double hDist = Math.sqrt((tx - ex) * (tx - ex) + (tz - ez) * (tz - ez));
        double minDist = sd("throughwalls.min-distance", 1.5D, 1.2D);
        if (hDist <= minDist) {
            return;
        }
        double maxLen = d("throughwalls.ray-length", 5.0D);
        if (hDist > maxLen) {
            return;
        }
        double minBlockedDistance = sd("throughwalls.min-blocked-distance", 1.0D, 0.7D);
        double minSolidChord = d("throughwalls.min-solid-chord", 0.25D);
        org.bukkit.World world = null;
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(data.getUuid());
        if (player != null && player.isOnline() && player.getWorld() != null) {
            world = player.getWorld();
        }
        if (world == null) {
            return;
        }
        double pRx = data.movement.lastLastX;
        double pRy = data.movement.lastLastY;
        double pRz = data.movement.lastLastZ;
        double tRx = tx - target.vx;
        double tRy = ty - target.vy;
        double tRz = tz - target.vz;
        double blockedAt = 0D;
        final org.bukkit.World fWorld = world;
        RayMarchUtil.OcclusionChecker checker = (bx, by, bz) -> {
            try {
                boolean oc = NmsUtil.isOccluding(fWorld, bx, by, bz);
                if (!oc) {
                    oc = fWorld.getBlockAt(bx, by, bz).getType().isSolid();
                }
                return oc;
            } catch (Exception e) {
                return false;
            }
        };
        for (double[] seg : new double[][] {
                { ex, data.movement.lastY + EYE_STANDING, ez, tx, ty, tz },
                { ex, data.movement.lastY + EYE_SNEAKING, ez, tx, ty, tz },
                { pRx, pRy + EYE_STANDING, pRz, tRx, tRy, tRz } }) {
            double sx = seg[0];
            double sy = seg[1];
            double sz = seg[2];
            double ddx = seg[3] - sx;
            double ddy = seg[4] - sy;
            double ddz = seg[5] - sz;
            double segLen = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            if (segLen < 1e-6) {
                return;
            }
            RayMarchUtil.Result r = RayMarchUtil.march(checker, sx, sy, sz,
                    ddx, ddy, ddz, segLen, minSolidChord);
            if (!r.blocked) {
                return;
            }
            if (blockedAt == 0D || r.blockedAt < blockedAt) {
                blockedAt = r.blockedAt;
            }
        }
        if (blockedAt < minBlockedDistance) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        int window = i("throughwalls.double-trigger-ms", 12000);
        if (nowMs - data.lastThroughWallsTime > window) {
            data.throughWallsBurst = 0;
        }
        data.throughWallsBurst++;
        data.lastThroughWallsTime = nowMs;
        if (data.throughWallsBurst >= 2) {
            data.throughWallsBurst = 0;
            if (bump(data, "throughwalls", 1D, i("throughwalls.vl-before-flag", 3))) {
                flag(data, "ThroughWalls", "hit through block at " + MathUtil.round(blockedAt, 1) + "m");
            }
        }
    }

    private void checkCps(AttackContext ctx) {
        if (!isSubEnabled("cps")) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.ping > i("cps.max-ping", 200)) {
            return;
        }
        long window = i("cps.window-ms", 1000);
        data.attackTimes.add(ctx.time);
        data.attackTimes.removeIf(t -> t < ctx.time - window);
        int cps = data.attackTimes.size();
        int maxCps = si("cps.max-cps", 20, 14);
        if (cps > maxCps) {
            int need = Math.max(1, isStrict() ? 1 : i("cps.required-consecutive-windows", 2));
            if (++data.cpsStreak >= need) {
                data.cpsStreak = 0;
                if (bump(data, "cps", 1D, i("cps.vl-before-flag", 6))) {
                    flag(data, "Cps", "cps=" + cps + " max=" + maxCps);
                }
            } else {
                drain(data, "cps", 0.05D);
            }
        } else {
            data.cpsStreak = 0;
            drain(data, "cps", 0.05D);
        }
    }
}