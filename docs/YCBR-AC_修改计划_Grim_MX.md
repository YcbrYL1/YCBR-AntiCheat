# YCBR-AC 修改计划：吸收 Grim（移动/协议）+ MX（战斗）优势

> 目标：在不推倒重来的前提下，把 YCBR-AC 的移动类补到 Grim 级、战斗类补到 MX 级。
> 依据：三方源码实测（YCBR-AC 当前结构 + Grim 2.0 + MX 的 `kireiko.dev.anticheat`）。

---

## 〇、现状基线（实测关键发现）

读 YCBR-AC 源码后，发现它**已经比“纯经验公式”先进**，但存在结构性短板：

| 模块 | 现状 | 问题 |
|------|------|------|
| 移动引擎 | 已有 `simulation/PredictionEngine.java`、`ShadowPlayer.java`、`SimulationCheck.java`（sim-speed / sim-fly 雏形） | 是**初级版 Grim**：只会算速度/摩擦/跳跃，**完全没有世界交互层** |
| 移动检测 | `SpeedCheck`/`FlyCheck`/`NoFallCheck`/`NoSlowCheck` **仍在用经验公式**（如 `ground.limit 0.29`、`air.momentum 0.985^t` 魔法数）与 `SimulationCheck` **双轨并存** | 维护魔法数、漏新介质、与引擎逻辑重复 |
| 世界交互 | `SimulationCheck.getFrictionFactor()` 只分三档（冰 0.98 / 史莱姆 0.8 / 普通 0.6）；遇液体/网/梯子直接 `return` 跳过 | **留下巨大盲区**（水里、蜘蛛网、梯子不下检测） |
| 垂直模拟 | `PredictionEngine.candidates()` 单 tick 的 `motionY` 从 0 起算，不继承上 tick；遇液体/网/梯子 `return` | 自由落体/连续跳跃模拟错误 |
| onGround | `SimulationCheck.resyncShadow()` 用 `ctx.data.movement.onGround`（**客户端报的**）重同步 Shadow | fly 作弊谎报 onGround 会污染 shadow |
| 容差 | `sim-speed.horizontal-tolerance 0.03`、`sim-fly.vertical-tolerance 0.05`（按 √ticks 放大） | 偏宽，漏小幅加速（Grim 用 ~0.001–0.01） |
| Timer | `TimerCheck` 用 **wall-clock EPS**（6s/2s/burst 窗口算到达频率） | 网络抖动/丢包制造假波动 |
| Blink | `BlinkCheck` 用**沉默时长**（`silence > maxSilence(2000+ping)`） | 高 ping 边界只简单加 ping，粗糙 |
| 战斗 | `KillAuraCheck`(16 子 GCD/行为指纹)、`FastClickCheck`(cps+burst+CV)、`ReachCheck`、`ScaffoldCheck`、`CriticalsCheck` 全是**纯启发式/GCD** | 没有统计层（熵/KS/IQR）和 ML/RNN，深度不及 MX |
| 基础设施 | `Check` 基类有 `flag/bump/drain/sd/d/i/si` + VL 框架；`MovementTracker` 有 `iceTicks/slimeTicks/nearLiquidTicks/inWebTicks/ladderTicks/onGround/sprinting` | 缺 SensitivityProcessor、Transaction 延迟处理器、统计工具库、数据集管线 |

**结论**：不需要从零造引擎，重点是**把现有 `SimulationCheck`/`PredictionEngine` 完善到 Grim 级**，并把战斗类补上 MX 的统计/ML 层。

---

## 一、总体策略

| 借鉴对象 | 借鉴什么 | 落到 YCBR 哪 |
|----------|----------|--------------|
| **Grim** | 世界交互层（碰撞/液体/网/梯子/动态摩擦/活塞/载具）、容差盒哲学、Transaction 三明治、射线求交 Reach | Phase 1 移动、Phase 2 协议(Timer/Blink/Velocity)、Phase 3.5 Reach |
| **MX** | 统计工具箱（熵/KS/IQR/Z-score/峰度/Jiff）、灵敏度校准、多信号交叉、攻击阻断、数据集+ML/RNN、Aim 分层 | Phase 0 基础设施、Phase 3 战斗 |

