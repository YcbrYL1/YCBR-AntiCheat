# Phase 6：误判收敛第三轮（Sprint usingItem 超时复位 / SimulationCheck 实机开启 / Scaffold 行为层灰度）实施计划

> **给 Claude：** 按任务逐项执行本计划；任务 1 遵循 TDD（先写失败测试 → 实现 → 全量回归 → 提交），任务 2/3 为部署与调参步骤。

**目标：** 执行《YCBR-AC_vs_Grim_误判程度对比.md》与《YCBR-AC_vs_Grim_对比分析_v2.md》的剩余建议：P0 开启并验证 SimulationCheck（追平 Grim 移动类差距）、P1 修复 Sprint 的 usingItem 卡死残留（唯一已知 Sprint 误判源）、P2 Scaffold 行为层灰度。

**架构方案：** 唯一代码改动是 `usingItem` 超时复位（纯逻辑 `ItemUseLogic.expired` + 状态计算移入 actor 线程），消除"客户端不发 dig status 5 → usingItem 卡 true → 用物品状态下疾跑误判"。SimulationCheck 开启与 Scaffold 灰度均为配置/部署步骤，按"默认关 → 低负载观察 → 调参 → 开启"哲学执行。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3

**前置依赖：** Phase 5 完成（101/101 测试）；`docs/2026-08-14-simulation-tuning-sop.md` 已存在。

---

### 任务 1（P0）：Sprint usingItem 超时复位

**背景：** `data.usingItem` 置 true 于 `BLOCK_PLACE`（吃/喝/拉弓/牛奶/钓鱼，`AsyncPacketListener.java:448-450`），置 false 仅于 `BLOCK_DIG status 5`（`AsyncPacketListener.java:472`）。若客户端中途退出物品使用且不发 dig status 5，`usingItem` 永久卡 true → `blockedStates` 含 `STATE_USING_ITEM` → 玩家之后疾跑被 Sprint 误判（NoSlow 有 100ms 窗口守卫不受影响，Sprint 无守卫）。两篇对比文档均将此项列为"唯一值得关注的残留 FP"。

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/util/ItemUseLogic.java`（新增 `expired`）
- 修改：`src/test/java/com/ycbr/anticheat/util/ItemUseLogicTest.java`（新增用例）
- 修改：`src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java`（handleClientCommand：状态计算移入 actor + 超时复位）
- 修改：`src/main/resources/config.yml`（`settings.using-item-timeout-ms`）

**步骤 1：写失败测试（追加到 ItemUseLogicTest）**

```java
// usingItem 卡死残留：超时后应判定过期，复位使用状态
@Test
void expired_afterTimeout() {
    long now = 10_000L;
    assertTrue(ItemUseLogic.expired(now, now - 1600L, 1500L), "超时后应过期");
    assertTrue(ItemUseLogic.expired(now, now - 1500L, 1500L), "恰好超时应过期");
}

@Test
void expired_withinTimeout() {
    long now = 10_000L;
    assertFalse(ItemUseLogic.expired(now, now - 100L, 1500L), "窗口内不应过期");
    assertFalse(ItemUseLogic.expired(now, 0L, 1500L), "从未使用不应过期");
}
```

**步骤 2：运行测试确认失败**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q "-Dtest=ItemUseLogicTest"`
预期：**COMPILATION ERROR**（`expired` 不存在）

**步骤 3：实现 `ItemUseLogic.expired`**

```java
/** usingItem 超时复位：客户端中途退出物品使用（不发 dig status 5）时兜底置 false。 */
public static boolean expired(long now, long lastUseTime, long timeoutMs) {
    return lastUseTime > 0L && now - lastUseTime >= timeoutMs;
}
```

**步骤 4：运行测试确认通过**

预期：**PASS**。

**步骤 5：接线 AsyncPacketListener.handleClientCommand**

当前（316 行）：`final int fBlocked = blockedStates(player, data);` 在监听线程计算——可能读到旧 `usingItem`。
改为：捕获 `player` 引用，在 `data.actor.submit` 内先做超时复位再计算状态：

