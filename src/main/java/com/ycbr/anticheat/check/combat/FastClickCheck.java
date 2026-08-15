package com.ycbr.anticheat.check.combat;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;

public final class FastClickCheck extends Check {

    private static final long CLEANUP_WINDOW_MS = 1000L;

    private final FastClickLogic logic = new FastClickLogic();

    public FastClickCheck(AntiCheatManager manager) {
        super(CheckType.FASTCLICK, manager);
    }

    @Override
    protected void onAttack(AttackContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        long now = ctx.time;
        if (data.ping > i("max-ping", 200)) {
            return;
        }
        // 【关键修复】burst 窗口用本检测独立队列 fastClickTimes，不能复用 data.attackTimes——
        // KillAuraCheck.checkCps（cps 默认开）在 onAttack 派发链中先执行并向 attackTimes
        // 写入本次攻击时间戳，双写会让 burst 计数翻倍（实际 N 次攻击数出 2N）。
        data.fastClickTimes.add(now);
        data.fastClickTimes.removeIf(t -> t < now - CLEANUP_WINDOW_MS);
        int window = si("burst-window-ms", 200, 250);
        int burst = 0;
        for (Long t : data.fastClickTimes) {
            if (now - t <= window) {
                burst++;
            }
        }
        int maxBurst = si("burst-count", 6, 5);
        if (burst >= maxBurst) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - data.lastFastClickFlagTime >= si("cooldown-ms", 5000, 3000)) {
                data.lastFastClickFlagTime = nowMs;
                if (bump(data, "fastclick", 1D, i("vl-before-flag", 2))) {
                    flag(data, "FastClick", burst + " attacks in " + window + "ms (cap " + maxBurst + ")");
                }
            }
        } else {
            drain(data, "fastclick", 0.1D);
        }
        // 【关键修复】间隔基准用本检测独立字段 lastFastClickAttackTime，不能复用
        // data.lastAttackTime——KillAuraCheck 在 onAttack 派发链中先执行并已把它更新为
        // 本次攻击时间戳，复用会导致间隔恒为 ~0ms、样本全污染、机械判定必然命中。
        if (data.lastFastClickAttackTime > 0) {
            logic.feed(Math.max(1L, now - data.lastFastClickAttackTime));
            if (logic.sampleCount() >= 40 && logic.mechanicalPattern(
                    d("mechanical.kurtosis-max", -1.5D),
                    d("mechanical.max-mean-interval-ms", 120.0D))) {
                if (bump(data, "mechanical", 1D, i("mechanical.vl-before-flag", 3))) {
                    flag(data, "Mechanical", "kurtosis/entropy click rhythm");
                }
            }
        }
        data.lastFastClickAttackTime = now;
    }
}