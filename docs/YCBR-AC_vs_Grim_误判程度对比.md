# YCBR-AC vs GrimAC：误判程度专项对比（重读版）

> 基线：2026-08-14 重新通读 YCBR-AC 当前源码（含 Aug 14 之后的 10+ 个新提交：
> `9ca4be6` sim-speed 疾跑候选行独立、`e9aa4f1` 楼梯/半砖 0.5 豁免、
> `af154b1` noslow 只认真使用物品、`ff4e296` Timer 按 TPS 归一化、
> `d481903` Reach 实时取消不可能攻击、`6a61630` Scaffold 行为子项默认关 等）。
>
> 说明：本文只回答一个问题——**相同的检测项，谁的误判（false positive）更少、为什么**。
> 不比较抓取力（见《YCBR-AC_vs_Grim_对比分析.md》）。
> 凡标注「默认关」者，开启前不产生该项误判；标注「经验回退」者为当前生产实际运行的检测。

---

## 0. 一句话结论

**Grim 在"移动类"误判上仍结构性领先（靠碰撞盒模拟），但 YCBR 自上一版文档以来已补齐数处最大短板：Sprint 升级为状态合规（原最差项）、Timer 修掉 19.2 TPS 系统误判、Reach 实时取消、Scaffold 行为子项默认关。当前 YCBR 唯一仍明显弱于 Grim 的是「移动类精度」——因为生产实际跑的是经验回退 Speed/Fly/NoFall，引擎 sim-speed/sim-fly 仍默认关。**

Grim 的低误判是**架构性**的（碰撞盒模拟 + 事务时钟 + 协议级校验）；YCBR 的优势是**误判可控性**（每项可独立开关、VL 缓冲、交叉验证、灵敏度校准、实时取消降级）。

---

## 1. 误判程度对比总表（重读更新）

图例：🟢 低（极少误判）· 🟢→🟡 较低 · 🟡 中 · 🔴 较高

| 检测项 | Grim 误判 | YCBR 误判（当前） | YCBR 主要误判来源 / 现状 |
|--------|-----------|-------------------|--------------------------|
| Speed / Fly / NoFall（生产） | 🟢 低 | 🟡 中 | **经验回退**（魔法数容差 + 上下文加成），非碰撞模拟；台阶/液体边界靠加成兜底。引擎 sim-speed/sim-fly **默认关** |
| NoSlow | 🟢 低 | 🟢→🟡 较低 | 引擎 predictSingle(usingItem) + usingItem 已修（只认吃/喝/拉弓）；100ms 窗口守卫 |
| Timer | 🟢 低 | 🟢→🟡 较低 | **已 TPS 归一化**（19.2 TPS 系统误判已修）；三窗口 + min-tps 15 门槛 |
| Blink | 🟢 低 | 🟢→🟡 较低 | "有事务 pong 无移动包"核心判定；极少数站立不发位置包边缘 |
| Velocity | 🟢 低 | 🟢→🟡 较低 | 事务到达窗口 ±1 tick；JumpReset/SprintReset 高特异指纹 |
| Reach | 🟢 低 | 🟢→🟡 较低 | **已实时取消不可能攻击** + 多帧射线-AABB + 双方移动 allowance；边缘为实体极速外推 |
| KillAura / Aim（启发式部分） | —（无专精） | 🟡 中 | 9 个瞄准子项已被 aimstat **交叉验证门控**；其余直判（selfinteract/autoblock…） |
| AutoClick | —（无专精） | 🟡 中 | 真人高速连点边缘规律；burst + 机械节奏（峰度/熵） |
| Scaffold（已开启子项） | 🟢 低 | 🟢→🟡 较低 | 仅协议/旋转/放置层级（fabricated/footclick/place-aim/rotation/fast-place/move-place）开启；行为子项**默认关** |
| Sprint | 🟢 低 | 🟢→🟡 较低 | **已升级为 6 类状态合规**（对齐 Grim）；误判来源从"网络重排"转为"状态快照"，残留极低 |

> 对比上一版文档的变化：**Sprint 🔴→🟢→🟡（已补齐）**、**Timer 19.2 TPS 系统误判已消除**、**Scaffold 行为子项默认关（🟡→🟢→🟡）**、**Reach 实时取消（后果降级）**。**Speed/Fly/NoFall 仍是 YCBR 最大弱项**（经验回退 vs Grim 碰撞模拟）。

---

## 2. 逐项深度分析（基于当前源码）

### 2.1 Speed / Fly / NoFall —— YCBR 仍明显弱于 Grim（生产跑经验回退）

**Grim（低）**：完整碰撞盒重演，合法位移由世界唯一确定，容差仅覆盖浮点/量化误差。台阶、半砖、墙边、活塞全部纳入模拟，不存在"猜阈值"空间。