**三阶段优先级**：
- **Phase 0 基础设施**（所有后续前提，解耦设计）
- **Phase 1 移动完善到 Grim 级**（最高优先级、最大短板）
- **Phase 2 协议事务化**（中工作量、快速见效）
- **Phase 3 战斗引入 MX 统计/ML 层**（补齐第二短板）

---

## 二、Phase 0 — 基础设施（前提）

### P0.1 Transaction / 延迟处理器（借鉴 Grim `LatencyHandler` + MX `LatencyHandler`）
- 新增 `core/TransactionTracker.java`：客户端发起 Transaction → 服务器确认 → 计算 RTT 与“玩家视角下的 tick 进度”。
- 暴露 `playerTransactionTick()`（玩家自己事务的到达节奏），**替换所有 `ping > cfg.maxPing()` 与 `ping` 估算**。
- 在 `AsyncPacketListener` / `MainThreadHandler` 接入；`PlayerData` 持有引用。
- **验收**：Timer/Blink/Velocity 后续阶段不再依赖 wall-clock/ping 估算。

### P0.2 SensitivityProcessor（借鉴 MX `api/player/SensitivityProcessor`）
- 新增 `core/SensitivityProcessor.java`：从旋转 GCD 反推灵敏度，暴露 `calculateSensitivity()`，有效区间 `[20,150]`。
- 攻击/视角类检测只在有效区间执行，避免不同鼠标灵敏度误杀。

### P0.3 统计工具库（借鉴 MX `millennium.math.Statistics`）
- 新增 `util/Statistics.java`：`getShannonEntropy`、`getKurtosis`、`getIQR`、`getZScoreOutliers`、`getJiffDelta`、`kolmogorovSmir...`（KS 检验）、`getDistinct`、`getStandardDeviation`、`getVariance`、`getAverage`、`getMin`/`getMax`。
- 战斗统计层的基础，Phase 3 直接调用。

### P0.4 惩罚框架增强
- **攻击阻断**（借鉴 MX `setAttackBlockToTime`）：`Check.flag` 增加“阻断攻击 N ms”模式，替代一切即封。
- **setback**（借鉴 Grim）：移动类 flag 时把 `ShadowPlayer` 回退到最近合法位置（防误判伤害）。
- **多信号交叉**：`PlayerData` 新增 `crossSignals` 集合；单一维度 flag 不直接 punish，需 ≥2 维度交叉（借鉴 MX 的 `localVlLimit` + 交叉）。

---

## 三、Phase 1 — 移动完善到 Grim 级（最高优先级）

### 1.1 给 PredictionEngine 补“世界交互层”（当前最大缺陷）
- 新增 `simulation/WorldProbe.java`：基于 Bukkit/NMS 读玩家脚下方块，判定碰撞盒、可站立、液体、网、梯子、活塞、载具。
- 改造 `PredictionEngine.predictSingle / candidates / candidatesMultiTick`：
  - 接收方块状态（`WorldProbe` 结果），而非仅 `frictionFactor` 常量。
  - **动态 friction**：冰 0.98、灵魂沙、床、蛋糕、小路、不同方块组合（当前只有三档）。
  - **碰撞截断**：撞墙 `motX/motZ` 归零（当前完全没有）。
  - **液体**：水/熔岩游泳速度（当前 `SimulationCheck` 遇液体 `return` → 盲区）。
  - **网/蜘蛛网**：减速（当前 `return` 跳过）。
  - **梯子/藤蔓**：攀爬（当前 `return` 跳过）。
  - **0.03 跳过 tick、活塞推动、粘液块弹射、末影珍珠、鞘翅、船/载具**。
- **验收**：`SimulationCheck` 在液体/网/梯子场景从“跳过”变为“正确模拟”，盲区消除。

### 1.2 完整垂直模拟
- 修正 `PredictionEngine.candidates()`：垂直速度跨 tick 传递；`ShadowPlayer.tick()` 正确推进 `motionY`。
- `NoFallCheck` 改用连续垂直候选（自由落体/连续跳跃能正确模拟）。
- **验收**：单 tick 与多 tick 的垂直预测一致；自由落体不误判。

### 1.3 不信任客户端 onGround
- `SimulationCheck.resyncShadow()` 改为：shadow 自己用 `WorldProbe` 判定 `onGround`（碰撞地面），**不再用 `ctx.data.movement.onGround`**。
- fly 作弊谎报 onGround 时 shadow 不被污染。
- **验收**：fly 作弊在 onGround 谎报下被稳定抓到，且不含糊重同步。

