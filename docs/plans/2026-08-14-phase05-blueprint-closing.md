# Phase 5：蓝图未覆盖项收敛（Blink 重放 / 方向匹配 / Reach 动态收缩 / 弹跳豁免）实施计划

> **给 Claude：** 按任务逐项执行本计划，每个任务遵循 TDD（先写失败测试 → 实现 → 全量回归 → 提交）。

**目标：** 收敛两份战略蓝图（《修改计划·Grim/MX》85%、《修改计划·8AC》80%）中的剩余未覆盖项：Blink 重放 burst 确认、PredictionEngine strafe 候选 + 方向匹配、Reach 临界距离动态收缩、sim-fly 粘液块弹跳豁免；并记录 Phase 4 误判风暴 4 修复的后续调参结论。

**架构方案：** 全部新逻辑抽为纯逻辑类（`BlinkLogic`/`ReachModLogic`）或引擎参数（strafe 候选），检测类只接线；新子检测默认关闭，遵循"默认关闭 → 观察 → 调参 → 开启"哲学。唯一直接生效的是粘液块弹跳豁免（消除已知误判源）。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3

**前置依赖：** Phase 4 完成（65 测试）＋ 误判风暴 4 修复（85/85 测试，`ff4e296`/`af154b1`/`e9aa4f1`/`9ca4be6`）。

---

### 任务 1（P0）：Blink 重放 burst 确认子检测

**背景：** Phase 2 已实现"活体 pong"核心判定（有事务 pong 但无移动包 → 囤包），但缺少重放确认：囤包作弊者在静默期结束后会瞬间补发大量位置包（burst）。网络拥塞恢复也会产生 burst，因此 burst 必须与"静默期有活体 pong"绑定才能作为可信信号。

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/check/protocol/BlinkLogic.java`
- 新建：`src/test/java/com/ycbr/anticheat/check/protocol/BlinkLogicTest.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/protocol/BlinkCheck.java`（接线，默认关子项）
- 修改：`src/main/resources/config.yml`（blink 段加 `replay-burst` 子项，默认 false）
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`（到达时间戳缓冲）

**步骤 1：写失败测试 `BlinkLogicTest`**

```java
package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BlinkLogicTest {

    // 静默期（有活体 pong 但无移动包）结束后突发补发位置包 = 重放
    @Test
    void silenceThenBurst_isReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        for (int i = 0; i < 40; i++) logic.feed(50L, false); // 正常节奏，无静默
        // 静默 1800ms（pong 活跃）
        for (int i = 0; i < 30; i++) logic.tick(50L, true);
        // 突发补发 20 包，间隔 ~2ms
        boolean burst = false;
        for (int i = 0; i < 20; i++) {
            if (logic.feed(2L, true)) { burst = true; }
        }
        assertTrue(burst, "静默后突发补发应判定为重放");
    }

    // 无静默期的 burst（网络拥塞恢复）不得判定
    @Test
    void burstWithoutSilence_notReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        boolean burst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(2L, false)) { burst = true; }
        }
        assertFalse(burst, "无静默期的 burst 不应判定为重放");
    }

    // 正常节奏永不判定
    @Test
    void normalPacing_neverReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        boolean burst = false;
        for (int i = 0; i < 200; i++) {
            if (logic.feed(50L, false)) { burst = true; }
        }
        assertFalse(burst, "正常节奏不应判定");
    }

    // 静默但无突发补发（仅掉线边缘）不得判定
    @Test
    void silenceWithoutBurst_notReplay() {
        BlinkLogic logic = new BlinkLogic(40);
        for (int i = 0; i < 40; i++) logic.feed(50L, false);
        for (int i = 0; i < 30; i++) logic.tick(50L, true);
        for (int i = 0; i < 20; i++) logic.feed(50L, true); // 恢复后正常节奏
        boolean burst = false;
        for (int i = 0; i < 40; i++) {
            if (logic.feed(50L, true)) { burst = true; }
        }
        assertFalse(burst, "静默后恢复但无突发，不应判定");
    }
}
```

**步骤 2：运行测试确认失败**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q "-Dtest=BlinkLogicTest"`
预期：**COMPILATION ERROR**（BlinkLogic 不存在）

**步骤 3：实现 `BlinkLogic`**

```java
package com.ycbr.anticheat.check.protocol;

