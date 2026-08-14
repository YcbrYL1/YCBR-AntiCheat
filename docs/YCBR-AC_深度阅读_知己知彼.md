# YCBR-AC 深度阅读报告：知己知彼（源码 + 计划 + 对手模块）

> 阅读范围：
> - **YCBR-AC 反作弊源码**（你的项目，`com.ycbr.anticheat`，19 个检测类）
> - **`YCBR-AC/docs/plans/` 5 份计划文档**（design / impl / phase01~03）
> - **`ycbr/` 说明网页**（对手——LiquidBounce 第三方绕过模块集，针对 GrimAC / YCBR-AC）
>
> 核心结论：**你是反作弊作者，而 `ycbr/` 正是一套专门绕过你这类反作弊的"敌方说明书"。** ycbr 对手当前利用的，恰好是你 Phase 2（协议事务化）与 Phase 3（战斗统计层）**还没实现**的部分。

---

## 一、YCBR-AC 现状

### 1.1 架构（已读 `YCBR.java` / `AntiCheatManager.java` / `CheckRegistry.java`）

- 入口 `YCBR`（JavaPlugin）→ 强依赖 **ProtocolLib**（缺失即自禁用）。
- 中央 `AntiCheatManager` 挂载：
  - `DataManager`（每玩家 `PlayerData`）
  - `EntitySnapshotService`（实体快照供 Reach/攻击判定）
  - `CheckRegistry`（19 个检测 + 异步遍历分发）
  - `MainThreadHandler`（主线程管线：方块探测、tick 推进、惩罚执行）
  - `AsyncPacketListener`（异步包监听，含 teleport resync / velocity 注入 / sprinting 设置）
  - 多个 Manager：`BanManager` / `AuthManager` / `BotManager` / `DDosGuard` / `GhostManager`
- 检测调度：`CheckRegistry.onMove/onAttack/onPlace/onRotation/...` 对 `checks` 列表**同步遍历**，op 玩家整体跳过。
- 判定结果经 `Verdict` 队列回主线程执行惩罚（`kick` / `setback`）。
- 异步线程池：`PlayerActor` 驱动（`executor` 固定线程池，大小取自 `cfg.checkThreads()`）。

**评价**：架构比一般个人反作弊专业——异步检测 + 主线程惩罚分离、实体快照、ghost/ddos/bot 管理齐全。这是好底子。

### 1.2 检测清单（`CheckRegistry` 实际挂载）

| 类别 | 检测类 | 范式 | 备注 |
|------|--------|------|------|
| 移动 | `SpeedCheck` / `FlyCheck` / `NoFallCheck` / `NoSlowCheck` | 经验公式 | 主力，仍开启 |
| 移动 | `SimulationCheck` (sim-speed/sim-fly) | 预测引擎（Grim 级雏形） | **默认关闭**，待验证 |
| 战斗 | `KillAuraCheck`（16 子检测）| 纯启发式 | 最强项 |
| 战斗 | `ReachCheck` / `FastClickCheck` / `CriticalsCheck` / `AutoToolCheck` / `BowCheck` | 启发式 | |
| 协议 | `TimerCheck` / `BlinkCheck` / `VelocityCheck` / `SprintCheck` / `ProtocolCheck` / `WrongTurnCheck` / `FastThrowCheck` | 阈值/状态 | wall-clock / silence / pingTicks 估算 |
| 模拟 | `PredictionEngine` + `ShadowPlayer` + `WorldProbe` | 纯 Java 物理 | 已落地 |

### 1.3 实现进度（Grep + 源码核实）

