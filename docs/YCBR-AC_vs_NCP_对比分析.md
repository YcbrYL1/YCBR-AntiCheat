# YCBR-AC 与 NoCheatPlus (NCP) 对比分析

> 评价对象：YCBR-AC（`com.ycbr.anticheat`）与 NoCheatPlus（`fr.neatmonster.nocheatplus`）
> 核心问题：在两者**相同的检测项**上，谁更强（抓取力）、谁误判更少？
> 分析依据：两边源码实测（NCP 706 个 Java 文件，多模块；YCBR 当前基线 v2026-08-14）。
> 注：NCP-PE（基岩版）为干扰项，已排除；本对比专指 PC Java 版 NCP。

---

## 一、一句话结论

**NCP 是 1.8 时代的"老牌规则引擎"——检测覆盖面极广（连挖掘/交互/放置/战斗/移动都有），但精度依赖 `Magic.*` 魔数经验拟合 + 海量豁免/workaround，对精确贴包络的作弊漏检率高。YCBR-AC 是现代化的"统计+事务"引擎，物理精度（SimulationCheck 引擎）、网络层（事务时钟）、战斗智能（统计+ML+交叉验证）全面领先 NCP，且误判控制更主动（交叉验证门控而非被动豁免）。**

论"相同检测谁更强、误判少"：
- **移动类（Speed/Fly/NoFall）**：YCBR 更强（引擎公式一致 + 事务化），误判更低（容差+VL 而非海量豁免）
- **Velocity**：YCBR 更强（事务到达判定 + SprintReset 指纹），但 NCP 的"速度账本"思路值得借鉴（YCBR 已部分实现）
- **KillAura/Aim**：YCBR 更强（NCP 仅 Angle/Direction/NoSwing/SelfHit 传统启发式，易绕过）
- **Timer/Blink/Sprint/Reach/Scaffold/Criticals/NoSwing/Passable**：YCBR 更强或持平；NCP 这些要么弱、要么靠近似
- **覆盖广度**：NCP 更广（含挖掘/交互/放置等 YCBR 未覆盖的非核心项），但每项都"浅"

---

## 二、架构与范式对比

| 维度 | YCBR-AC（当前） | NCP |
|------|---------|-----|
| 核心范式 | 统计 + 事务对齐 + 物理引擎（公式/候选枚举） | **魔数包络 + 频率桶 + 几何近似**（`Magic.*` 经验常数） |
| 物理精度 | 中高（SimulationCheck 引擎与 1.8.8 NMS 一致，默认关）；经验回退兜底 | 低（`Magic.WALK_SPEED` 等经验拟合 + `LostGround`/`BlockChangeTracker` 大量 workaround） |
| 事务(transaction) | **有**（TransactionTracker 逐玩家 RTT + kbArrivalServerTick） | **无**（wall-clock + `TickTask.getLag` 滞后补偿） |
| ML | 有 SimpleMLP（默认关） | 无 |
| 检测覆盖面 | 战斗+移动+协议三大类 ~13 项核心检测 | **极广但浅**（移动/战斗/挖掘/交互/放置/载具/聊天 全有，每项精度有限） |
| 误判哲学 | 容差 + VL 缓冲 + **交叉验证门控**（主动控误判） | **重豁免/workaround/VL 衰减**（被动容忍，低误判优先） |
| 维护代价 | 低（公式清晰） | 高（源码满是 `TODO: Remove fumbling with magic constants`） |

**范式差异本质**：NCP 用"经验魔数 + 事后豁免"在 1.8 时代做到了稳定低误判，但这是**精度换稳定性**——精确贴包络的 cheat 能绕过。YCBR 用"物理公式 + 事务时钟"从根上确定合法范围，精度上限更高。

---

## 三、逐项深度对比

### 3.1 移动类（Speed / Fly / NoFall）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🔴 更强 | 🟢 较低 | SimulationCheck 引擎（模长匹配+服务器 onGround+idle 候选+楼梯豁免）；生产跑经验回退（魔法数+上下文加成）但**有事务化兜底** |
| NCP | 🟡 较弱 | 🟡 中（靠重豁免维持低） | `SurvivalFly` 魔数包络（`Magic.WALK_SPEED` 等）+ `LostGround`（丢地判定）+ `BlockChangeTracker`（方块变化推拉豁免）海量 workaround |

**结论：YCBR 更强。** NCP 的 `LostGround`/`BlockChangeTracker` 是 1.8 时代补丁式逻辑，维护成本高、对新型移动作弊覆盖弱。

### 3.2 NoSlow

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🟡 | 🟢→🟡 较低（引擎 `predictSingle(usingItem=true)` + `ItemUseLogic` 只认吃/喝/拉弓） |
| NCP | 🟡 | 🟢→🟡 较低（融入 `SurvivalFly` 移动处理） |