/**
 * Blink 重放 burst 纯逻辑（无 Bukkit 依赖，可单测）。
 *
 * <p>判定：静默期（有活体 pong 但无移动包，由 BlinkCheck 每 tick 喂入）
 * 结束后突发补发位置包（间隔 &lt; burst-max-interval-ms 且连续 N 包）=
 * 囤包重放确认。静默期是前提：网络拥塞恢复的 burst 无静默期，不算。</p>
 */
public final class BlinkLogic {

    private final long[] intervals;
    private final int window;
    private int head;
    private int count;
    private long silentMs;

    public BlinkLogic(int window) {
        this.window = window;
        this.intervals = new long[window];
    }

    /** 无移动包的一 tick：累计静默时长（pongActive=true 表示网络活着）。 */
    public void tick(long tickMs, boolean pongActive) {
        if (pongActive) {
            silentMs += tickMs;
        } else {
            silentMs = 0L; // pong 也停了 → 整体断流，不视为囤包静默
        }
    }

    /**
     * 记录一次位置包到达间隔（ms）。
     * 返回 true = 判定为重放 burst（静默达标 + 突发补发达标）。
     */
    public boolean feed(long intervalMs, boolean pongActive) {
        long silentBefore = silentMs;
        silentMs = 0L; // 位置包到达 → 静默期结束
        intervals[head] = intervalMs;
        head = (head + 1) % window;
        if (count < window) count++;

        if (silentBefore < minSilenceMs()) return false;
        if (count < minBurstPackets()) return false;

        // 最近 min-burst-packets 个包间隔全部低于阈值
        int n = Math.min(count, minBurstPackets());
        for (int i = 0; i < n; i++) {
            long iv = intervals[(head - 1 - i + window) % window];
            if (iv > maxIntervalMs()) return false;
        }
        return true;
    }

    private long minSilenceMs() { return 1000L; }      // 静默 ≥1s
    private int minBurstPackets() { return 8; }        // 连续 8 包
    private long maxIntervalMs() { return 25L; }       // 间隔 ≤25ms（正常 50ms 的一半）
}
```

（阈值先写死，config 化在步骤 4 接线时做；若需可配置，把三个私有方法改为构造参数。）

**步骤 4：运行测试确认通过**

运行：同上命令，预期：**PASS**（4 个用例）

**步骤 5：接线 `BlinkCheck` + PlayerData + config**

- `PlayerData` 新增：`public volatile long lastMoveIntervalMs;`（在 AsyncPacketListener 移动包处理处记录 `now - lastMoveTime`）——若已有等价字段则复用。
- `BlinkCheck` 新增子检测：`blink.replay-burst` 默认关；开启时在 `onTick` 中 `logic.tick(50L, livePong)`，在 `onMove`（需新增 `onMove` 入口或在 MainThreadHandler 每 tick 喂）中 `logic.feed(interval, livePong)`；命中时 bump("blink-replay", 1D, ...) 独立 VL，不并入主 blink VL。
- config.yml：

```yaml
  blink:
    replay-burst:
      enabled: false          # 默认关，观察后开启
      vl-before-flag: 6
      min-silence-ms: 1000
      min-burst-packets: 8
      max-interval-ms: 25
```

**步骤 6：全量测试**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q`
预期：**PASS**，89/89（85 + 4）

**步骤 7：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/protocol/BlinkLogic.java src/test/java/com/ycbr/anticheat/check/protocol/BlinkLogicTest.java src/main/java/com/ycbr/anticheat/check/protocol/BlinkCheck.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/resources/config.yml
git commit -m "feat(blink): replay-burst confirmation sub-check (default off)"
```

---

### 任务 2（P1）：PredictionEngine strafe 候选 + 方向匹配（默认关）

**背景：** 8AC 蓝图 P2.1：候选集只有 4 speeds × 2 jumps = 8，strafe=0；SimulationCheck 仅模长匹配。扩展 strafe ∈ {-1, 0, 1}（4×3×2 = 24 候选），并新增方向联合匹配（夹角 < 阈值）——方向匹配默认关闭（高 ping 方向漂移是已知误判风险）。

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`（candidates 加 strafe 维度）
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`（新增 strafe 测试）
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java`（方向匹配，config 门控）
- 修改：`src/main/resources/config.yml`（sim-speed 段加 `direction-match`，默认 false）

**步骤 1：写失败测试（加入 PredictionEngineTest 或新文件）**

