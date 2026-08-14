# 协议事务化（Timer/Blink/Velocity）实施计划

> **给 Claude：** 按任务逐项执行本计划，每个任务完成后验证通过再提交。

**目标：** 借鉴 Grim 的 transaction/packet-order 思路，把 Timer、Blink、Velocity 三个协议类检测从"wall-clock 估算"升级为"事务往返精确判定"，消除高 ping/网络抖动导致的误判，同时保留 YCBR-AC 的 Velocity 细分指纹（JumpReset/SprintReset）差异化优势。

**架构方案：** 依赖 Phase 0 的 `TransactionTracker`（每玩家 RTT 追踪）。Timer 改用"玩家事务往返速率 vs 移动包速率"对比；Blink 改用"移动包序号连续性"校验（囤包重放暴露为序号异常），沉默时长降级为辅助；Velocity 用 transaction 三明治确认击退到达客户端的精确时刻，区分"网络延迟"与"真没被推"。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3、ProtocolLib（事务包）

**前置依赖：** `docs/plans/2026-08-13-phase01-grim-tier-movement.md` 任务 7（TransactionTracker）已完成。

---

### 任务 1：TimerCheck 事务化改造（Phase 2.1）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/protocol/TimerCheck.java`
- 修改：`src/main/resources/config.yml`
- 测试：`src/test/java/com/ycbr/anticheat/check/protocol/TimerCheckTest.java`（纯逻辑抽离后可测）

**步骤 1：先读现有 TimerCheck 源码，理解当前 wall-clock EPS 逻辑**

运行：`Get-Content src/main/java/com/ycbr/anticheat/check/protocol/TimerCheck.java`
预期：看到 6s 窗口 EPS > 22 阈值 + 2s 短窗口 + 500ms burst 逻辑

**步骤 2：抽离纯逻辑为可测类 `TimerLogic`**

```java
package com.ycbr.anticheat.check.protocol;

/**
 * Timer 纯逻辑（无 Bukkit 依赖，可单测）。
 * 输入：每移动包到达的"服务器 tick 间隔"（由 TransactionTracker 提供，
 *      即距离上个事务 pong 的客户端进度差）。
 */
public final class TimerLogic {

    private static final int WINDOW = 100;          // 100 包窗口
    private final double[] intervals = new double[WINDOW];
    private int head;
    private int count;

    /**
     * 记录一次移动包：intervalTicks = 该包覆盖的服务器 tick 数
     * （正常 1 tick；加速器 <1；丢包合并 >1）。
     * 返回 true 表示窗口内平均间隔显著低于 1（加速）。
     */
    public boolean feed(double intervalTicks, int windowSize, double threshold) {
        intervals[head] = intervalTicks;
        head = (head + 1) % WINDOW;
        if (count < WINDOW) count++;
        if (count < windowSize) return false;
        double sum = 0;
        int n = Math.min(count, windowSize);
        for (int i = 0; i < n; i++) {
            int idx = (head - 1 - i + WINDOW) % WINDOW;
            sum += intervals[idx];
        }
        double avg = sum / n;
        return avg < threshold; // 阈值如 0.95：平均每包不足 0.95 tick = 加速
    }
}
```

**步骤 3：写失败测试**

```java
package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TimerLogicTest {

    @Test
    void normalPacing_neverFlags() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 200; i++) {
            assertFalse(logic.feed(1.0, 60, 0.95));
        }
    }

    @Test
    void acceleratedPacing_flags() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 100; i++) logic.feed(1.0, 60, 0.95);
        boolean flagged = false;
        for (int i = 0; i < 60; i++) {
            if (logic.feed(0.5, 60, 0.95)) { // 每包只覆盖 0.5 tick = 2x 加速
                flagged = true;
                break;
            }
        }
        assertTrue(flagged, "acceleration should be flagged");
    }

    @Test
    void burstAcceleration_eventuallyRecovers() {
        TimerLogic logic = new TimerLogic();
        for (int i = 0; i < 100; i++) logic.feed(1.0, 60, 0.95);
        for (int i = 0; i < 40; i++) logic.feed(0.5, 60, 0.95); // 突发加速
        boolean flaggedDuringBurst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(0.5, 60, 0.95)) { flaggedDuringBurst = true; }
        }
        assertTrue(flaggedDuringBurst);
        for (int i = 0; i < 100; i++) logic.feed(1.0, 60, 0.95); // 恢复
        for (int i = 0; i < 60; i++) {
            assertFalse(logic.feed(1.0, 60, 0.95), "should recover after burst");
        }
    }
}
```