**结论：持平，误判均低。**

### 3.3 Timer

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🔴 更强 | 🟢→🟡 较低（TPS 归一化，修 19.2 TPS 系统误判） | 服务器 tick 间隔（长/短/突发三窗口）+ 事务活性前置 |
| NCP | 🟡 较弱 | 🟡 中（`lag>1.5f` 跳过 VL，阈值经验值） | `MorePackets` EPS 频率桶 + 突发检测 + `TickTask.getLag` 补偿 |

**结论：YCBR 更强。** NCP 的频率桶对温和加速与漏包区分弱，且依赖 `TickTask.getLag` 滞后补偿（不如事务时钟精准）。

### 3.4 Blink

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🔴 更强 | 🟢→🟡 较低（"有事务 pong 无移动包"核心判定，2s 阈值 + 保活） |
| NCP | 🟡 弱 | 🟡（无专门 Blink；`MorePackets` 是包率非囤包检测） |

**结论：YCBR 更强。** NCP 无专门囤包检测。

### 3.5 Velocity（击退）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🔴 更强 | 🟢→🟡 较低 | 事务化（`kbArrivalServerTick` + 到达窗口 ±1 tick）+ **保留 JumpReset/SprintReset 行为指纹**（NCP 无） |
| NCP | 🟡 较弱 | 🟡 中 | **速度账本**（`SimpleAxisVelocity`/`FrictionAxisVelocity`：入队-消耗-容差，识别"被发出但玩家从未消费的速度"）+ 无事务，纯统计 |

**结论：YCBR 更强（到达时刻精度 + 行为指纹覆盖）。但 NCP 的"速度账本"思路是亮点——YCBR 已在 Phase 8 实现 `simulation/VelocityLedger`（水平账本，`HORIZONTAL_DECAY=0.91`/`DIRECTION_DOT=0.6`/`MIN_CONSUME_RATIO=0.35`），互补提升精度。**

### 3.6 Reach

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🔴 更强 | 🟢→🟡 较低（多帧射线-AABB + 插值回退 + 双方移动 allowance + `shouldCancelAttack` 实时取消） |
| NCP | 🟡 较弱 | 🟡 中（眼睛到实体中心"点到点"近似 + 动态 `reachMod` 收敛；贴包络 legit 抖动易误判、微调距离易绕过） |

**结论：YCBR 更强。** NCP 的射线是点到点近似，精度最低。

### 3.7 KillAura / Aim

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🔴 更强 | 🟡 中（9 瞄准子项被 aimstat 交叉验证门控，默认关则零误判） | 16+ 启发式（GCD/方差/行为指纹）+ 统计层（熵/KS/IQR/峰度/Z/Jiff）+ SimpleMLP + 交叉验证 |
| NCP | 🟡 较弱 | 🟡 中 | `Angle`（转向角加权）+ `Direction`（视线到盒偏移）+ `NoSwing`（无挥手）+ `SelfHit`（自伤），**纯传统启发式** |

**结论：YCBR 更强。** NCP 的 KA 检测是 2010 年代传统启发式，aimbot 微偏即可绕过；YCBR 的统计+ML+交叉验证维度远超。

### 3.8 AutoClicker

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🔴 更强 | 🟡 中（burst + cps + Interval(CV) + `FastClickLogic` 峰度/熵机械节律，默认可用） |
| NCP | 🟡 弱 | 🟡（无专门 AC，靠 `fight/Speed` 攻击频率间接） |

**结论：YCBR 更强。**

### 3.9 Scaffold

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🔴 更强 | 🟢→🟡 较低（协议/旋转/放置层已开 + 行为层 cadence/colinear/grid45/duprot 默认关） |
| NCP | ⚪ 无专门 | ⚪（无 Scaffold 类；`Passable` 是穿墙非 scaffold） |

**结论：YCBR 更强（NCP 无此项）。**

### 3.10 Sprint

| 家 | 抓取力 | 误判 |
|----|--------|------|
| YCBR | 🔴 更强 | 🟢→🟡 较低（6 类状态合规：饥饿/潜行/用物品/失明/头顶挡/水中 + 翻转双条件 + `max-flip-gap-ms` 40ms + usingItem 超时复位 1500ms） |
| NCP | 🟡 弱 | 🟡（无独立 sprint 合规；`SurvivalFly` 间接处理速度） |

**结论：YCBR 更强（NCP 无专门合规校验）。**

### 3.11 NCP 独有但 YCBR 已借鉴的特色

