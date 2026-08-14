package com.ycbr.anticheat.data;

import java.util.EnumMap;
import java.util.Map;

import com.ycbr.anticheat.check.CheckType;

/**
 * ImproBable 跨检测融合桶（Phase 10，P2-9，学 NCP Improbable）。
 *
 * <p>各检测的亚阈值小违规（{@code Check.bump} 未达 vl-before-flag）按类别喂入
 * 每玩家双窗频率桶：短窗抓突发、长窗抓持续性，<b>短窗与长窗同时超阈且覆盖
 * 至少 min-categories 个类别</b>才升级（触发后命中类别短桶清零防重复 flag）。
 * 单次毛刺、单类别波动均不触发。</p>
 *
 * <p>与服务器级全局熔断（MainThreadHandler.checkFuse，误报风暴保护）语义不同：
 * 本类是每玩家信号融合升 VL，两者共存。</p>
 *
 * <p>线程安全：{@code feed} 由 actor 线程调用（每玩家串行），{@code hotAndReset}
 * 由主线程每 tick 调用，均同步。</p>
 */
public final class ImprobableTracker {

    public enum Category {
        COMBAT, MOVEMENT, PROTOCOL
    }

    /** 短窗环形桶槽数（20 tick ≈ 1 秒）。 */
    public static final int SHORT_SIZE = 20;
    /** 长窗环形桶槽数（200 tick ≈ 10 秒）。 */
    public static final int LONG_SIZE = 200;

    private static final Map<CheckType, Category> TYPE_CATEGORY = new EnumMap<CheckType, Category>(CheckType.class);

    static {
        TYPE_CATEGORY.put(CheckType.KILLAURA, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.SCAFFOLD, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.CRITICALS, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.NOSWING, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.AUTOTOOL, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.INSTANTBOW, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.FASTCLICK, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.REACH, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.AIMSTAT, Category.COMBAT);
        TYPE_CATEGORY.put(CheckType.SPEED, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.VELOCITY, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.FLY, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.NOFALL, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.NOSLOW, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.SIMULATION, Category.MOVEMENT);
        TYPE_CATEGORY.put(CheckType.TIMER, Category.PROTOCOL);
        TYPE_CATEGORY.put(CheckType.WRONGTURN, Category.PROTOCOL);
        TYPE_CATEGORY.put(CheckType.BLINK, Category.PROTOCOL);
        TYPE_CATEGORY.put(CheckType.BADPACKET, Category.PROTOCOL);
        TYPE_CATEGORY.put(CheckType.FASTTHROW, Category.PROTOCOL);
        TYPE_CATEGORY.put(CheckType.SPRINT, Category.PROTOCOL);
    }

    private final int[] shortRing = new int[Category.values().length * SHORT_SIZE];
    private final int[] longRing = new int[Category.values().length * LONG_SIZE];
    private int lastTick;

    /** 类别映射；未映射类型（如 IMPROBABLE 自身）默认 PROTOCOL（中性桶）。 */
    public static Category categoryOf(CheckType type) {
        Category c = TYPE_CATEGORY.get(type);
        return c != null ? c : Category.PROTOCOL;
    }

    /** 喂入一票亚阈值小违规（actor 线程，每玩家串行）。 */
    public synchronized void feed(CheckType type, int tick) {
        advance(tick);
        Category cat = categoryOf(type);
        shortRing[cat.ordinal() * SHORT_SIZE + tick % SHORT_SIZE]++;
        longRing[cat.ordinal() * LONG_SIZE + tick % LONG_SIZE]++;
    }

    /** 短窗计数（主线程读）。 */
    public synchronized int shortCount(Category cat, int tick) {
        advance(tick);
        return windowSum(shortRing, cat.ordinal() * SHORT_SIZE, SHORT_SIZE, tick);
    }

    /** 长窗计数（主线程读）。 */
    public synchronized int longCount(Category cat, int tick) {
        advance(tick);
        return windowSum(longRing, cat.ordinal() * LONG_SIZE, LONG_SIZE, tick);
    }

    /**
     * 融合判定：≥ minCategories 个类别短窗/长窗同时超阈 → true，并把命中类别
     * 短桶清零（长桶保留），防止持续作弊每 tick 重复 flag。默认关由调用方 gate。
     */
    public synchronized boolean hotAndReset(int nowTick, int shortTicks, int shortThreshold,
            int longTicks, int longThreshold, int minCategories) {
        advance(nowTick);
        int hits = 0;
        for (Category cat : Category.values()) {
            int shortSum = windowSum(shortRing, cat.ordinal() * SHORT_SIZE, SHORT_SIZE, nowTick);
            int longSum = windowSum(longRing, cat.ordinal() * LONG_SIZE, LONG_SIZE, nowTick);
            if (shortSum >= shortThreshold && longSum >= longThreshold) {
                hits++;
                if (shortTicks >= SHORT_SIZE) {
                    java.util.Arrays.fill(shortRing, cat.ordinal() * SHORT_SIZE,
                            cat.ordinal() * SHORT_SIZE + SHORT_SIZE, 0);
                } else {
                    clearWindow(shortRing, cat.ordinal() * SHORT_SIZE, SHORT_SIZE, nowTick, shortTicks);
                }
            }
        }
        return hits >= minCategories;
    }

    /** 把桶推进到 nowTick：滑出窗口的旧槽清零；跳变过大直接全清。 */
    private void advance(int tick) {
        int delta = tick - lastTick;
        if (delta <= 0) {
            return;
        }
        if (delta >= LONG_SIZE) {
            java.util.Arrays.fill(shortRing, 0);
            java.util.Arrays.fill(longRing, 0);
            lastTick = tick;
            return;
        }
        for (int t = lastTick + 1; t <= tick; t++) {
            for (Category c : Category.values()) {
                shortRing[c.ordinal() * SHORT_SIZE + t % SHORT_SIZE] = 0;
                longRing[c.ordinal() * LONG_SIZE + t % LONG_SIZE] = 0;
            }
        }
        lastTick = tick;
    }

    /** 统计 [tick-windowTicks+1, tick] 窗口内的票数（环形桶按 tick 模槽位累加）。 */
    private static int windowSum(int[] ring, int base, int size, int tick) {
        int sum = 0;
        for (int i = 0; i < size; i++) {
            sum += ring[base + i];
        }
        return sum;
    }

    /** 清零最近 windowTicks 个槽（按 tick 模槽位）。 */
    private static void clearWindow(int[] ring, int base, int size, int tick, int windowTicks) {
        for (int t = tick - windowTicks + 1; t <= tick; t++) {
            ring[base + t % size] = 0;
        }
    }
}