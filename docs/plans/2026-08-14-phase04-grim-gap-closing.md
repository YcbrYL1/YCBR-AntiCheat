# Phase 4：Grim 差距收敛与误判控制实施计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 子技能，按任务逐项执行本计划，每个任务完成后验证通过再提交。

**目标：** 依据《YCBR-AC_vs_Grim_对比分析_v2.md》与《YCBR-AC_vs_Grim_误判程度对比.md》两份对比文档的结论，收敛 YCBR-AC 与 Grim 的剩余差距：Sprint 升级为"状态合规"校验（当前唯一误判风险显著高于 Grim 的项）、Reach 增加"实时取消不可能攻击"（缩小执行力差距）、Scaffold 行为子项默认关闭（降低误杀）、补充 simulation 实机调参 SOP 与误判样本回灌流程。Velocity 的 JumpReset/SprintReset 与 Aim 交叉验证作为差异化护城河保持不变。

**架构方案：**
- Sprint：将判定拆为可单测的纯逻辑 `SprintLogic`（6 类禁止疾跑状态位 + 翻转双条件），`SprintCheck` 只负责取状态与接线；`max-flip-gap-ms` 放宽至 40ms。
- Reach：新增只读同步预检 `ReachCheck.shouldCancelAttack(...)`（复用现有多帧射线-AABB 几何），由 `AsyncPacketListener` 在 USE_ENTITY 监听线程直接 `event.setCancelled(true)`（与攻击阻断 `attackBlockedUntil` 联动），检测与 VL 积累仍留在原异步路径。
- Scaffold：纯配置调整（`cadence`/`colinear` 默认关）。
- 调参与回灌：产出 SOP 文档，不动检测代码。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3、ProtocolLib（监听线程 cancel 需在 `onPacketReceiving` 同步段执行）

**前置依赖：** Phase 0/1/2/3 全部完成；审计修复提交 `10f4aa2` 已完成（`settings.debug-packets` 开关可用于误判观察）。

---

### 任务 1（P0）：Sprint 状态合规升级 + 翻转双条件

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/check/protocol/SprintLogic.java`
- 新建：`src/test/java/com/ycbr/anticheat/check/protocol/SprintLogicTest.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/protocol/SprintCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java`（ENTITY_ACTION 分支接线状态位）
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java` 或 `MovementTracker.java`（如缺失 sneaking/usingItem 等字段）
- 修改：`src/main/resources/config.yml`（sprint 段）

**步骤 1：先写失败的测试 `SprintLogicTest`**

```java
package com.ycbr.anticheat.check.protocol;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SprintLogicTest {

    // 6 类禁止疾跑状态位
    @Test
    void hungryBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_HUNGRY));
    }

    @Test
    void sneakingBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_SNEAKING));
    }

    @Test
    void usingItemBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_USING_ITEM));
    }

    @Test
    void blindedBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_BLINDED));
    }

    @Test
    void headBlockedBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_HEAD_BLOCKED));
    }

    @Test
    void inLiquidBlocksSprint() {
        assertFalse(SprintLogic.canSprint(SprintLogic.STATE_IN_LIQUID));
    }

    @Test
    void normalStateAllowsSprint() {
        assertTrue(SprintLogic.canSprint(0));
    }

    // 翻转双条件：仅"翻转 + 该状态下本不可疾跑"计违规
    @Test
    void flipInBlockedStateIsViolation() {
        assertTrue(SprintLogic.isIllegalFlip(SprintLogic.STATE_HUNGRY));
    }

    @Test
    void flipInLegalStateIsNotViolation() {
        assertFalse(SprintLogic.isIllegalFlip(0));
    }
}
```

**步骤 2：运行测试，确认先失败**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test "-Dtest=SprintLogicTest" -q`
预期：**失败（FAIL）**，报 `cannot find symbol`（SprintLogic 不存在）。

**步骤 3：实现 `SprintLogic`**

```java
package com.ycbr.anticheat.check.protocol;

/** Sprint 状态合规判定（对齐 Grim 的 7 类禁止疾跑场景，1.8.8 无鞘翅故为 6 类）。 */
public final class SprintLogic {

    public static final int STATE_HUNGRY = 1;        // 饥饿值 <= 6
    public static final int STATE_SNEAKING = 2;      // 蹲伏
    public static final int STATE_USING_ITEM = 4;    // 进食 / 使用物品
    public static final int STATE_BLINDED = 8;       // 失明
    public static final int STATE_HEAD_BLOCKED = 16; // 撞墙（面前方块阻挡）
    public static final int STATE_IN_LIQUID = 32;    // 水中 / 岩浆