**YCBR（中，经验回退）**：`SimulationCheck`（sim-speed/sim-fly）**默认 `enabled: false`**，生产实际运行的是 `@Deprecated` 的 `SpeedCheck`/`FlyCheck`/`NoFallCheck` 经验公式：
- **SpeedCheck**：地面/空中动量模型 + 冰/史莱姆/撞墙/落地加成 + 速度/跳跃药水 + 3 tick 尖峰宽限 + 4 样本均值 + `min-overage 0.02` + `vl-before-flag 8`。加成较全，但本质仍是"阈值魔法数"，台阶边缘/墙边滑动/楼梯的预测偏差只能靠加成与容差吸收。
- **FlyCheck**：rise（motionY 超期望 + 跳跃容差，史莱姆/梯子/液体豁免，1 tick 宽限）+ level（悬停 ≥8 tick，传送/击退豁免）。
- **NoFallCheck**：仅在 `blockBelowUnstandable`（脚下空气/水/岩浆）且客户端谎报 onGround（airTicks>8、motionY<-0.4）且 20 tick 窗口内 ≥8 次谎报才 flag——非常保守，真漏真打才命中。

**现状**：引擎（"初级版 Grim"）已实现 8 候选模长匹配、楼梯/半砖 **step 豁免**（`WorldProbe.stepVerticalAllowed`，`|dy|≤0.6` 且脚下确为台阶/楼梯）、液体/网/梯子容差 ×2、多 tick 模拟（`ticks>1` 时容差 `×√ticks`）。但**未在生产默认开启**，故当前移动类误判仍由经验回退主导，高于 Grim。

### 2.2 NoSlow —— 已显著降低（🟢→🟡）

`NoSlowCheck` 用引擎 `predictSingle(carried,0,0,true,…,usingItem=true)` 预测"应减速"位移，实际显著超出才 flag。关键修复 `af154b1`：
- `ItemUseLogic.isUseItem()` 现在**只认吃/喝/拉弓/牛奶/钓鱼**，**排除放置方块/工具/剑**，避免放方块误置 `usingItem` 卡死导致正常走路误判；
- 多重守卫：`onGround && groundTicks>=2`、无液体/网/冰/史莱姆/梯子/撞墙、非 `blockingSword`、无近期击退、`lastItemUseTime` 100ms 内；
- `tolerance: 0.045`、`streak: 2`、`vl-before-flag: 4`。

### 2.3 Timer —— 系统误判已修（🟢→🟡）

`TimerLogic.normalizedInterval(intervalTicks, tps) = intervalTicks × 20 / max(tps,10)`：**按实际 TPS 归一化**。修复了 `ff4e296` 指出的问题——TPS<20（如 19.2→tick 52ms）时正常玩家每包仅覆盖 0.96 tick，未经归一化会系统性误判为加速；归一化后回正 ≈1.0，真加速器（40ms/包）仍 <1。三窗口（长/短/突发）+ `min-tps 15` 门槛 + `vl-before-flag 5`。与 Grim 同级（时钟源不同：事务 vs 服务器 tick）。

### 2.4 Blink —— 持平（🟢→🟡）

核心判定改为"**有事务 pong 无移动包**"（客户端网络活着却超 2s 不发位置 = 囤包），事务未初始化时退回"超时 + ping 补偿"兜底，5s cooldown。Grim 对重放次序的校验更细（能抓部分改进版 Blink），主场景覆盖一致。

### 2.5 Velocity —— 持平（🟢→🟡）

事务三明治 `kbArrivalServerTick = 发出tick + ceil(rttMs/50)`，`arrival-window-ticks: 2` 覆盖 ±1 tick；precise band（0.97~1.2）+ JumpReset/SprintReset 高特异指纹（真人几乎不会偶发）。墙/天花板/地面豁免齐全。与 Grim 同级，行为指纹项误判极低。

### 2.6 Reach —— 后果已降级（🟢→🟡）

`ReachCheck`：
- `shouldCancelAttack()`（commit `d481903`）：监听线程预检，距离超限且**所有帧射线均未命中 → 取消本次攻击**而非封禁，把误判后果从"VL 封禁"降级为"该次攻击无效"；
- 多帧视角枚举 `hitsFromAnyFrame`：实体插值位置回退（`target - v*ageTicks`）+ `expand 0.05` + 最近 2 tick 旋转历史，防擦边误杀；
- 双方 allowance：攻击者移动 `attackAllowance` + 受害者移动 `victimAllowance`，各按 tick×速度上限 0.4；
- `max-reach 3.1` + `leniency 0.03`，`vl-before-flag 2`。
判定方法已对齐 Grim，且多了"实时取消"这一道防线。

### 2.7 KillAura / Aim —— 有用但被严格门控（🟡，Grim 无此检测）

