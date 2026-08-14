package com.ycbr.anticheat.simulation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 击退速度账本（学 NCP SimpleAxisVelocity）：把服务端发出的击退水平向量入队，
 * 玩家位移按方向/量级匹配消耗，识别"发出但从未消费"的击退（绕过指纹）。
 *
 * <p>语义（纯逻辑，tick 时间轴，可单测）：
 * <ul>
 *   <li>只做水平（vx/vz）：垂直分量落地吸收场景无法区分"吸收"与"绕过"，交给
 *       Vertical 检测。</li>
 *   <li>到达前不计数：条目在 arrivalTick（事务推算的到达 tick）之前不算未消费。</li>
 *   <li>消费条件：位移方向与该击退方向 dot >= directionDot，且位移模长
 *       >= 衰减后期望 * minConsumeRatio。正常被击飞满足；绕过者零/反向/过小位移不满足。</li>
 *   <li>墙截断位移会误判：调用方必须在无墙/无天花板场景才计数。</li>
 * </ul>
 */
public final class VelocityLedger {

    /** 水平摩擦衰减基（与 NMS motX *= 0.91 一致，集中定义于 PhysicsConstants）。 */
    public static final double HORIZONTAL_DECAY = PhysicsConstants.AIR_FRICTION;
    /** 方向匹配最低 dot（位移与击退方向夹角的余弦下界）。 */
    public static final double DIRECTION_DOT = 0.6D;
    /** 消费所需最低位移比例（相对衰减后期望）。 */
    public static final double MIN_CONSUME_RATIO = 0.35D;

    public static final class Entry {
        final double vx;
        final double vz;
        final int arrivalTick;
        boolean consumed;

        Entry(double vx, double vz, int arrivalTick) {
            this.vx = vx;
            this.vz = vz;
            this.arrivalTick = arrivalTick;
        }

        public double expectedAt(int tick) {
            int elapsed = Math.max(0, tick - arrivalTick);
            return Math.sqrt(vx * vx + vz * vz) * Math.pow(HORIZONTAL_DECAY, Math.min(20, elapsed));
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    public synchronized void enqueue(double vx, double vz, int arrivalTick) {
        entries.add(new Entry(vx, vz, arrivalTick));
    }

    /** 玩家一次位移尝试消费：方向与量级双条件匹配才消耗。 */
    public synchronized void consume(double dx, double dz, int tick) {
        double moveLen = Math.sqrt(dx * dx + dz * dz);
        if (moveLen < 1.0E-4D) {
            return;
        }
        Entry best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entry e : entries) {
            if (e.consumed || tick <= e.arrivalTick) {
                continue;
            }
            double kbLen = Math.sqrt(e.vx * e.vx + e.vz * e.vz);
            if (kbLen < 1.0E-4D) {
                continue;
            }
            double dot = (dx * e.vx + dz * e.vz) / (moveLen * kbLen);
            if (dot < DIRECTION_DOT) {
                continue;
            }
            if (moveLen < e.expectedAt(tick) * MIN_CONSUME_RATIO) {
                continue;
            }
            // 取最匹配（dot 最大）的条目消耗，避免重复消耗
            if (dot > bestScore) {
                bestScore = dot;
                best = e;
            }
        }
        if (best != null) {
            best.consumed = true;
        }
    }

    /** 到达后超过 windowTicks 仍未消费的条目数（含已过期但未清理的）。 */
    public synchronized int unconsumedCount(int tick, int windowTicks) {
        int count = 0;
        for (Entry e : entries) {
            if (!e.consumed && tick - e.arrivalTick > windowTicks) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean isAllConsumed() {
        for (Entry e : entries) {
            if (!e.consumed) {
                return false;
            }
        }
        return true;
    }

    /** 清理超龄条目（超过 maxAgeTicks 未消费的丢弃，防无限增长）。 */
    public synchronized void prune(int tick, int maxAgeTicks) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.consumed || tick - e.arrivalTick > maxAgeTicks) {
                it.remove();
            }
        }
    }
}