### 1.4 收紧容差
- `sim-speed.horizontal-tolerance` 默认从 `0.03` 下调至 `~0.01`（配合世界交互层精度，对齐 Grim）。
- `sim-fly.vertical-tolerance` 从 `0.05` 下调至 `~0.02`。
- 保留 √ticks 放大，但基准更严。
- **验收**：小幅加速（speed 微调）能被抓到，合法玩家不误伤。

### 1.5 迁移经验检测 → 引擎（消除双轨）
- `SpeedCheck`/`FlyCheck`/`NoFallCheck` 标记 `@Deprecated`，新引擎覆盖后逐步移除（保留为短期冗余兜底）。
- `NoSlowCheck` 接入引擎：吃东西/喝药时期望速度减半，用引擎候选校验。
- **验收**：魔法数检测下线，全部移动检测由 `SimulationCheck` 驱动。

---

## 四、Phase 2 — 协议事务化（Grim 思路，快速见效）

### 2.1 Timer 改造
- `TimerCheck.java` 改写：用 P0.1 的 `playerTransactionTick()` 校验“移动包频率 vs 玩家事务往返”，替换 wall-clock EPS。
- 消除网络抖动误判（`cfg.maxPing()` 过滤可移除）。
- **验收**：网络波动下 Timer 零误判；真实加速稳定抓到。

### 2.2 Blink 改造
- `BlinkCheck.java` 改写：用 **TransactionOrder / PacketOrder** 校验“移动包到达序号是否连续/重放”，替换沉默时长。
- 保留沉默时长作辅助（高 ping 兜底）。
- **验收**：改进版 Blink（囤包后重放）被正确识别顺序异常，而非仅测沉默。

### 2.3 Velocity 精确化
- `VelocityCheck.java` 改造：用 transaction 三明治确认击退何时到达客户端，区分“网络延迟”vs“真没被推”。
- **务必保留** `JumpReset` / `SprintReset` 细分（这是 YCBR 相对 Grim/MX 的差异化优势）。
- **验收**：高 ping 下 Velocity 误判大幅下降，且细分指纹不丢。

---

## 五、Phase 3 — 战斗引入 MX 统计/ML 层（补齐第二短板）

### 3.1 KillAura 加统计层（新子模块）
- 新增 `combat/aim/AimStatisticsCheck`（借鉴 MX `AimStatisticsCheck`/`AimComplexCheck`）：
  - 在现有 GCD 启发式（`GcdStable`/`GcdGrid`/`ConstStep`/`AxisAsym`/...）之上，新增：**Shannon 熵、IQR、Jiff 模式、KS 检验、Z-score 离群（随机化缺陷）、机械心跳**。
  - **多信号交叉**：启发式 flag + 统计 flag 同时命中才 punish（用 P0.4 的 `crossSignals`）。
  - **灵敏度校准**：P0.2 有效区间才执行。
- **验收**：AimBot/KA 检测深度接近 MX，误判率因交叉验证下降。

### 3.2 FastClick 增强
- 现有 `cps + burst + CV` 基础上，加 **峰度（Kurtosis<0 机械规律）+ Shannon 熵（极低熵）**（借鉴 MX `AutoClickerCheck`）。
- **默认开启**（MX 默认关是缺点，YCBR 应默认开但保守阈值）。
- **验收**：高级点击器（规律但 cps 不高）能被抓到。

### 3.3 数据集收集管线
- 新增 `core/DatasetManager.java`（借鉴 MX）+ `RECORDING` 模式 + `/ycbr record` 命令。
- 收集合法/作弊视角与点击样本，供 Phase 3.4 训练。
- **验收**：可一键采集样本，样本落盘可管理。

### 3.4 可选 ML/RNN 层（验证后上）
- 先训练**轻量 MLP on 统计特征**（暂不做 RNN，降低复杂度）。
- 集成进 `AimStatisticsCheck` 作为“增强”而非替代；启发式保底。
- **验收**：ML 只在统计+启发式交叉之上加成，数据集偏差时不独立误判。