`KillAuraCheck` 把 9 个瞄准子项（AimModulo360/AimStep/GcdStable/GcdGrid/ConstStep/AxisAsym/BigRot/Angle/Switch）列入 `AIM_GATED_SUBS`：
- `shouldPunish()` 在 `aimstat` 启用且收集满 `MIN_SAMPLES` 样本、且 `signalCount("aim-stat")>=1` 且信号新鲜（<10s）时才真正 punish；否则只投启发式信号 `heur-*`（不封禁）；
- `aimstat`（默认关）用熵/IQR/KS/Jiff/Z-score/峰度 + 灵敏度区间 `[20,150]` 校准，只投交叉信号，绝不单独 punish；
- 其余子项（selfinteract/autoblock/noswing/post/multiinteract/cps/reach/throughwalls）与瞄准模式无关，保持直判避免假阴性。

→ 真人旋转偶发机械指纹的误判被交叉验证大量吸收；开启 aimstat 后进一步降低。

### 2.8 AutoClick —— 中等（🟡，Grim 无）

`FastClickCheck`：250ms 窗口 burst ≥6 + 5s cooldown + `FastClickLogic` 机械节奏（峰度/熵，`sampleCount>=40`）+ `max-ping 200` 限制 + `vl-before-flag 2`。真人 15~20cps 边缘有规律性与机械点击的统计区分度，但边缘玩家仍有误判风险。

### 2.9 Scaffold —— 行为层已默认关（🟢→🟡，原 🟡）

配置中 `cadence`/`colinear`/`grid45`/`duprot`/`footclick` **全部 `enabled: false`**（commit `6a61630`）。当前仅开启协议/旋转/放置层级：
- `fabricated`（cursor 越界）、`invalid-place`（非法面）、`fast-place`（放置频率）、`move-place`（移动放置速度）、`place-aim`（放置距离）、`rotation`（不看向放置方块，连续 8 次才 flag）。
这些抓"协议/几何上不可能"的放置，误判趋近零；行为节奏指纹（每 tick 完美节奏）默认不参与，熟练玩家不再被误杀。

### 2.10 Sprint —— 已从最差项升级为状态合规（🟢→🟡，原 🔴）

`SprintCheck` + `SprintLogic` 现在对齐 Grim 的 6 类禁止疾跑状态（1.8.8 无鞘翅）：饥饿/潜行/用物品/失明/头顶阻挡/水中。判定：
- `START_SPRINT && SprintLogic.isIllegalFlip(blockedStates)` → 直接 flag（服务端权威状态违规，等价于 Grim）；
- 翻转检测：`lastSprintAction != action && 间隔正常 && gap < max-flip-gap-ms(40) && 处于 blocked 状态` → 连续 3 次才 flag。

`blockedStates` 全部取自服务端权威 `player` 状态（`AsyncPacketListener.blockedStates`）：`getFoodLevel()<=6`、`isSneaking()`、`usingItem||isBlocking()`、`hasPotionEffect(BLINDNESS)`、`blockBoxedIn`（脚下+头顶实心）、`blockNearLiquid`（脚下/脚下方块为水/岩浆）。**误判来源已从"丢包/高 ping 下 START/STOP 包重排"转移为"状态快照是否准确"——而状态来自服务端权威，故残留极低。**

残留边缘（仍 🟢→🟡 而非 🟢）：
1. `data.usingItem` 仅在 `BLOCK_DIG status 5` 时复位——若客户端中途退出未发该包，`usingItem` 可能卡 true，导致"用物品状态下疾跑"误判（NoSlow 有 100ms 窗口守卫，但 Sprint 无，故此为 Sprint 唯一值得关注的残留 FP）；
2. `blockBoxedIn` 是简化启发（脚下+头顶实心且非史莱姆），与 1.8 原版"面前方块阻挡"语义略有出入，极少数 2 格高隧道场景可能多判。

---

## 3. 为什么 Grim 的误判低是"架构性"的（仍成立）

1. **碰撞盒模拟**：合法位移由世界唯一确定，不需要"猜阈值"——移动类误判差距的根源。
2. **事务时钟**：延迟测量基于事务往返而非 wall-clock/ping 估算。
3. **协议级校验**：Scaffold 等抓"协议上不可能"，不依赖行为统计。
4. **惩罚即纠错**：setback 自动拉回合法位置并重同步 shadow，误判后果被自愈。

**Grim 的低误判是"出厂自带"**，代价是实现复杂、难以按需放宽。

## 4. YCBR 的误判可控性设计（相对优势，已增强）