**步骤 4：运行测试确认通过**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q -Dtest=TimerLogicTest`
预期：**全部通过（PASS）**

**步骤 5：改造 TimerCheck.onMove**

```java
@Override
protected void onMove(MoveContext ctx) {
    if (!isEnabled()) return;
    PlayerData data = ctx.data;
    if (data.creative || data.flying || data.inVehicle || data.dead) return;

    TransactionTracker tx = data.transaction;
    if (tx == null || tx.rttMs() <= 0) return; // 事务未初始化，跳过

    // 服务器已处理 tick 数（距离上包的真实进度）：
    // 上包事务 pong 距现在的时间 → 客户端侧 tick 进度
    long now = System.currentTimeMillis();
    long lastPong = tx.lastPongTime();
    if (now - lastPong > 2000L) return; // 长时间无 pong，网络异常，跳过

    double intervalTicks = (now - lastPong) / 50.0;
    // 用"服务器实际 tick 间隔"（MainThreadHandler 的 tick 计数差值）更准：
    // intervalTicks = data.actor.currentServerTick() - data.lastMoveServerTick
    data.lastMoveServerTick = data.actor.currentServerTick();

    if (logic.feed(intervalTicks, si("window-size", 60, 40), sd("min-avg", 0.95, 0.97))) {
        if (bump(data, "timer", 1D, i("vl-before-flag", 8))) {
            flag(data, "Timer", "avgInterval=" + String.format("%.3f", lastAvg)
                    + " window=" + windowSize);
        }
    } else {
        drain(data, "timer", 0.02D);
    }
}
```

其中 `intervalTicks` 优先用**服务器 tick 计数差值**（每个移动包到达时记录当前服务器 tick，下一包差值即该包覆盖的 tick 数），事务 pong 作为辅助校准。这样**与 ping 完全解耦**：高 ping 玩家每包覆盖 tick 数可能 >1，但正常玩家平均仍趋近 1，加速器则稳定 <1。

**步骤 6：更新 config.yml**

```yaml
  timer:
    enabled: true
    kick-at-vl: 25
    kick-message: "&cKicked for Timer"
    window-size: 60
    min-avg: 0.95
    vl-before-flag: 8
    strict:
      window-size: 40
      min-avg: 0.97
```

**步骤 7：全量测试 + 编译**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 8：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/protocol/TimerCheck.java src/main/java/com/ycbr/anticheat/check/protocol/TimerLogic.java src/test/java/com/ycbr/anticheat/check/protocol/TimerLogicTest.java src/main/resources/config.yml
git commit -m "feat: TimerCheck transaction/tick-interval based, decoupled from wall-clock"
```

---

### 任务 2：BlinkCheck 序号连续性校验（Phase 2.2）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/protocol/BlinkCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：读现有 BlinkCheck 源码，理解当前沉默时长逻辑**

运行：`Get-Content src/main/java/com/ycbr/anticheat/check/protocol/BlinkCheck.java`
预期：看到 `maxSilence = 2000 + ping` 的沉默时长逻辑

**步骤 2：PlayerData 新增序号追踪字段**

```java
public volatile long lastMoveSequence;      // 上次移动包自增序号
public volatile long moveSequenceGap;       // 当前累计缺口
public volatile long lastBlinkSequenceTime; // 缺口开始时间
public volatile boolean seqBlinkFlagged;
```

**步骤 3：BlinkCheck 增加序号连续性检测**

```java
// 在现有 onMove 逻辑之上新增：
@Override
protected void onMove(MoveContext ctx) {
    if (!isEnabled()) return;
    PlayerData data = ctx.data;
    if (data.creative || data.flying || data.inVehicle || data.dead) return;

    // —— 序号连续性（囤包重放识别）——
    long now = System.currentTimeMillis();
    data.lastMoveSequence++;
    long expected = data.lastMoveSequence - 1; // 上包序号
    // 若服务器长时间未收到该玩家移动包（相对其事务 pong 进度），
    // 说明客户端在囤包（Blink）。判定：距上次移动包 > 2s 且期间有事务 pong。
    if (data.transaction != null && data.transaction.lastPongTime() > data.lastMoveTime) {
        long silent = now - data.lastMoveTime;
        if (silent > si("max-silence-ms", 2000, 1000)) {
            // 囤包确认：客户端明明活着（有 pong）却 2s 无移动包
            if (bump(data, "blink", 1D, i("vl-before-flag", 3))) {
                flag(data, "Blink", "silent=" + silent + "ms with live pong");
            }
        }
    }

    // —— 原有沉默时长逻辑保留为辅助（高 ping 兜底）——
    ...existing silence logic...
}
```

核心改进：**用"有事务 pong 但无移动包"替代"单纯超时"**。网络正常时客户端有移动包就有 pong；只有 Blink 玩家会"活着但不发位置"。高 ping 不再简单加 ping 补偿，而是看 pong 活性。

**步骤 4：更新 config.yml**