| 阶段 | 计划项 | 状态 |
|------|--------|------|
| **Phase 0 工具库** | `Statistics`（熵/峰度/IQR/KS/Z-score/Jiff） | ✅ 已实现 + 测试 |
| | `SensitivityProcessor`（GCD 反推灵敏度）| ✅ 已实现 + 测试 |
| | `TransactionTracker`（事务 RTT）| ❌ 仅计划 |
| | 惩罚框架（blockAttacks / setback / crossSignals）| ⚠️ 未逐文件核实 |
| **Phase 1 移动（Grim 级）** | `PredictionEngine` / `ShadowPlayer` / `SimulationCheck` | ✅ 已实现 + 提交 |
| | `WorldProbe`（世界状态）| ✅ 已实现（**适配层**，见下）|
| | Speed/Fly/NoFall/NoSlow 经验公式 | ⚠️ 仍并行开启为主力 |
| **Phase 2 协议事务化** | `TimerLogic` + Timer 事务化 | ❌ 未实现 |
| | Blink 序号连续性 / pong 活跃检测 | ❌ 未实现 |
| | Velocity 事务三明治到达窗口 | ❌ 未实现 |
| **Phase 3 战斗统计（MX 级）** | `AimStatisticsCheck` | ❌ 未实现 |
| | `FastClickLogic`（峰度+熵）| ❌ 未实现 |
| | Reach 多帧射线求交 | ❌ 未实现 |
| | `DatasetManager` / `SimpleMLP` | ❌ 未实现 |

> **偏差澄清**：`WorldProbe` 实际实现为**数据适配层**（`fromPlayerData` 从 `PlayerData` 已探测字段：`blockOnIce / blockNearLiquid / blockInWeb / blockOnLadder / blockBoxedIn / blockOnSlime` 构建 `ProbeResult`），**不是**计划 design 文档里写的"主线程查询门面 + ttl 缓存"。真实方块探测职责在 `MainThreadHandler`（每 tick 写入 PlayerData）。这其实更合理——主线程探测、异步线程消费，避免预测线程触碰 Bukkit API。

---

## 二、`docs/plans/` 计划解读

5 份文档是你（用 Claude）写的可执行改造路线，质量很高：

1. **`prediction-engine-design.md`**：论证方案一（纯 Java 参数化模拟）优于方案二（NMS 全模拟），从 1.8.8 NMS 源码转写物理公式（摩擦/跳跃/输入/重力/垂直拖拽），定义候选枚举与容差盒。
2. **`prediction-engine-impl.md`**：**已落地**——含完整提交记录（`9437e18`~`230aeb0`）、12/12 测试通过。这是目前唯一有"实施结果"的文档。
3. **`phase01-grim-tier-movement.md`**：把移动引擎补强到 Grim 级（WorldProbe 世界交互层 + ShadowPlayer 自判 onGround + 容差收紧 0.03→0.01 + 事务化基础设施 + 统计/灵敏度工具 + 惩罚框架 + NoSlow 接引擎 + 经验检测 @Deprecated）。**部分落地**（WorldProbe/Statistics/SensitivityProcessor 在；TransactionTracker/NoSlow 引擎接入未确认）。
4. **`phase02-protocol-transaction.md`**：Timer/Blink/Velocity 从事务往返精确判定，消除高 ping 误判，保留 Velocity 细分指纹。**完全未实现**。
5. **`phase03-combat-statistics.md`**：战斗类借鉴 MX 补统计层（熵/IQR/KS/Jiff/Z-score/机械心跳）+ FastClick 峰度熵 + Reach 多帧 + DatasetManager + 可选 MLP。**完全未实现**。

---

## 三、`ycbr/` 对手模块详解（敌方说明书）

> 这是 LiquidBounce-nextgen 的第三方增强模块集，**专为你这类反作弊设计绕过**。逐项提炼其"破防核心"：

### 3.1 击退控制（4 模式，全部"针对 GrimAC"）
- **YcbrRevert**：击退包延迟 `LagTime` 拍 → 地面静止 `StuckTicks` 后释放，使击退被"摩擦力自然消耗"。支持 JumpReset / 爆炸 / 方向旋转。
- **YcbrJumpReset**：受击 `DelayTicks` 拍后跳一次，消耗下一个地面 tick 的击退窗口（模仿原版疾跑跳跃重置）。触发 `hurtTime 9..10` + 地面 + 疾跑。
- **YcbrCounterStrafe**：**不取消**击退包，而是每 tick 向反方向施加 `kb/Ticks×Strength` 速度，净位移≈0。
- **YcbrGrimOnGround**：最激进——地面静止门槛 + 敌人距离释放（战斗中保持可命中）+ 空中延迟到落地再处理 + 8 tick 抑制窗口。

