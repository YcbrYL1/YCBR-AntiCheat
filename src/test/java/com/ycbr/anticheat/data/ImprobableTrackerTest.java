package com.ycbr.anticheat.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ycbr.anticheat.check.CheckType;

class ImprobableTrackerTest {

    private static final int SHORT_TICKS = 20;
    private static final int LONG_TICKS = 200;

    @Test
    void feed_countsInWindow() {
        ImprobableTracker t = new ImprobableTracker();
        for (int i = 0; i < 5; i++) {
            t.feed(CheckType.SIMULATION, i + 1);
        }
        assertEquals(5, t.shortCount(ImprobableTracker.Category.MOVEMENT, 10), "短窗应计数 5");
        assertEquals(5, t.longCount(ImprobableTracker.Category.MOVEMENT, 50), "长窗应计数 5");
    }

    @Test
    void windowSlides_oldTicketsExpire() {
        ImprobableTracker t = new ImprobableTracker();
        for (int i = 0; i < 5; i++) {
            t.feed(CheckType.SIMULATION, i + 1);
        }
        // tick 30：早于 20 的票滑出 20-tick 短窗，但仍在 200-tick 长窗
        assertEquals(0, t.shortCount(ImprobableTracker.Category.MOVEMENT, 30), "旧票应滑出短窗");
        assertEquals(5, t.longCount(ImprobableTracker.Category.MOVEMENT, 30), "旧票仍在长窗");
    }

    @Test
    void singleCategory_notHot() {
        ImprobableTracker t = new ImprobableTracker();
        // 一个类别连续 40 tick 每 tick 1 票：短窗 20 长窗 40，均超阈，但只有 1 个类别
        for (int i = 1; i <= 40; i++) {
            t.feed(CheckType.SIMULATION, i);
        }
        assertFalse(t.hotAndReset(40, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "单类别不应触发");
    }

    @Test
    void twoCategories_hot() {
        ImprobableTracker t = new ImprobableTracker();
        for (int i = 1; i <= 40; i++) {
            t.feed(CheckType.SIMULATION, i);
            t.feed(CheckType.KILLAURA, i);
        }
        assertTrue(t.hotAndReset(40, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "两类别同时双超应触发");
    }

    @Test
    void longOnly_notHot() {
        ImprobableTracker t = new ImprobableTracker();
        // 长期慢速违规：tick 1-30 单类别 30 票 → tick 40 时短窗 0 票、长窗 30 票
        for (int i = 1; i <= 30; i++) {
            t.feed(CheckType.SIMULATION, i);
        }
        assertFalse(t.hotAndReset(40, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "短窗未超不应触发");
    }

    @Test
    void shortOnly_notHot() {
        ImprobableTracker t = new ImprobableTracker();
        // 突发：单 tick 双类别各 10 票 → 短窗超，长窗不足
        for (int i = 0; i < 10; i++) {
            t.feed(CheckType.SIMULATION, 5);
            t.feed(CheckType.KILLAURA, 5);
        }
        assertFalse(t.hotAndReset(5, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "长窗未超不应触发");
    }

    @Test
    void resetClearsShortWindow() {
        ImprobableTracker t = new ImprobableTracker();
        for (int i = 1; i <= 40; i++) {
            t.feed(CheckType.SIMULATION, i);
            t.feed(CheckType.KILLAURA, i);
        }
        assertTrue(t.hotAndReset(40, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "前置：双类别触发");
        assertEquals(0, t.shortCount(ImprobableTracker.Category.MOVEMENT, 41), "触发后短桶清零");
        assertEquals(0, t.shortCount(ImprobableTracker.Category.COMBAT, 41), "另一命中类别短桶也清零");
        assertTrue(t.longCount(ImprobableTracker.Category.MOVEMENT, 41) > 0, "长桶保留");
        assertFalse(t.hotAndReset(41, SHORT_TICKS, 6, LONG_TICKS, 30, 2), "清零后不再重复触发");
    }

    @Test
    void categoryMapping_correct() {
        assertEquals(ImprobableTracker.Category.COMBAT, ImprobableTracker.categoryOf(CheckType.KILLAURA));
        assertEquals(ImprobableTracker.Category.MOVEMENT, ImprobableTracker.categoryOf(CheckType.SIMULATION));
        assertEquals(ImprobableTracker.Category.PROTOCOL, ImprobableTracker.categoryOf(CheckType.TIMER));
        assertEquals(ImprobableTracker.Category.PROTOCOL, ImprobableTracker.categoryOf(CheckType.SPRINT));
    }

    @Test
    void unknownType_defaultsToProtocol() {
        assertEquals(ImprobableTracker.Category.PROTOCOL,
                ImprobableTracker.categoryOf(CheckType.IMPROBABLE), "未映射类型默认 PROTOCOL");
    }
}