### 3.5 Reach 精度（Grim 思路）
- `ReachCheck.java` 改造：`UseEntity` 时**精确射线-碰撞盒求交**；实体位置插值（多帧）；枚举当前/上一 `yaw·pitch`；**实时取消**不可能攻击（而非事后 flag）。
- **验收**：Reach 精度达到 Grim 级，误杀合法边缘命中下降。

### 3.6 Scaffold 协议校验补充
- 新增 ulp 浮点容差的放置校验（`FabricatedPlace`/`RotationPlace` 思路），与现有 `cursor/旋转/行为级(Cadence/Colinear/Grid45)` 互补。
- **验收**：非法放置（协议级）被抓到，且与行为级不重复误判。

---

## 六、关键文件改动清单

| 文件 | 动作 | Phase |
|------|------|-------|
| `core/TransactionTracker.java` | 新增 | P0.1 |
| `core/SensitivityProcessor.java` | 新增 | P0.2 |
| `util/Statistics.java` | 新增 | P0.3 |
| `Check.java` / `PlayerData.java` | 扩展（攻击阻断、setback、crossSignals） | P0.4 |
| `simulation/WorldProbe.java` | 新增 | P1.1 |
| `simulation/PredictionEngine.java` | 改造（世界交互、垂直传递、碰撞截断） | P1.1/1.2 |
| `simulation/ShadowPlayer.java` | 改造（onGround 自判、motionY 推进） | P1.2/1.3 |
| `check/movement/SimulationCheck.java` | 改造（WorldProbe 接入、容差收紧、不信任客户端 onGround） | P1.1/1.3/1.4 |
| `check/movement/SpeedCheck.java` 等 | `@Deprecated` → 移除 | P1.5 |
| `check/protocol/TimerCheck.java` | 改写（事务化） | P2.1 |
| `check/protocol/BlinkCheck.java` | 改写（PacketOrder） | P2.2 |
| `check/movement/VelocityCheck.java` | 改造（transaction 三明治，保留 JumpReset/SprintReset） | P2.3 |
| `combat/aim/AimStatisticsCheck.java` | 新增 | P3.1 |
| `check/combat/KillAuraCheck.java` | 扩展（接入统计层 + 交叉） | P3.1 |
| `check/combat/FastClickCheck.java` | 扩展（峰度+熵） | P3.2 |
| `core/DatasetManager.java` + `/ycbr record` | 新增 | P3.3 |
| ML 模块（可选） | 新增 | P3.4 |
| `check/combat/ReachCheck.java` | 改造（射线求交） | P3.5 |
| `check/combat/ScaffoldCheck.java` | 扩展（协议容差校验） | P3.6 |

---

## 七、验证与发布策略

1. **默认关闭 → 观察 → 调参 → 开启**：每个 Phase 的新检测先在 `config.yml` 默认关闭，观察 1–2 周误判日志，调参后默认开启。
2. **flag 原因标准化**：复用现有 `flag(data, type, detail)`，detail 包含关键数值（如 MX 的 `debug` 输出），便于建误判看板。
3. **回归测试**：扩展现有 `SimulationCheckTest` / `ShadowPlayerTest`，新增 `WorldProbe` 碰撞单测、液体/网/梯子场景单测、垂直模拟一致性单测。
4. **分层上线**：P1（移动）先上（最大收益），P2（协议）快速跟进，P3（战斗）验证后上（ML 谨慎）。

---

## 八、优先级与风险

| Phase | 优先级 | 工作量 | 收益 | 风险 |
|-------|--------|--------|------|------|
| P0 基础设施 | 高（前提） | 中 | 解锁后续所有 | 解耦设计需谨慎 |
| P1 移动完善 | **最高** | 高 | 补齐最大短板，移动类达 Grim 级 | WorldProbe 性能（需缓存方块查询） |
| P2 协议事务化 | 高 | 中 | Timer/Blink/Velocity 误判大幅下降 | transaction 实现复杂度 |
| P3 战斗统计/ML | 高 | 中高 | 补齐第二短板，接近 MX 水平 | ML 数据集偏差风险（启发式保底） |

**一句话路线**：先建 Transaction/灵敏度/统计三件套（P0）→ 把 `SimulationCheck` 补成真 Grim（P1）→ Timer/Blink/Velocity 事务化（P2）→ KillAura/FastClick/Reach 加统计层与可选 ML（P3）。