### 3.2 战斗增强
- **YcbrGrimCrit**：仅在 `fallDistance>0` 时注入微小 Y 偏移（`y-=0.000001` / `y+=0.1→0.01` / `y+=0.0625→0.0625013579`）绕过暴击检测。
- **YcbrKillAura**：Silent 静默旋转（客户端视角不变）+ **±0.02° Jitter 破 ACAOX 的 PerfectRotation/EqualRotation** + raytraceBox 瞄准 + SnapSensitivity 量化伪装（Verus/NCP 伪装）+ AutoBlock。

### 3.3 闪烁（Blink）
- **YcbrBlink**：智能过滤（仅延迟移动/攻击/交互包，聊天/确认包 PASS）+ 每 5 tick 全量 flush（避 Pulse）+ **旋转 ±0.25°/±0.15° jitter 破 ACA 完美旋转** + **BadPacketsE 保护（19 tick 无位置包紧急 flush）+ 保留 KeepAlive/Pong 保活** + 假人克隆 + Setback 检测。

### 3.4 自动搭桥（Telly）
- **YcbrTelly**：21 拍脚本曲线 + **GCD 量化 + yaw nudge 微抖** 防检测 + 直线车道约束（偏离即拦为 ghost block）+ AntiSway PID 车道保持 + OpenZen 6 项（**YawOffsetBypass：写 yRot 前 yaw+720 绕开 yaw 区间校验**、KnockbackPause、ClutchPredict 等）。

### 3.5 禁用器（11 项，针对 GrimAC / ACA）
GrimBadPacketsA/W（重复切槽/无效实体攻击 cancel）、GrimNoSlow（`PlayerUseMultiplier` 覆盖倍率为 1.0 全速）、GrimKeepAlive（延迟 KeepAlive 打乱时序）、GrimPost（补发位置包满足 post 检查）、GrimDuplicateRotPlace（放置旋转 0.001-0.009° 抖动）、AcaInventoryFreq / AcaRotationJitter（σ=0.005° 高斯噪声）/ AcaFastSwitch / ThemisBlink（每 200ms 发 Pong 保活）。

---

## 四、知己知彼对照矩阵（★★★ 核心）

> 威胁等级：🔴 高危（你的当前实现极易被绕过）｜🟡 中危（部分覆盖/猫鼠博弈）｜🟢 低风险（你的检测已正面覆盖）