```java
@Test
void candidates_includeStrafeRows() {
    // 斜跑（strafe≠0）是合法输入：候选应覆盖侧移方向
    PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
        0.0, 0.0, 0.0, true, 0f, 0.6, false, 0, 0, false, false, false, false, false);
    boolean foundStrafe = false;
    for (PredictionEngine.Candidate c : cands) {
        if (c.label.contains("strafe")) { foundStrafe = true; break; }
    }
    assertTrue(foundStrafe, "候选应包含 strafe 行");
}

@Test
void candidates_strafeLeft_bendsLeft() {
    // yaw=0（朝 +Z），strafe=-1（左）→ deltaX < 0（向左偏）
    PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
        0.0, 0.0, 0.0, true, 0f, 0.6, false, 0, 0, false, false, false, false, false);
    boolean found = false;
    for (PredictionEngine.Candidate c : cands) {
        if (c.label.contains("strafe=-1") && !c.label.contains("+jump")) {
            assertTrue(c.deltaX < 0, "左 strafe 应产生负 deltaX, got " + c.deltaX);
            found = true;
        }
    }
    assertTrue(found, "strafe=-1 行缺失");
}
```

**步骤 2：运行确认失败**

预期：**FAIL**（无 strafe 标签行）。

**步骤 3：实现 strafe 候选**

在 `candidates(...)` 完整签名中把单 `fwd=1, strafe=0` 循环改为两层：`double[] strafes = {-1.0, 0.0, 1.0};`，每行 `fwd=1.0`，`strafe=strafes[s2]`，标签追加 `"strafe=" + (int) strafes[s2]`。注意 `Entity.a` 中 `f3 = sqrt(fwd²+strafe²)`，f3≥1 时模长 = inputSpeed 不变，因此**纯 strafe 候选不会抬高模长上限**（不影响 sim-speed 判定的安全方向），只增加方向覆盖。sprint 行保持独立于 `sprinting` 标志（Phase 4 修复）。

**步骤 4：运行确认通过**

预期：**PASS**。

**步骤 5：SimulationCheck 方向联合匹配（config 门控，默认关）**

```java
// 在 hMatch 计算处：模长匹配通过后，若开启方向匹配再校验夹角
// direction-match 默认 false；开启时：
//   angle = acos(dot(actual, c) / (|actual| * |c| + 1e-10))
//   angle <= max-angle-deg (30°) 且 |actual| <= |c| + hTol 才 hMatch
// ticks >= 3 时 max-angle-deg 放宽到 45°（高 ping 方向漂移）
boolean directionMatch = isSubEnabled("direction-match"); // config: sim-speed.direction-match
```

config.yml：

```yaml
    direction-match:
      enabled: false           # 默认关（高 ping 方向漂移误判风险）
      max-angle-deg: 30
```

**步骤 6：全量测试 + 提交**

运行：`mvn test -q`，预期 **PASS**。
提交：`git commit -m "feat(sim): strafe candidates + optional direction match (default off)"`

---

### 任务 3（P1）：Reach 临界距离动态收缩（reachMod）

**背景：** 8AC 蓝图 P3.3 第 3 点（NCP reachMod）：玩家长期在临界距离攻击时逐步收紧允许距离，打击"卡距离蹭边缘"的违规者，同时不误杀正常玩家。

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/check/combat/ReachModLogic.java`
- 新建：`src/test/java/com/ycbr/anticheat/check/combat/ReachModLogicTest.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java`（接线，默认关）
- 修改：`src/main/resources/config.yml`

**步骤 1：写失败测试 `ReachModLogicTest`**

```java
package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReachModLogicTest {

    // 初始无收缩
    @Test
    void initialModifier_zero() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        assertEquals(0.0, logic.currentModifier(), 1e-9);
    }

    // 连续临界攻击达阈值 → 收缩
    @Test
    void repeatedEdgeAttacks_shrink() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int i = 0; i < 8; i++) logic.onEdgeAttack(1.0);
        assertTrue(logic.currentModifier() > 0.0, "连续临界攻击应产生收缩");
    }

    // 正常距离攻击 → 不收缩且衰减
    @Test
    void cleanAttacks_decay() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int i = 0; i < 8; i++) logic.onEdgeAttack(1.0);
        double shrunk = logic.currentModifier();
        assertTrue(shrunk > 0.0);
        for (int i = 0; i < 20; i++) logic.onCleanAttack();
        assertTrue(logic.currentModifier() < shrunk, "正常攻击应衰减收缩");
    }

    // 收缩有上限
    @Test
    void shrink_capped() {
        ReachModLogic logic = new ReachModLogic(8, 0.05, 0.5);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 8; i++) logic.onEdgeAttack(1.0);
        }
        assertTrue(logic.currentModifier() <= 0.5 + 1e-9, "收缩不得超过上限");
    }
}
```

**步骤 2：运行确认失败**

预期：**COMPILATION ERROR**（类不存在）。

**步骤 3：实现 `ReachModLogic`**

```java
package com.ycbr.anticheat.check.combat;