| 手段 | 说明（当前源码） |
|------|------|
| 独立开关 | simulation / aimstat / ml / scaffold 行为子项（cadence/colinear/grid45/duprot/footclick）高风险项默认关 |
| VL 缓冲 | bump/drain + `vl-before-flag`（Speed 8 / Fly 5~6 / NoSlow 4 / Timer 5 / Reach 2 / Sprint 2） |
| 交叉验证 | KillAura 9 个瞄准子项 × aimstat 统计双引擎同中才 punish |
| 灵敏度校准 | AimStatistics 用 SensitivityProcessor 区间 `[20,150]` 外不执行 |
| 事务化 | Timer/Blink/Velocity 已与 ping 解耦（TransactionTracker，EMA 0.7/0.3） |
| 实时降级 | Reach `shouldCancelAttack` 把误判后果从封禁降级为"该次攻击无效" |
| 状态合规 | Sprint 升级为 6 类禁止疾跑状态判定（对齐 Grim） |
| 经验兜底 | Speed/Fly/NoFall 经验公式保留（@Deprecated），引擎关闭时兜底 |

**一句话：Grim = 低误判出厂自带；YCBR = 低误判需要你逐个开关调出来，但给你完整控制权，且已补上状态合规/实时取消两道关键防线。**

---

## 5. 结论与建议

- **纯比误判少：Grim 仍胜**，主要在**移动类**（碰撞模拟 vs YCBR 经验回退）与**Scaffold 协议层**；
- **YCBR 已追平的项**：Timer（TPS 归一化）、Blink、Velocity、Reach（实时取消 + 多帧）、Sprint（状态合规）、Scaffold（行为层默认关）；
- **YCBR 有误判但可控的项**：KillAura/Aim（交叉验证门控）、AutoClick、Speed/Fly/NoFall（经验回退，引擎关）；
- **YCBR 当前最大弱项**：**移动类精度**——引擎 `simulation` 仍默认关，生产跑经验回退，误判高于 Grim 碰撞模拟。

**给 YCBR 的降误判优先级建议（更新版）：**

1. **P0 · 开启并验证 SimulationCheck**：这是补齐移动类差距、追平 Grim 的关键。按 `docs/plans/2026-08-14-simulation-tuning-sop.md` 先在低负载服观察，容差从 `0.01`（水平）/ `0.02`（垂直）起步，确认楼梯/液体/网/梯子豁免生效、误判日志为零后再调紧；**开启后与经验回退 Speed/Fly/NoFall 二选一或互补，避免双判叠加**。
2. **P1 · 修复 Sprint 的 usingItem 卡死残留**：在 `AsyncPacketListener` 给 `data.usingItem` 加超时复位（如 `now - lastItemUseTime > 1500ms` 自动置 false），彻底消除"客户端不发 dig status 5"导致的 Sprint 误判。
3. **P1 · Reach 保持实时取消**：维持 `cancel-impossible: true`，宁可漏不可杀；收集实机误判样本回灌 `DatasetManager` 训练集。
4. **P2 · Scaffold 行为层灰度**：`cadence`/`grid45` 先在测试服开启积累数据，确认无误杀再进生产；`colinear`/`duprot` 保持关。
5. **持续**：KillAura 保持交叉验证门控；`aimstat` 视服务器情况开启（默认关）以进一步降误判；`dataset` 录制用于 MLP 增强。

---

## 6. 自上一版（2026-08-14）文档的代码变更清单

| 提交 | 改动 | 对误判的影响 |
|------|------|--------------|
| `9ca4be6` | sim-speed 疾跑候选行不依赖 `m.sprinting` 标志 | 修"客户端先发移动包后发 START_SPRINTING"导致的 sim-speed 误判正常走路 |
| `e9aa4f1` | sim-fly 允许楼梯/半砖 step 高度 0.5 | 修走上半砖/楼梯 motY 达 ±0.5 被 sim-fly 误判 |
| `af154b1` | noslow 只认真使用物品（排除放方块） | 修放方块误置 usingItem 卡死 → 正常走路 NoSlow 误判 |
| `ff4e296` | Timer 按服务器 TPS 归一化间隔 | 修 19.2 TPS 下正常玩家系统性误判加速 |
| `d481903` | Reach 实时取消不可能攻击 | 误判后果从封禁降级为攻击无效 |
| `6a61630` | Scaffold 行为子项（cadence/colinear/grid45/duprot）默认关 | 熟练玩家行为节奏误杀归零 |
| `24acc9b` | 合并 phase04 缩差计划文档 | — |
| `03108d1` | fastclick/reach 配置归到 `checks.*` | 修死配置键 |
| `ce6108e` / `6dca39c` | 模拟实机调参 SOP / 误判样本反馈流程 | 运维流程 |

> 注：上一版文档建议的 P0（Sprint 升级为状态合规、放宽翻转窗口）、P0（Timer TPS 归一化）、P1（Scaffold 行为层默认关）、P1（Reach 实时取消）**均已在本轮代码迭代中落地**，故本版相应评级上调。剩余唯一 P0 为"开启并验证 SimulationCheck"。
