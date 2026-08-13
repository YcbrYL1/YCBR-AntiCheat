package com.ycbr.anticheat.check.protocol;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.util.MathUtil;

public final class TimerCheck extends Check {

    private static final long WINDOW_MS = 6000L;

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
        if (manager.getMainHandler().getTps() < d("min-tps", 15D)) {
            return;
        }
        long now = ctx.arrivalTime;
        boolean same = ctx.x == data.movement.lastLastX && ctx.y == data.movement.lastLastY
                && ctx.z == data.movement.lastLastZ;
        data.samePosStreak = same ? data.samePosStreak + 1 : 0;
        if (data.samePosStreak > data.samePosPeak) {
            data.samePosPeak = data.samePosStreak;
        }
        data.moveTimes.addLast(now);
        while (!data.moveTimes.isEmpty() && now - data.moveTimes.peekFirst() > WINDOW_MS) {
            data.moveTimes.pollFirst();
        }
        double eps = data.moveTimes.size() / (WINDOW_MS / 1000.0D);
        if (eps > sd("max-eps", 22D, 20D)) {
            if (bump(data, "timer", 1D, i("vl-before-flag", 5))) {
                flag(data, "Timer", "eps=" + MathUtil.round(eps, 1) + " (6s window) dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timer", 0.05D);
        }
        long cutoff = now - 2000L;
        int shortCount = 0;
        for (long t : data.moveTimes) {
            if (t > cutoff) {
                shortCount++;
            }
        }
        double epsShort = shortCount / 2.0D;
        if (epsShort > sd("max-eps-short", 24D, 21D)) {
            if (bump(data, "timer", 1D, i("vl-before-flag", 5))) {
                flag(data, "TimerShort", "eps2s=" + MathUtil.round(epsShort, 1) + " dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timer", 0.03D);
        }
        long burstMs = si("burst-ms", 500, 400);
        data.burstTimes.addLast(now);
        while (!data.burstTimes.isEmpty() && now - data.burstTimes.peekFirst() > burstMs) {
            data.burstTimes.pollFirst();
        }
        double burstEps = data.burstTimes.size() / (burstMs / 1000.0D);
        if (burstEps > sd("max-burst-eps", 22D, 20D)) {
            boolean sustained = data.lastBurstExceedMs > 0L && now - data.lastBurstExceedMs < burstMs;
            data.lastBurstExceedMs = now;
            if (bump(data, "timerburst", sustained ? 1D : 0.5D, i("burst-vl-before-flag", 3))) {
                flag(data, "TimerBurst", "burst=" + MathUtil.round(burstEps, 1) + "/s ("
                        + burstMs + "ms window) dup=" + data.samePosPeak);
            }
        } else {
            drain(data, "timerburst", 0.05D);
            data.lastBurstExceedMs = 0L;
        }
    }
}