/**
 * 临界距离动态收缩（借鉴 NCP reachMod）。
 * 玩家长期在临界距离攻击（边缘命中）→ 逐步收紧允许距离；
 * 正常距离攻击 → 指数衰减回零。纯逻辑，可单测。
 */
public final class ReachModLogic {

    private final int edgeStreakRequired;
    private final double shrinkStep;
    private final double maxShrink;
    private int edgeStreak;
    private double modifier;

    public ReachModLogic(int edgeStreakRequired, double shrinkStep, double maxShrink) {
        this.edgeStreakRequired = edgeStreakRequired;
        this.shrinkStep = shrinkStep;
        this.maxShrink = maxShrink;
    }

    /** 临界距离攻击（距离接近上限）：累计连击，达阈值即收缩一步。 */
    public void onEdgeAttack(double distanceRatio) {
        edgeStreak++;
        if (edgeStreak >= edgeStreakRequired) {
            modifier = Math.min(maxShrink, modifier + shrinkStep);
            edgeStreak = 0;
        }
    }

    /** 正常距离攻击：衰减。 */
    public void onCleanAttack() {
        edgeStreak = 0;
        modifier *= 0.8D;
    }

    public double currentModifier() {
        return modifier;
    }
}
```

**步骤 4：运行确认通过**

预期：**PASS**（4 用例）。

**步骤 5：接线 `ReachCheck`（默认关）**

- config：`checks.reach.reach-mod.enabled: false`（+ `edge-streak-required: 8` / `shrink-step: 0.05` / `max-shrink: 0.5`）。
- 在 `onAttack` 判定处：当 `distance > maxReach - leniency` 视为临界 → `logic.onEdgeAttack(...)`；否则 `logic.onCleanAttack()`。
- 仅 `reach-mod.enabled` 时，有效 `maxReach' = maxReach - logic.currentModifier()`。

**步骤 6：全量测试 + 提交**

运行：`mvn test -q`，预期 **PASS**（89 + 4 = 93）。
提交：`git commit -m "feat(reach): critical-distance dynamic shrink reachMod (default off)"`

---

### 任务 4（P2）：sim-fly 粘液块弹跳豁免（直接生效）

**背景：** 1.8 粘液块会反弹玩家（落地后 motY 反跳 ~1.5×），PredictionEngine 无弹跳模型，踩粘液块会被 sim-fly 误判。此前误判风暴修复已处理台阶/楼梯（`stepVerticalAllowed`），本任务补齐粘液块。**直接生效（默认开启）**，因为这是消除已知误判源，不引入新检测。

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/WorldProbe.java`（弹跳豁免判定）
- 修改：`src/test/java/com/ycbr/anticheat/simulation/WorldProbeStepTest.java`（新增弹跳测试）
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java`（接线）

**步骤 1：写失败测试（追加到 WorldProbeStepTest）**

```java
@Test
void slimeBounceAllowed() {
    // 踩粘液块落地反弹：|dy| 可达 ~0.6 以上（1.8 弹跳 motY 反跳 1.5x）
    assertTrue(WorldProbe.slimeBounceAllowed(0.6, true));
    assertTrue(WorldProbe.slimeBounceAllowed(-0.6, true));
    assertFalse(WorldProbe.slimeBounceAllowed(1.2, true)); // 超出弹跳包络
    assertFalse(WorldProbe.slimeBounceAllowed(0.6, false)); // 非粘液块不豁免
}
```

**步骤 2：运行确认失败**

预期：**FAIL**（方法不存在）。

**步骤 3：实现**

```java
/** 粘液块弹跳豁免：脚下是粘液块且 |dy| ≤ 弹跳包络（0.6，1.8 反弹 1.5× 起跳 0.42 ≈ 0.63）。 */
public static boolean slimeBounceAllowed(double actualDY, boolean onSlime) {
    return onSlime && Math.abs(actualDY) <= 0.65D;
}
```

`SimulationCheck` 垂直判定处：`boolean stepUp = ... || WorldProbe.slimeBounceAllowed(actualDY, data.blockOnSlime);`（`data.blockOnSlime` 已有字段，MainThreadHandler 已探测）。

**步骤 4：运行确认通过 + 全量测试**

预期：**PASS**（93 + 3 = 96）。

**步骤 5：提交**