| 检测 | NCP 实现 | YCBR 现状 |
|------|----------|-----------|
| Criticals（假性暴击） | 状态机 `Critical.java` | ✅ 有 `CriticalsCheck` |
| NoSwing（无挥手攻击） | `fight/NoSwing.java` | ✅ 有 |
| Passable（穿墙） | **几何射线追踪** `Passable.java`（NCP 最接近真仿真的部分，多轴序取最宽松） | ✅ 已借鉴实现 `simulation/RayMarchUtil`（DDA 体素步进，Phase 8 `45e7c99`） |
| Improbable（跨检测融合） | `combined/Improbable.java` 频率桶融合 | ✅ 有交叉信号框架基础（Phase 6） |
| Velocity 速度账本 | `SimpleAxisVelocity`/`FrictionAxisVelocity` | ✅ 已实现 `VelocityLedger`（Phase 8 `36c1de6`） |

> YCBR 已主动吸收了 NCP 最具价值的三个设计（账本/几何射线/融合），并在实现上现代化（事务化、DDA 步进）。

---

## 四、误判控制机制对比

| 机制 | YCBR-AC | NCP |
|------|---------|-----|
| VL / 缓冲 | 有（各 check 独立 VL + 衰减） | 有（VL + `cancel` 级联惩罚 + 衰减） |
| 误判控制哲学 | **主动**：容差 + 交叉验证门控（`shouldPunish` 多信号）+ 事务时钟 | **被动**：重豁免/workaround（`LostGround`/`BlockChangeTracker`/`Magic.*` 容差）+ lag 跳过 |
| 网络/ping 处理 | **事务时钟**（RTT + 到达 tick 推算，对齐 Grim） | wall-clock + `TickTask.getLag` 滞后补偿 |
| 漏检代价 | 低（精度高，精确 cheat 难绕过） | 高（经验魔数，贴包络 cheat 绕过） |

**误判维度总评**：在相同检测项上，YCBR 误判普遍更低或持平；NCP 靠海量豁免把表面误判压住，但**这是以高漏检率为代价的**——NCP 的低误判"虚假繁荣"，精确 cheat 作者最喜欢 NCP 这类规则引擎。

---

## 五、给 YCBR-AC 的建议（关于 NCP）

**应该借鉴（低成本高回报）：**
1. **速度账本** ✅ 已实现（`VelocityLedger`）—— 与事务到达判定互补，提升击退绕过识别。
2. **Passable 几何射线** ✅ 已实现（`RayMarchUtil`）—— 多轴序取最宽松 + 起始格不判 + 实时方块跟踪。
3. **Improbable 跨检测融合** —— 各检测小违规喂统一频率桶，短窗/全窗超阈值才升级 VL；YCBR 已有框架，建议升级为 NCP 式全量融合。

**不应该学（避免退化）：**
- **不学 `Magic.*` 魔数经验拟合**：NCP 源码满是 `TODO: Remove fumbling with magic constants`，精度上限低、维护成本高。YCBR 的事务化 + 引擎公式已优于此路。
- **不学 NCP 把兜底全交给豁免/workaround**：这是 1.8 时代的权宜之计，现代反作弊应从"精确预测合法范围"入手（YCBR 已做到）。

---

## 六、双维度总评

| 检测项 | 抓取力 | 误判 |
|--------|--------|------|
| Speed/Fly/NoFall | YCBR > NCP | YCBR 较低 / NCP 中（重豁免） |
| NoSlow | 持平 | 均低 |
| Timer | YCBR > NCP | YCBR 较低 / NCP 中 |
| Blink | YCBR > NCP | YCBR 较低 / NCP 中 |
| Velocity | YCBR > NCP | 均较低（NCP 账本思路已借鉴） |
| Reach | YCBR > NCP | YCBR 较低 / NCP 中 |
| KillAura/Aim | YCBR > NCP | 均中（YCBR 交叉验证更可控） |
| AutoClicker | YCBR > NCP | 均中 |
| Scaffold | YCBR > NCP（无） | — |
| Sprint | YCBR > NCP | YCBR 较低 / NCP 中 |
| Criticals/NoSwing/Passable | YCBR ≈ NCP（各有实现） | 均低 |

**一句话**：YCBR 在每个相同检测项上都**强于或持平 NCP**，且误判控制更主动。NCP 唯一优势是**覆盖广度**（连挖掘/交互/放置都检测），但这些是 YCBR 作为"核心战斗/移动/协议基座"设计上未优先覆盖的非关键项。NCP 最具价值的设计（速度账本/几何射线/融合）已被 YCBR 现代化吸收。

---

*配套文档：`YCBR-AC_vs_Grim_对比分析_v2.md`（双家）、`YCBR-AC_vs_MX_对比分析.md`（双家）、`2026-08-14-quad-analysis-grim-mx-ncp.md`（四家总览）、`docs/2026-08-14-simulation-tuning-sop.md`（仿真调参 SOP）。*