```java
final Player fPlayer = player;
final long usingItemTimeout = manager.config().raw().getLong("settings.using-item-timeout-ms", 1500L);
data.actor.submit(() -> {
    long now = System.currentTimeMillis();
    if (data.usingItem && ItemUseLogic.expired(now, data.lastItemUseTime, usingItemTimeout)) {
        data.usingItem = false;
    }
    int blocked = blockedStates(fPlayer, data);
    if (fAction == 3) {
        data.lastSprintStartTime = now;
        data.movement.sprinting = true;
        ((VelocityCheck) manager.getRegistry().get(CheckType.VELOCITY)).checkSprintReset(data, now);
    } else if (fAction == 4) {
        data.lastSprintStopTime = now;
        data.movement.sprinting = false;
    }
    if (fAction == 5) {
        manager.getRegistry().onRidingJump(data, now);
    } else {
        manager.getRegistry().onSprintAction(data, fAction, blocked);
    }
});
```

config.yml 追加：

```yaml
  using-item-timeout-ms: 1500
```

**步骤 6：全量测试 + 提交**

运行：`mvn test -q`，预期 **PASS**（101 + 2 = 103）。
提交：`git commit -m "fix(sprint): timeout-reset usingItem to kill stuck-state FP"`

---

### 任务 2（P0）：SimulationCheck 实机开启与验证（部署步骤）

**背景：** 两份对比文档一致结论：YCBR 唯一仍明显弱于 Grim 的是移动类精度，根因是生产跑经验回退（Speed/Fly/NoFall @Deprecated），引擎 sim-speed/sim-fly 默认关。Phase 4/5 已消除引擎的已知误判源（疾跑标志、楼梯台阶、粘液块弹跳），具备开启条件。

**步骤 1：部署当前 jar**

```bash
# 1) 打包（已在 Phase 5 验证 252966 字节）
# 2) 复制到插件目录并删除旧 config.yml（强制重建）
Copy-Item target/YCBR.jar "D:\MC\MCSL2-2.3.1.0-Windows-x64\Servers\1\plugins\YCBR.jar" -Force
Remove-Item "D:\MC\MCSL2-2.3.1.0-Windows-x64\Servers\1\plugins\YCBR\config.yml" -ErrorAction SilentlyContinue
```

**步骤 2：低负载观察期开启引擎（保留经验回退兜底）**

config.yml（低负载服或玩家低谷时段）：

```yaml
  simulation:
    enabled: true
    sim-speed:
      enabled: true
      horizontal-tolerance: 0.01
      liquid-tolerance-multiplier: 2.0
      vl-before-flag: 8
    sim-fly:
      enabled: true
      vertical-tolerance: 0.02
      vl-before-flag: 10
```

**步骤 3：观察 1–2 周并记录**

- 按 `docs/2026-08-14-simulation-tuning-sop.md` 采集误判日志（flag 明细含 `h=`/`vDist=`/`ticks=`）。
- 验收标准：**误判日志为零** 后，将 `horizontal-tolerance` 从 0.01 收紧至 0.005、`vertical-tolerance` 从 0.02 收紧至 0.01（对齐 Grim 容差量级），再观察一周。
- 若出现误判：优先检查豁免路径（液体/网/梯子 ×2、台阶/楼梯 `stepVerticalAllowed`、粘液块 `slimeBounceAllowed`、多 tick ×√ticks、方向匹配默认关不参与）；确认为新豁免源时，走 Phase 5 的 KnownExemptions 雏形扩展（如 `WorldProbe` 静态方法 + `blockOn*` 探测字段）。
- **双判协调**：引擎开启后经验回退 Speed/Fly/NoFall 保持运行（互补兜底，其 `vl-before-flag` 高、阈值宽，不会与引擎叠加误杀——两套各自独立 flag 独立 VL）；若实测出现双判叠加误杀，将经验回退三项 `enabled: false` 或上调其 `vl-before-flag`。

**步骤 4：提交观察结论**

观察期结束回填 `docs/plans/2026-08-14-phase06-fp-third-round.md` 实施结果：开启日期、容差档位、误判样本数、豁免修正（如有）。

---

### 任务 3（P2）：Scaffold 行为层灰度（部署步骤）

**背景：** 对比文档建议：`cadence`/`grid45` 先在测试服开启积累数据，确认无误杀再进生产；`colinear`/`duprot` 保持关。Scaffold 当前开启子项均为协议/几何层（invalid-place/fabricated/fast-place/move-place/place-aim/rotation）。

**步骤 1：测试服开启灰度子项**

```yaml
  scaffold:
    cadence:
      enabled: true
      batch-size: 10
      tolerance-ms: 10
      vl-before-flag: 4
    grid45:
      enabled: true
      max-pitch-std: 0.15
      max-yaw-mod: 0.02
      vl-before-flag: 4
```

**步骤 2：观察并记录**