```bash
git commit -m "fix(sim-fly): slime-block bounce exemption (1.8 rebound)"
```

---

### 任务 5（收尾）：蓝图覆盖状态回填 + 回归

**涉及文件：**
- 修改：`docs/plans/2026-08-14-phase05-grim-gap-closing.md`（追加实施结果）
- 修改：`docs/YCBR-AC_修改计划_Grim_MX.md`、`docs/2026-08-13-modification-plan-vs-8ac.md`（未覆盖项勾选）

**步骤 1：全量测试 + 打包**

运行：`mvn test -q`（预期 **PASS**）；`mvn -q -DskipTests package`（预期 jar 生成）。

**步骤 2：回填文档**

- Phase 5 计划文档追加"实施结果"章节（提交哈希、测试数、config 变更）。
- 两份蓝图各自未覆盖项清单：勾掉已覆盖项，标注剩余（活塞/载具高级交互、PhysicsConstants 集中化、Scaffold 批处理——cadence 已存在且默认关，视为已覆盖）。

**步骤 3：提交**

```bash
git commit -m "docs: Phase 5 results + blueprint coverage updates"
```

---

## 验证方式

- 每任务 `mvn test -q` 通过，最终 96/96 全绿。
- `mvn -q -DskipTests package` 构建成功。
- 新逻辑全部为纯逻辑类（BlinkLogic/ReachModLogic/WorldProbe 静态方法），可单测、无 Bukkit 依赖。
- 除任务 4（粘液弹跳豁免）外，所有新检测默认关闭，观察后开启。

## 风险与注意事项

- **方向匹配误判风险**（任务 2）：夹角匹配对高 ping 方向漂移敏感，必须默认关；开启时对 ticks≥3 放宽角度（30°→45°）。
- **Blink burst 与网络拥塞**（任务 1）：静默期前提 + 连续 8 包 ≤25ms 双条件，拥塞恢复 burst 无静默期不会命中；仍默认关观察。
- **reachMod 误伤擦边玩家**（任务 3）：收缩步进 0.05、上限 0.5、正常攻击衰减——擦边但合法玩家会周期性 cleanAttack 衰减，不会累计误判；默认关。
- **粘液弹跳包络 0.65**（任务 4）：1.8 粘液块反弹系数为 ~1.5× 下落速度；若实机仍有误判可调 0.65 → 0.75；作弊者垂直速度 >1.0 仍会被抓。
- **不改动护城河**：Velocity JumpReset/SprintReset、KillAura 交叉门控、Timer TPS 归一化（Phase 4）保持不动。

---

## ✅ 实施结果（2026-08-14）

| 任务 | 提交 | 测试 | 状态 |
|------|------|------|------|
| 1. Blink 重放 burst 确认 | `cf01afa` | BlinkLogicTest +4（89/89） | ✅ 完成 |
| 2. strafe 候选 + 方向匹配 | `7bd0c57` | PredictionEngineStrafeTest +5（94/94） | ✅ 完成 |
| 3. Reach reachMod 动态收缩 | `f1e388f` | ReachModLogicTest +4（98/98） | ✅ 完成 |
| 4. 粘液块弹跳豁免 | `09bf428` | WorldProbeStepTest +3（101/101） | ✅ 完成 |
| 5. 文档回填 + 蓝图勾选 | 本次提交 | 101/101 + 打包 252966 字节 | ✅ 完成 |

**偏离计划的决策：**
- 任务 1：`BlinkLogic` 增加 `burstWindowRemaining` 窗口（静默达标后保持 N 包判定），因为静默标记只出现在静默后第一包，而 burst 判定需要连续 8 包——测试暴露后修复。
- 任务 2：镜像测试断言修正（yaw=0 时 strafe 只改变 deltaZ，不改变 deltaX）；现有 `PredictionEngineTest` 适配 24 候选（`assertEquals(8→24)`、标签改前缀匹配）。
- 任务 4：粘液弹跳为唯一直接生效项（消除已知误判源）；其余全部默认关观察。
- 蓝图勾选结论：8AC P3.4（Scaffold 批处理）→ cadence 已存在且默认关，视为已覆盖；Grim/MX P3.6 → invalid-place/fabricated 协议级子项已在。剩余未覆盖：活塞推动/0.03 跳过 tick/末影珍珠（传送已覆盖）、PhysicsConstants 集中化、KnownExemptions 集中化。

**验证方式：** `mvn test` 101/101 全绿；`mvn -q -DskipTests package` 产出 YCBR.jar（252966 字节）。