    private SprintLogic() {
    }

    /** 当前状态位集合下是否允许疾跑。 */
    public static boolean canSprint(int blockedStates) {
        return blockedStates == 0;
    }

    /** 翻转检测双条件：该状态下本不可疾跑仍发疾跑 = 违规。 */
    public static boolean isIllegalFlip(int blockedStates) {
        return !canSprint(blockedStates);
    }
}
```

**步骤 4：`SprintCheck` 集成（保留翻转指纹，改为双条件 + 放宽窗口）**

```java
public void checkAction(PlayerData data, int action, int blockedStates) {
    if (!isEnabled() || data.creative || data.flying || data.inVehicle || data.dead) {
        return;
    }
    if (action != ACTION_START_SPRINT && action != ACTION_STOP_SPRINT) {
        return;
    }
    long now = System.currentTimeMillis();
    // 状态合规：该状态下本不可疾跑仍发 START → 直接判违规（Grim 式）
    if (action == ACTION_START_SPRINT && SprintLogic.isIllegalFlip(blockedStates)) {
        if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
            flag(data, "Sprint", "sprint in blocked state");
        }
    }
    // 翻转指纹：仅当翻转期间状态合法时计数（丢包/重排不再误杀合法玩家）
    long gap = now - data.lastSprintActionTime;
    if (data.lastSprintAction != 0 && data.lastSprintAction != action
            && gap < si("max-flip-gap-ms", 40, 30)
            && SprintLogic.isIllegalFlip(blockedStates)) {
        if (++data.sprintFlipCount >= si("flips-to-flag", 3, 2)) {
            data.sprintFlipCount = 0;
            if (bump(data, "sprint", 1D, i("vl-before-flag", 2))) {
                flag(data, "Sprint", "sprint state flips x" + si("flips-to-flag", 3, 2));
            }
        }
    } else {
        data.sprintFlipCount = 0;
        drain(data, "sprint", 0.1D);
    }
    data.lastSprintAction = action;
    data.lastSprintActionTime = now;
}
```

**步骤 5：接线状态位（AsyncPacketListener ENTITY_ACTION 分支，`handleClientCommand` 所在处 L300 附近）**

`blockedStates` 计算（同步段取只读数据，随 `data.actor.submit` 内一并计算或提前算好传入）：

```java
private static int blockedStates(Player player, PlayerData data) {
    int s = 0;
    if (player.getFoodLevel() <= 6) {
        s |= SprintLogic.STATE_HUNGRY;
    }
    if (data.movement.sneaking) {
        s |= SprintLogic.STATE_SNEAKING;
    }
    if (data.usingItem || player.isBlocking()) {
        s |= SprintLogic.STATE_USING_ITEM;
    }
    if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS)) {
        s |= SprintLogic.STATE_BLINDED;
    }
    if (data.movement.headBlocked) {
        s |= SprintLogic.STATE_HEAD_BLOCKED;
    }
    if (data.movement.inLiquid) {
        s |= SprintLogic.STATE_IN_LIQUID;
    }
    return s;
}
```

注意：若 `MovementTracker` 缺 `sneaking`/`headBlocked`/`inLiquid`/`usingItem` 字段，先在对应结构补充并接线（`inLiquid`/`headBlocked` 可在 `WorldProbe` 或移动包处理处维护；`sneaking` 在 movement flags 解析处维护；`usingItem` 在 USE_ITEM 包处理处维护），然后传入 `manager.getRegistry().onSprintAction(data, fAction)` 改为 `onSprintAction(data, fAction, blockedStates(player, data))`，`CheckRegistry.onSprintAction` 签名同步扩展并转发给 `SprintCheck.checkAction(data, action, blockedStates)`。

**步骤 6：config.yml sprint 段更新**

```yaml
  sprint:
    enabled: true
    kick-at-vl: 20
    kick-message: "&cKicked for Sprint"
    max-flip-gap-ms: 40      # 20 → 40（放宽，吸收丢包重排）
    flips-to-flag: 3
```

**步骤 7：运行全量测试**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q`
预期：**通过（PASS）**，测试数 = 54 + 9（新增 SprintLogicTest）。

**步骤 8：提交变更**

