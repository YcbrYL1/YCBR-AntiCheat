package com.ycbr.anticheat.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VelocityLedgerTest {

    @Test
    void enqueueAndConsume_directionMatch_consumes() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.consume(0.8, 0.0, 11);
        assertEquals(0, l.unconsumedCount(11, 12), "方向匹配应消耗");
        assertTrue(l.isAllConsumed());
    }

    @Test
    void zeroMovement_notConsumed() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.consume(0.0, 0.0, 11);
        assertFalse(l.isAllConsumed(), "零位移不应消耗");
        assertEquals(1, l.unconsumedCount(25, 12), "超窗后应计为未消费");
    }

    @Test
    void oppositeDirection_notConsumed() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.consume(-0.8, 0.0, 11);
        assertFalse(l.isAllConsumed(), "反向位移不应消耗");
        assertEquals(1, l.unconsumedCount(25, 12), "超窗后应计为未消费");
    }

    @Test
    void tooSmallMovement_notConsumed() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.consume(0.1, 0.0, 11);
        assertFalse(l.isAllConsumed(), "位移过小不应消耗");
        assertEquals(1, l.unconsumedCount(25, 12), "超窗后应计为未消费");
    }

    @Test
    void multiEnqueue_separateEntries() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.enqueue(0.0, 1.0, 10);
        l.consume(0.8, 0.0, 11);
        assertFalse(l.isAllConsumed(), "只应消耗方向匹配的一条");
        assertEquals(1, l.unconsumedCount(25, 12), "另一条超窗后应计数");
    }

    @Test
    void unconsumed_afterWindow_counts() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        assertEquals(0, l.unconsumedCount(21, 12), "窗口内不计数");
        assertEquals(1, l.unconsumedCount(23, 12), "超过窗口应计数");
    }

    @Test
    void prune_removesOldEntries() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.prune(10 + 30, 25);
        assertEquals(0, l.unconsumedCount(40, 12), "超龄条目应被清理");
    }

    @Test
    void consumedEntry_notCountedEvenAfterWindow() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        l.consume(0.8, 0.0, 11);
        assertEquals(0, l.unconsumedCount(30, 12), "已消费条目永不计数");
    }

    @Test
    void decay_reducesExpectedOverTicks() {
        VelocityLedger l = new VelocityLedger();
        l.enqueue(1.0, 0.0, 10);
        // 到达 3 tick 后期望位移 = 1.0 * 0.91^2 = 0.8281，35% 消费线 = 0.29
        l.consume(0.3, 0.0, 13);
        assertEquals(0, l.unconsumedCount(13, 12), "衰减后较小位移也应消费");
        assertTrue(l.isAllConsumed());
    }
}