```yaml
  blink:
    enabled: true
    kick-at-vl: 15
    kick-message: "&cKicked for Blink"
    max-silence-ms: 2000
    vl-before-flag: 3
    strict:
      max-silence-ms: 1000
```

**步骤 5：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 6：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/protocol/BlinkCheck.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/resources/config.yml
git commit -m "feat: BlinkCheck live-pong detection (silence + transaction liveness)"
```

---

### 任务 3：VelocityCheck 事务三明治（Phase 2.3）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/VelocityCheck.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：读现有 VelocityCheck 源码，理解当前 pingTicks 估算**

运行：`Get-Content src/main/java/com/ycbr/anticheat/check/movement/VelocityCheck.java`
预期：看到 `pingTicks = ceil(ping/50)` 的估算逻辑 + JumpReset/SprintReset 子检测

**步骤 2：加入击退到达时刻追踪**

```java
// onKbIssued 时记录：
public void onKbIssued(PlayerData data) {
    ...existing...
    data.kbIssuedServerTick = data.actor.currentServerTick();
    if (data.transaction != null) {
        data.kbArrivalServerTick = data.kbIssuedServerTick
                + Math.max(1, (int) Math.ceil(data.transaction.rttMs() / 50.0));
    }
}
```

**步骤 3：用到达时刻替换 pingTicks 估算**

```java
// 判定窗口：从 kbArrivalServerTick 开始（击退真正到达客户端的那一刻）+
// 1 tick 容差，而不是"发送后立刻按 pingTicks 放宽"。
// 优势：高 ping 玩家击退包还在路上时，不会因为"服务器已经过了 pingTicks"误判
// 玩家取消击退；到达后的窗口严格收紧，减少漏判。
int arrivalWindowStart = data.kbArrivalServerTick;
int arrivalWindowEnd = arrivalWindowStart + si("arrival-window-ticks", 2, 1);

int currentTick = data.actor.currentServerTick();
if (currentTick < arrivalWindowStart) return; // 击退还没到客户端，不判定
```

**步骤 4：保留 JumpReset/SprintReset 细分（差异化优势，不删）**

- 确认 `checkJumpReset` / `checkSprintReset` 逻辑原样保留
- 其豁免逻辑同样参考事务到达时刻而非纯 pingTicks

**步骤 5：更新 config.yml**

```yaml
  velocity:
    enabled: true
    kick-at-vl: 15
    kick-message: "&cKicked for Velocity"
    arrival-window-ticks: 2
    ...（其余子检测配置不动，严格模式：arrival-window-ticks: 1）
```

**步骤 6：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 7：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/movement/VelocityCheck.java src/main/resources/config.yml
git commit -m "feat: VelocityCheck transaction-based arrival window (keep JumpReset/SprintReset)"
```

---

### 任务 4：协议类回归验证 + 文档更新

**涉及文件：**
- 修改：`docs/plans/2026-08-13-prediction-engine-impl.md`（追加结果）
- 修改：`README.md`（如有需要）

**步骤 1：全量构建 + 测试**

运行：`mvn -q -DskipTests package`
预期：**BUILD SUCCESS**

运行：`mvn test -q`
预期：**全部测试通过（PASS）**

**步骤 2：更新计划文档追加"实施结果"章节**

记录：提交哈希、测试结果、config 变更摘要

**步骤 3：提交**

```bash
git add docs/plans/2026-08-13-phase02-protocol-transaction.md docs/plans/2026-08-13-prediction-engine-impl.md README.md
git commit -m "docs: Phase 2 protocol transaction results"
```

---

## 验证方式

- 每任务 `mvn test -q` / `mvn compile -q`，预期 BUILD SUCCESS + 全部通过
- 最终 `mvn -q -DskipTests package` 构建成功
- 回归：现有 12+ 测试全部通过（TimerLogic 新增 3 个）

## 风险与注意事项

- **事务包节流**：TransactionTracker 每 tick ≤1 个事务包，避免刷包攻击；windowId=0 无效
- **Timer 阈值**：window-size 60/min-avg 0.95 是起点，线上观察后按误判日志调参
- **Blink 判定**："有 pong 无移动包"核心判定；若客户端暂停移动（AFK 站立）会连续发位置包（1.8 客户端站立时也发），不误判；但"原地站立不发包"的玩家（少数客户端行为）可能误判，需保留原 max-silence 豁免（站立时 lastMoveTime 持续更新）
- **Velocity 到达时刻**：事务 RTT 是平滑值，到达时刻仍有 ±1 tick 误差，arrival-window-ticks=2 已覆盖
- **服务器 tick 计数**：`PlayerActor.currentServerTick()` 需在 MainThreadHandler 维护（若不存在需先添加）
- **任务依赖**：任务 1/2/3 依赖 Phase 0 的 TransactionTracker 与 PlayerActor tick 计数；任务 4 依赖前三个