- 采集熟练搭路玩家的误判样本（Flag 明细 + 玩家行为回放）。
- 验收：一周内无误杀 → 生产开启；有误杀 → 上调 `vl-before-flag` 或保持关并记录原因。
- `colinear`/`duprot` 维持 `enabled: false`（与 Phase 4 决策一致）。

**步骤 3：提交观察结论**

回填实施结果章节。

---

### 任务 4（收尾）：基线文档入库 + 实施结果回填

**涉及文件：**
- 新建（复制入库）：`docs/YCBR-AC_vs_Grim_误判程度对比.md`、`docs/YCBR-AC_vs_Grim_对比分析_v2.md`（两篇对比文档当前在仓库外，入库作为基线）
- 修改：`docs/plans/2026-08-14-phase06-fp-third-round.md`（实施结果）

**步骤 1：复制文档入库并提交**

```bash
git add docs/YCBR-AC_vs_Grim_误判程度对比.md docs/YCBR-AC_vs_Grim_对比分析_v2.md docs/plans/2026-08-14-phase06-fp-third-round.md
git commit -m "docs: FP round-3 plan + Grim comparison baselines"
```

**步骤 2：全量回归 + 打包**

运行：`mvn test -q`（预期 **PASS**，103/103）；`mvn -q -DskipTests package`。

**步骤 3：任务 2/3 观察完成后回填实施结果并提交**

---

## 验证方式

- 任务 1：`mvn test` 103/103 全绿；`ItemUseLogicTest` 新增 2 用例。
- 任务 2/3：按部署步骤执行，观察期验收标准（误判日志为零 / 一周无误杀）达成后回填记录。
- 最终 `mvn -q -DskipTests package` 构建成功。

## 风险与注意事项

- **usingItem 超时 1500ms 语义**：1.8 吃食物需要持续按住（最长达 ~1.6s 进食动画后自动结束会发 dig status 5 或 release）；1500ms 超时仅兜底"卡死"状态，不提前释放正常进食（正常进食期间每 ~100ms 有 NoSlow 窗口判定，不依赖此超时）。若实测提前释放影响 NoSlow 判定，可调大至 2000ms。
- **状态计算移入 actor**：`blockedStates` 从监听线程移入 `data.actor`（per-player 串行），读取的状态（usingItem/blockBoxedIn/blockNearLiquid）全部为 actor 线程写入字段，无新增竞态；`player.getFoodLevel()/isSneaking()/hasPotionEffect()` 在 actor（主线程）调用符合 Bukkit 线程模型。
- **引擎开启的双判叠加**：经验回退与引擎各自独立 VL，理论上不叠加；若实机观察到同一移动行为双 flag，按任务 2 步骤 3 处理（关经验回退或上调阈值）。
- **不学 NCP 魔数、不改护城河**：Velocity JumpReset/SprintReset、KillAura 交叉门控、Timer TPS 归一化保持不动。

---

## 实施结果（2026-08-14）

### 任务 1：Sprint usingItem 超时复位 ✅（`2160a00`）

- TDD 全流程：`ItemUseLogicTest` 新增 2 用例（`expired_afterTimeout`/`expired_withinTimeout`）→ 编译失败 → 实现 `ItemUseLogic.expired(now, lastUseTime, timeoutMs)` → PASS。
- `AsyncPacketListener.handleClientCommand`：`blockedStates` 计算移入 `data.actor.submit` 内，先做 usingItem 超时复位（`settings.using-item-timeout-ms`，默认 1500ms）再算状态。
- config.yml 新增 `settings.using-item-timeout-ms: 1500`。
- 全量 **103/103** 通过，提交 `2160a00 fix(sprint): timeout-reset usingItem to kill stuck-state FP`。

### 任务 2：SimulationCheck 实机开启 ⬜（待部署）

观察期未开始，等待服务器部署时机（低负载时段）。验收标准与调参步骤见任务 2 原文。

### 任务 3：Scaffold 行为层灰度 ⬜（待部署）

等待测试服部署（`cadence`/`grid45` 开启参数见任务 3 原文，`colinear`/`duprot` 保持关）。

### 任务 4：基线文档入库 ✅（本次提交）

- `docs/YCBR-AC_vs_Grim_误判程度对比.md`、`docs/YCBR-AC_vs_Grim_对比分析_v2.md` 复制入库（基线）。
- 本计划文档回填实施结果。

### 备注

- 任务 1 完成后继续执行了 Phase 7（P0-2 AABB 碰撞截断 + 活塞/墙介质，`cdd7991`/`08e798d`/`e5ca5b9`/`f781d78`，113/113）——引擎开启前的介质豁免补齐，属任务 2 的前置加固。