| ycbr 绕过策略 | 针对你的检测 | 威胁 | 原因与应对 |
|---|---|---|---|
| **Blink 保活 Pong + 19tick flush + 智能过滤** | `BlinkCheck` | 🔴 | 你 Blink 仍用 `silence > 2000+ping` 沉默时长；ycbr 保留 KeepAlive/Pong 使"有 pong 无移动包"检测失效。**你的 phase02 修复（pong 活跃检测）未实现** → 现在极易绕过。 |
| **4 种 Velocity 模式（Revert/JumpReset/CounterStrafe/GrimOnGround）** | `VelocityCheck` | 🔴 | 全部利用"延迟/静止/反向"消耗击退窗口，而你的判定仍基于 `pingTicks=ceil(ping/50)` 估算窗口。**transaction 三明治未实现** → 静默门/反向抵消都落在你的容差盲区。 |
| **Telly：GCD量化 + yaw nudge + YawOffsetBypass(+720)** | `ScaffoldCheck` | 🟡 | 破"重复旋转"与 cursor 越界；yaw+720 绕开 yaw 区间校验。你的 Cadence/Colinear/Grid45 行为检测可能仍抓"完美每 tick 搭路"，但旋转类子检测被削弱。 |
| **KillAura：Silent 旋转 + ±0.02° Jitter** | `KillAuraCheck` | 🟡 | Jitter 专门破"完美旋转"类检测；但你的 16 子检测含统计指纹（GCD/方差/AxisAsym/Modulo360/Interval CV），对静默旋转 + 微抖有**一定鲁棒性**（猫鼠博弈，非单点失守）。 |
| **GrimNoSlow（倍率覆盖 1.0）** | `NoSlowCheck` | 🟡 | 客户端报全速移动；若你仍用经验公式 `lastXZ*0.92+0.01` 只看客户端速度则漏。你的 phase01 计划把 NoSlow 接引擎 `candidates(usingItem)`，需确认是否已接——**接了则能防，未接则漏**。 |
| **AcaRotationJitter（σ=0.005°）** | `KillAuraCheck` / `WrongTurnCheck` | 🟡 | 通用破完美旋转，与上面 KA Jitter 同源。 |
| **YcbrGrimCrit（微小 Y 偏移）** | `CriticalsCheck` | 🟢 | 你的暴击检测若查"击中瞬间 fallDistance/oonGround 矛盾"应能抓；需确认阈值是否覆盖 `y-=0.000001` 级偏移。 |
| **YcbrJumpReset / CounterStrafe** | `VelocityCheck` | 🟢 | **你的差异化优势**：`VelocityCheck` 的 `JumpReset`/`SprintReset` 子检测正是为此设计。YcbrJumpReset 若时机不完美会被指纹命中。 |
| **GrimBadPacketsA/W / GrimPost / ThemisBlink** | （无对应检测）| 🟢 | 这些针对 GrimAC/ACA 的 BadPackets，你**根本没有 BadPackets 类检测**，对 YCBR 无影响（只是说明对手重心在 Grim）。 |

---

## 五、优先级行动清单（基于"知彼"）

### P0 — 立刻堵最高危（对手正在利用的盲区）
1. **实现 `TransactionTracker` + Blink pong 活跃检测**（phase02 任务2）：把"有 pong 无移动包"作为 Blink 核心判定，直接废掉 ycbr 的 BadPacketsE 保护 + Pong 保活。
2. **Velocity 事务到达窗口**（phase02 任务3）：用 transaction RTT 算击退到达客户端时刻，替换 `pingTicks` 估算；保留 JumpReset/SprintReset 细分。

### P1 — 强化移动护城河
3. **SimulationCheck 实机验证后开启**，替换 Speed/Fly/NoFall 经验公式（phase01 已落地但默认关）。
4. **确认 NoSlow 是否已接引擎 `candidates(usingItem)`**（phase01 任务9），未接则补，防 GrimNoSlow 绕过。

### P2 — 战斗对抗 Jitter / 静默旋转
5. **`AimStatisticsCheck` 交叉验证**（phase03 任务1/2）：在现有 16 子检测之上叠统计层（熵/IQR/KS/Jiff/机械心跳），单一信号永不 punish，增强对 Jitter/静默旋转的鲁棒性。
6. **`FastClickLogic` 峰度+熵**（phase03 任务3）：补机械点击维度。

### P3 — 可选增强
7. Reach 多帧射线求交（phase03 任务4）、DatasetManager/可选 MLP（任务5/6）。

---

## 六、总结

- **你已做对的事**：移动预测引擎（Grim 级雏形）+ 统计/灵敏度工具库 + 专业异步架构 + Velocity 细分指纹——底子远好于普通个人反作弊。
- **最大漏洞**：Phase 2 协议事务化**完全未做**，而 ycbr 的 Blink 与 4 种 Velocity 模式**专门利用你基于 ping/wall-clock 的估算弱点**。这是当前最该先补的。
- **你的护城河**：KillAura 纯启发式 16 子检测对 Jitter 类绕过有韧性；Velocity JumpReset/SprintReset 正面覆盖 YcbrJumpReset/CounterStrafe。
- **一句话**：先把"事务化"补上（堵 Blink + Velocity 两大高危），再开移动引擎替换经验公式，最后上战斗统计层——顺序对了，ycbr 这套绕过模块会大量失效。