```bash
git add src/main/java/com/ycbr/anticheat/check/protocol/SprintLogic.java src/test/java/com/ycbr/anticheat/check/protocol/SprintLogicTest.java src/main/java/com/ycbr/anticheat/check/protocol/SprintCheck.java src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java src/main/java/com/ycbr/anticheat/data/ src/main/resources/config.yml
git commit -m "feat: sprint state-compliance + dual-condition flip (P0)
```

---

### 任务 2（P1）：Reach 实时取消不可能攻击

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/CheckRegistry.java`
- 修改：`src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java`
- 新建：`src/test/java/com/ycbr/anticheat/check/combat/ReachCancelTest.java`（几何判定单测）
- 修改：`src/main/resources/config.yml`（reach 段加 `cancel-impossible`）

**步骤 1：先写失败的测试 `ReachCancelTest`**

```java
package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.ycbr.anticheat.snapshot.EntitySnapshot;
import com.ycbr.anticheat.data.PlayerData;

class ReachCancelTest {

    private static EntitySnapshot snap(double x, double y, double z, double vx, double vy, double vz,
                                       double w, double h) {
        EntitySnapshot s = new EntitySnapshot();
        s.x = x; s.y = y; s.z = z;
        s.vx = vx; s.vy = vy; s.vz = vz;
        s.width = w; s.height = h;
        s.createdMillis = System.currentTimeMillis();
        return s;
    }

    @Test
    void farTargetCancels() {
        PlayerData data = new PlayerData();
        data.movement.lastX = 0; data.movement.lastY = 0; data.movement.lastZ = 0;
        EntitySnapshot t = snap(20, 0, 20, 0, 0, 0, 0.6, 1.8);
        assertTrue(ReachCheck.shouldCancelAttack(data, t, 3.1, 0.03, 2));
    }

    @Test
    void closeTargetNotCancelled() {
        PlayerData data = new PlayerData();
        data.movement.lastX = 0; data.movement.lastY = 0; data.movement.lastZ = 0;
        EntitySnapshot t = snap(2, 0, 0, 0, 0, 0, 0.6, 1.8);
        assertFalse(ReachCheck.shouldCancelAttack(data, t, 3.1, 0.03, 2));
    }
}
```

**步骤 2：运行测试，确认先失败**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test "-Dtest=ReachCancelTest" -q`
预期：**失败（FAIL）**，`cannot find symbol: method shouldCancelAttack`。

**步骤 3：实现只读同步预检**

在 `ReachCheck` 增加（**纯只读**，可在监听线程安全调用；复用 `onAttack` 的距离判定与 `hitsFromAnyFrame`，其中 `hitsFromAnyFrame` 需从 `protected` 改为 `static` 或包级可见，供静态方法调用）：

```java
/** 监听线程同步预检：距离超限且所有帧射线均未命中 → 建议取消本次攻击。 */
public static boolean shouldCancelAttack(PlayerData data, EntitySnapshot target,
                                         double maxReach, double leniency, int windowTicks) {
    if (target == null) {
        return false;
    }
    double dx = target.x - data.movement.lastX;
    double dz = target.z - data.movement.lastZ;
    double halfWidth = Math.max(0.1D, target.width / 2.0D);
    double hDist = Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - halfWidth);
    double eyeY = data.movement.lastY + 1.62D;
    double top = target.y + target.height;
    double vDist = eyeY > top ? eyeY - top : (eyeY < target.y ? target.y - eyeY : 0.0D);
    double distance = Math.sqrt(hDist * hDist + vDist * vDist);
    // 最保守情况：不做移动/快照年龄补偿，直接判是否超 max-reach + 容差
    if (distance <= maxReach + leniency) {
        return false;
    }
    // 多帧射线兜底：任一帧命中插值碰撞盒 → 不取消（防误杀擦边）
    return !hitsFromAnyFrame(data, target, maxReach + leniency, windowTicks);
}
```

`hitsFromAnyFrame` 改造：签名加 `int windowTicks` 参数（去掉对 `i("multi-frame.window-ticks", 2)` 的依赖），并改为 `static`；`onAttack` 内调用处传 `i("multi-frame.window-ticks", 2)`。

**步骤 4：`CheckRegistry` 增加同步预检入口**

