package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;

public final class TimerCheck extends Check {

    private final TimerLogic longWindow = new TimerLogic();
    private final TimerLogic shortWindow = new TimerLogic();
    private final TimerLogic burstWindow = new TimerLogic();

    public TimerCheck(AntiCheatManager manager) {
        super(CheckType.TIMER, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) {
            return;
        }
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.dead || data.ping > cfg.maxPing()) {
            return;
        }
        if (data.transaction == null) {
            return; // 事务未初始化，跳过
        }
        if (manager.getMainHandler().getTps() < d("min-tps", 15D)) {
            return;
        }

        // 间隔测量：与 ping 解耦——包到达抖动不改变"相邻包覆盖的服务器 tick 数"均值
        int serverTick = manager.getMainHandler().currentServerTick();
        int interval = 1;
        if (data.lastMoveServerTick > 0) {
            interval = Math.max(0, serverTick - data.lastMoveServerTick);
        }
        data.lastMoveServerTick = serverTick;

        boolean same = ctx.x == data.movement.lastLastX && ctx.y == data.movement.lastLastY
                && ctx.z == data.movement.lastLastZ;
        data.samePosStreak = same ? data.samePosStreak + 1 : 0;
        if (data.samePosStreak > data.samePosPeak) {
            data.samePosPeak = data.samePosStreak;
        }

        int longWin = si("window-size", 60, 40);
        double longAvg = sd("min-avg", 0.95, 0.97);
        if (longWindow.feed(interval, longWin, longAvg)) {
            if (bump(data, "timer", 1D, i("vl-before-flag", 5))) {
                flag(data, "Timer", "avgInterval=" + String.format("%.3f", longWindow.lastAverage())
                        + " window=" + longWin + " dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timer", 0.05D);
        }

        int shortWin = si("short-window-size", 25, 20);
        double shortAvg = sd("short-min-avg", 0.97, 0.98);
        if (shortWindow.feed(interval, shortWin, shortAvg)) {
            if (bump(data, "timer", 1D, i("vl-before-flag", 5))) {
                flag(data, "TimerShort", "avgInterval=" + String.format("%.3f", shortWindow.lastAverage())
                        + " window=" + shortWin + " dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timer", 0.03D);
        }

        int burstWin = si("burst-window-size", 10, 8);
        double burstAvg = sd("burst-min-avg", 0.85, 0.88);
        if (burstWindow.feed(interval, burstWin, burstAvg)) {
            boolean sustained = data.lastBurstExceedMs > 0L
                    && ctx.arrivalTime - data.lastBurstExceedMs < si("burst-window-ms", 500, 400);
            data.lastBurstExceedMs = ctx.arrivalTime;
            if (bump(data, "timerburst", sustained ? 1D : 0.5D, i("burst-vl-before-flag", 3))) {
                flag(data, "TimerBurst", "avgInterval=" + String.format("%.3f", burstWindow.lastAverage())
                        + " window=" + burstWin + " dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timerburst", 0.05D);
            data.lastBurstExceedMs = 0L;
        }
    }
}