```java
public boolean cancelImpossibleAttack(PlayerData data, int targetId) {
    if (data.op || data.creative) {
        return false;
    }
    EntitySnapshot target = manager.getEntitySnapshots().get(targetId);
    if (target == null || !checkReachEnabled()) {
        return false;
    }
    double maxReach = cfg.sd("checks.reach.max-reach", 3.1D);
    double leniency = cfg.sd("checks.reach.leniency", 0.03D);
    int window = cfg.i("checks.reach.multi-frame.window-ticks", 2);
    return ReachCheck.shouldCancelAttack(data, target, maxReach, leniency, window);
}
```

（`checkReachEnabled()` 读 `checks.reach.enabled` 与 `checks.reach.cancel-impossible`，两条都为 true 才取消；`cfg.sd/cfg.i` 按 `YCBRConfig` 现有取值 API 对齐，若签名不同则用 `manager.config().raw().getDouble(...)`。）

**步骤 5：AsyncPacketListener USE_ENTITY 监听线程取消（`onPacketReceiving` 内、`handleUseEntity` 之前）**

```java
} else if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
    // 实时取消不可能攻击（Phase 4）：同步预检，监听线程直接 cancel
    if (event.getPacket().getEntityUseActions().read(0) == EnumWrappers.EntityUseAction.ATTACK
            && manager.getRegistry().cancelImpossibleAttack(
                    manager.getDataManager().get(event.getPlayer().getUniqueId()),
                    event.getPacket().getIntegers().read(0))) {
        event.setCancelled(true);
        PlayerData d = manager.getDataManager().get(event.getPlayer().getUniqueId());
        d.attackBlockedUntil = System.currentTimeMillis() + 500L; // 与软惩罚联动
        return;
    }
    handleUseEntity(...); // 原路径
}
```

注意：`getEntityUseActions().read(0)` 与 `isAttack(packet)` 重复读取，可在分支内先取 action 再复用；`event.setCancelled(true)` 只对 ProtocolLib 监听有效，需确认 `onPacketReceiving` 位于 `ListenerPriority.HIGH`（读优先级，若非则调整），且 PacketListener 以 `Async` 方式注册不影响取消。

**步骤 6：config.yml reach 段**

```yaml
  reach:
    cancel-impossible: true    # 新增：实时取消不可能攻击（宁可漏不可杀）
```

**步骤 7：运行全量测试**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q`
预期：**通过（PASS）**，测试数 = 63 + 2（新增 ReachCancelTest）。

**步骤 8：提交变更**

```bash
git add src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java src/main/java/com/ycbr/anticheat/check/CheckRegistry.java src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java src/test/java/com/ycbr/anticheat/check/combat/ReachCancelTest.java src/main/resources/config.yml
git commit -m "feat: cancel impossible reach attacks in real-time (P1)
```

---

### 任务 3（P1）：Scaffold 行为子项默认关闭

**涉及文件：**
- 修改：`src/main/resources/config.yml`（scaffold 段）

**步骤 1：修改默认值（纯配置，无代码改动）**

```yaml
    cadence:
      enabled: false          # true → false（行为节奏指纹误杀熟练搭路）
    colinear:
      enabled: false          # true → false
```

（`grid45`、`duprot` 已为 false，保持不变；协议/旋转类子项 `invalid-place/fabricated/fast-place/move-place/place-aim/rotation` 保持开启。）

**步骤 2：验证**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q`
预期：**通过（PASS）**，测试数不变（63）。检查 `ScaffoldCheck` 读取 `cadence.enabled`/`colinear.enabled` 的路径与 config 一致（`checks.scaffold.cadence.enabled` 等，若有子路径差异以代码实际读取路径为准）。

**步骤 3：提交变更**

```bash
git add src/main/resources/config.yml
git commit -m "chore: scaffold behavior sub-checks off by default (P1)
```

---

### 任务 4（P0）：simulation 实机调参 SOP 文档

**涉及文件：**
- 新建：`docs/2026-08-14-simulation-tuning-sop.md`

**步骤 1：撰写 SOP（中文，面向服务器管理员）**

内容要点（全部来自两份对比文档的结论）：
1. **开启前**：确保 `settings.debug-packets: true`（审计修复已提供），先在低负载服务器观察 1-2 天误判日志。
2. **开启顺序**：
   - 第一周：`simulation.sim-speed.enabled: true`，`horizontal-tolerance: 0.02`（从 0.02 起步，**不是** 0.01），`liquid-tolerance-multiplier: 2.0` 保持不变；
   - 第二周：误判日志无异常后收紧 `horizontal-tolerance: 0.01`；
   - 第三周：`sim-fly.enabled: true`，`vertical-tolerance: 0.02` → 0.01 同理步进；
   - 最后：`simulation.enabled: true` 总开关（子开关依赖总开关）。
3. **回退条件**：任一检测单日误判 flag > 5 次 → 回退上一档容差；`simulation` 各子项可独立关。
4. **与 aimstat 的关系**：`aimstat`/`ml` 保持默认关，待 simulation 稳定后再评估开启。
5. **观察命令**：`/ycbr alerts`、`/ycbr record`（采集误判样本回灌训练集，见任务 5）。

**步骤 2：提交变更**

```bash
git add docs/2026-08-14-simulation-tuning-sop.md
git commit -m "docs: simulation live tuning SOP (P0)
```

---

### 任务 5（持续）：误判样本回灌 DatasetManager 工作流文档

**涉及文件：**
- 修改：`docs/ml/README.md`（追加"误判样本回灌"章节）

**步骤 1：在 `docs/ml/README.md` 追加章节**

内容要点：
1. 误判样本 = 真人被 flag 的窗口数据；用 `/ycbr record <player>` 在实机采集（命令已存在，Phase 3 完成）。
2. 采集后的 CSV 位于 `plugins/YCBR/dataset/`，文件名含玩家名与 label。
3. 回灌流程：将误判样本复制到训练集目录 → 重跑训练脚本（见 README 已有训练指南）→ 用 `/ycbr reload` 热更新（SimpleMLP 从 CSV 加载权重，`ml-enabled: true` 时生效）。
4. 数据卫生：样本需人工确认确为误判；每个玩家每窗口只保留 1 条，避免样本偏置。

**步骤 2：提交变更**

```bash
git add docs/ml/README.md
git commit -m "docs: false-positive sample feedback workflow (P1)
```

---

### 任务 6：回归验证与整体提交收尾

**步骤 1：全量测试**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q`
预期：**通过（PASS）**，测试数 = 65（54 + 9 SprintLogic + 2 ReachCancel）。

**步骤 2：打包**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" package -q -DskipTests`
预期：`target/YCBR.jar` 生成成功。

**步骤 3：确认 git 状态干净**

运行：`git status --short`
预期：无未提交改动（任务 1-5 已各自提交）。

---

## 验证方式

- 单测：SprintLogicTest（9 用例）+ ReachCancelTest（2 用例）+ 既有 54 用例 = 65 全绿。
- 编译：`mvn test -q` 无输出、退出码 0。
- 配置：`config.yml` 增量 diff 仅含计划内键（sprint.max-flip-gap-ms 40、reach.cancel-impossible、scaffold cadence/colinear false）。
- 实机（用户侧）：按 SOP 分三周步进开启 simulation，观察 `debug-packets` 日志误判情况。

## 风险与注意事项

- **Sprint 状态位接线**：`sneaking/inLiquid/headBlocked/usingItem` 字段若不存在于 `MovementTracker`，需先补字段并接线——这是任务 1 中改动面最大的部分，接线错误可能影响其他检测（如 PredictionEngine 输入），必须在补字段后跑全量 65 测试确认。
- **Reach 取消的线程安全**：`shouldCancelAttack` 必须保持纯只读（不写 PlayerData/不调 flag），否则监听线程与主线程并发写会出问题；`attackBlockedUntil` 是 volatile 已有字段，可安全写入。
- **ProtocolLib cancel**：`event.setCancelled(true)` 要求监听器优先级足够高（HIGH）且为同步段调用；若实测无效，退化为"仅设 attackBlockedUntil + 记录日志"方案（该方案已有 Phase 0.4 软惩罚基础）。
- **误判优先级**：Sprint（P0）误判风险最高，优先做；Scaffold 行为子项关闭（P1）不改变代码路径，风险最低。
- **护城河不回归**：Velocity JumpReset/SprintReset、Aim 交叉验证、FastClickLogic 峰度/熵——本计划不改动这些文件，回归测试可验证无意外影响。

## 保持项（本计划明确不动）

- Velocity 的 `JumpReset`/`SprintReset` 行为指纹（相对 Grim 的差异化优势）。
- KillAura 启发式 × 统计交叉验证门控（`signal-fresh-ms: 10000`）。
- Timer/Blink 事务化逻辑、Reach 多帧射线-AABB 主判定。