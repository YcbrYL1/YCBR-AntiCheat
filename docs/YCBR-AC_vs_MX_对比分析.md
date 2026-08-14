# YCBR-AC 与 MX 反作弊对比分析（v2，当前源码基线）

> 评价对象：YCBR-AC（`com.ycbr.anticheat`）与 MX（`kireiko.dev.anticheat`，作者 pawsashatoy / Kireiko Oleksandr）
> 核心问题：在两者**相同的检测项**上，谁更强、谁误判更少？
> 分析依据：两边源码实测（非文档宣传）。**本版为 v2**：YCBR 侧基线更新至 2026-08-14（含其后 10+ 提交），MX 侧源码未变（May 2026 基线）。

---

## 一、一句话结论

**MX 仍是"狙击手"——Aim 检测的统计+ML+RNN 深度业界罕见；但 YCBR 已不再是当年的纯启发式步枪：它吸收了同样的统计思路（熵/KS/IQR/峰度/Z-score/Jiff）并加上 SimpleMLP 与交叉验证门控，战斗类从"被 MX 完胜"追平为"同级对抗"。而在移动/协议/Reach/Scaffold/Criticals 上 YCBR 依旧碾压——MX 根本不检测这些。**

论"相同检测谁更强、误判少"（当前基线）：
- **Aim / KillAura：同级，各有胜负手**（MX 有 RNN 时序 + 灵敏度校准；YCBR 有 SimpleMLP + 交叉验证门控 + 覆盖更广的行为子项，且默认开启）
- **AutoClicker：YCBR 略优**（YCBR 已加峰度/熵并默认可用；MX 更学术但默认关闭、需 100 样本）
- **Velocity：YCBR 胜**（默认开启、覆盖广、SprintReset 高级指纹；MX 默认关闭、实验性）
- **Sprint：互补**（YCBR 查协议合规 6 类，MX 查 KA 行为指纹）
- **其余移动/协议/Reach/Scaffold/Criticals：YCBR 全胜**（MX 不检测）

---

## 二、架构与检测覆盖面

### 2.1 架构差异（v2 更新 YCBR 侧）

| 维度 | YCBR-AC（当前） | MX |
|------|---------|-----|
| 核心范式 | 阈值 + 行为统计 + **统计检验 + 轻量 ML**（手工经验公式 + 容差 + VL 缓冲） | 统计 + 机器学习（ProtocolLib 抓包 → 视角序列统计 → 经典 ML 模块 + RNN 投票） |
| 物理模拟 | 有（SimulationCheck 严格 1.8.8 参数化引擎，默认关） | 无（纯视角/行为分析） |
| 统计检验 | **熵/KS/IQR/峰度/Z-score/Jiff**（AimStatisticsCheck，默认关但已实现） | 熵/KS/IQR/Z-score/Jiff（同代技术） |
| ML | **SimpleMLP + DatasetManager**（默认关） | ClientML / Millennium 5（`AimMLCheck` 默认开启，7 经典模块 + 1 RNN） |
| 交叉验证 | **`shouldPunish` 多信号门控**（KillAura 9 子项需多信号同时异常才 flag） | 多统计维度同时异常才 flag |
| 灵敏度校准 | 无显式（GCD 对灵敏度天然不敏感） | 有（`SensitivityProcessor`，灵敏度 20–150 区间生效） |
| 网络/ping 处理 | **事务时钟**（TransactionTracker RTT + kbArrivalServerTick，对齐 Grim） | transactionLock 事务锁（仅 Velocity） |
| 惩罚方式 | VL 累加 → punish | VL 累加 + 攻击阻断（hitCancelTimeMS）渐进 |

### 2.2 检测覆盖面对比（关键差异）

**YCBR-AC 检测清单（当前源码）：**
- 战斗：Reach、KillAura（9 瞄准子项 + shouldPunish 门控）、AimStatistics（统计+ML）、FastClick、Criticals、Scaffold
- 移动：Speed、Fly、NoFall、Velocity（JumpReset/SprintReset）、NoSlow、SimulationCheck（引擎）
- 协议：Timer（tick 间隔版）、Sprint（6 类状态合规）、Blink（事务 pong）

**MX 检测清单（源码未变）：**
- 战斗/视角：`AimHeuristicCheck`(+7 启发式子组件)、`AimStatisticsCheck`、`AimAnalysisCheck`、`AimComplexCheck`、`AimMLCheck`、`AutoClickerCheck`、`SprintCheck`(实为 KA 指纹)
- 移动：`BaritoneCheck`(寻路工具视角指纹)、`GhostBlockAbuseCheck`(防利用 setback)
- 其他：`VelocityCheck`(默认关闭)

**MX 完全缺失的检测（Grep 确认无对应类）：**
Reach、独立 KillAura、Timer、Fly、Speed、NoFall、NoSlow、Blink、Scaffold、Criticals。

---

## 三、逐项深度对比

### 3.1 Aim / KillAura —— 同级对抗（v2 核心修正）

**旧基线结论：MX 完胜。v2 修正：同级，各有胜负手。**

**YCBR-AC（当前）：**
- `KillAuraCheck`：9 个瞄准子项 + **`shouldPunish` 交叉验证门控**（需多信号同时异常才 flag，降低单信号误判）
- `AimStatisticsCheck`：**熵、KS 检验、IQR、峰度、Z-score、Jiff 模式** + 交叉验证 + **SimpleMLP**（`ml-enabled` 默认关）——与 MX 同一代统计技术
- 默认开启路径：启发式子项即时生效；统计/ML 层默认关，作为进阶

**MX：**
- `AimHeuristicCheck`：7 启发式组件（Basic/Constant/Invalid/Inconsistent/Pattern/Factor/Smooth）
- `AimStatisticsCheck`：IQR、KS、Shannon 熵、Z-Score、Jiff
- `AimAnalysisCheck`：Z-score 离群、Jiff 去重率、长期 distinctRank + 灵敏度校准
- `AimComplexCheck`：GCD 量化 + 机械心跳 + 随机化缺陷 + 完美/相似熵
- `AimMLCheck`（默认开启）：7 经典 ML + 1 RNN 时序模型，UNUSUAL/STRANGE/SUSPECTED 三级投票

**谁更强：** 统计维度（熵/KS/IQR/Z/Jiff）YCBR 已全部对齐。剩余差距：MX 独有 **RNN 时序模型**与**灵敏度校准**；YCBR 独有 **SimpleMLP + 交叉验证门控**与**默认开启的实用路径**。综合同级——MX 学术深度略深（RNN + 灵敏度），YCBR 工程落地更稳（交叉验证 + 默认可用）。

**误判控制：**
- MX：多统计维度交叉 + 灵敏度校准 + 攻击阻断渐进惩罚。ML 依赖训练数据质量（`DatasetManager`）。
- YCBR：GCD 天然对灵敏度鲁棒 + `shouldPunish` 多信号门控 + VL 缓冲 + 事务时钟（网络层更稳）。SimpleMLP 同样依赖数据集（`DatasetManager` 已实现，默认关）。
- **结论：同级。** YCBR 的交叉验证门控在防单信号误判上比 MX 的多维度统计交叉更显式；MX 的灵敏度校准是 YCBR 缺失的稳健性手段（但 GCD 类检测天然免疫）。

### 3.2 AutoClicker —— YCBR 略优（v2 修正）

**YCBR-AC（`FastClickCheck`）：** cps 阈值 + burst + Interval（CV）+ **峰度/熵**（FastClickLogic 已加 kurtosis/entropy 维度），默认开启、即时生效。

**MX（`AutoClickerCheck`，默认关闭）：** 收集 100 个挥手包间隔，峰度 + Shannon 熵，Kurtosis<0 或熵极低且规律 → flag。

**对比：** 技术维度已对齐（都用了峰度/熵）。YCBR 默认开启 + 即时响应更实用；MX 默认关 + 需 100 样本。**v2 结论：YCBR 略优（同技术 + 默认可用）。**

### 3.3 Velocity —— YCBR 胜

**YCBR-AC（`VelocityCheck`）：** 水平取消/部分减少/反向、`HorizontalPrecise`、`Vertical` 百分比、`JumpReset`、`SprintReset`（KB 前停疾跑重置——高级 speed/fly 滥用指纹）、`kbArrivalServerTick`（事务 RTT 推算到达 tick）。默认开启。

**MX（`VelocityCheck`，默认关闭）：** 水平/垂直 + `JumpReset` + `transactionLock` 事务锁。实验性质。

**结论：YCBR 胜（默认开启 + SprintReset 指纹 + 事务到达推算）。**

### 3.4 Sprint —— 互补，不直接可比

**YCBR-AC（`SprintCheck`）：** 协议状态合规——饥饿/潜行/用物品/失明/头顶挡/水中 6 类禁止疾跑 + 翻转双条件 + `max-flip-gap-ms` 40ms + usingItem 超时复位（1500ms，已修卡死残留）。

**MX（`SprintCheck`）：** KA 行为指纹——攻击时 sprint 状态 <10ms 高频翻转（`zeros>buffer` → flag）。

**结论：互补。** 论传统 Sprint 协议合规 YCBR 更全；论 KA 行为指纹 MX 更准。YCBR 的 6 类 = 1.8.8 完整覆盖（无鞘翅）。

### 3.5 移动类（Speed / Fly / NoFall / NoSlow / Timer / Blink）—— YCBR 全胜

- **YCBR：** 全覆盖 + SimulationCheck 引擎（严格 1.8.8 参数化预测）+ Timer tick 间隔版 + Blink 事务 pong。
- **MX：** 完全缺失。仅 `BaritoneCheck`（机器式旋转视角）+ `GhostBlockAbuseCheck`（地面声称 vs 下方无方块 setback）——都不是通用移动检测。

**结论：YCBR 碾压。MX 在移动作弊上几乎是"盲"的。**

### 3.6 Reach / Scaffold / Criticals —— YCBR 胜

- **Reach：** YCBR 有 `ReachCheck`（多帧射线-AABB + 双方移动 allowance + `shouldCancelAttack` 实时取消）；MX 无
- **Scaffold：** YCBR 有 `ScaffoldCheck`（cursor 越界/旋转未对准/过远/非法面 + 行为层 Cadence/Colinear/Grid45 默认关）；MX 无
- **Criticals：** YCBR 有 `CriticalsCheck`；MX 无

**结论：YCBR 全胜（MX 不检测）。**

---

## 四、误判控制机制对比（v2 更新）

| 机制 | YCBR-AC（当前） | MX |
|------|---------|-----|
| VL / 缓冲 | 有（各 check 独立 VL + 衰减） | 有（多索引 buffer + fade） |
| 统计交叉验证 | **有（shouldPunish 多信号门控）** | 有（多统计维度同时异常） |
| 灵敏度校准 | 无显式（GCD 天然鲁棒） | 有（SensitivityProcessor） |
| 网络/ping 处理 | **事务时钟（RTT + 到达 tick 推算）** | transactionLock（仅 Velocity） |
| 惩罚强度 | punish | punish + 攻击阻断（hitCancelTimeMS）渐进 |
| ML 数据依赖 | 有（SimpleMLP/DatasetManager，默认关） | 有（ClientML 数据集） |
| 即装即用 | 默认开（战斗启发式 + 移动 + 协议） | 部分默认关（Velocity/AutoClicker） |

**总体：** 战斗类误判控制已同级（YCBR 交叉验证门控 vs MX 灵敏度校准）；网络层 YCBR 事务时钟更稳；移动类 YCBR 独有（MX 不检测）。

---

## 五、给 YCBR-AC 作者的建议（v2 更新）

1. **战斗类已追平，剩余差距两点**：① 引入 **RNN 时序模型**（MX AimMLCheck 的时序维度是 YCBR SimpleMLP 没有的，对"渐进式瞄准修正"类作弊更敏感）；② 引入 **灵敏度校准**（SensitivityProcessor 思路，非 GCD 类检测受益）。
2. **AutoClicker 的峰度/熵已对齐 MX，可考虑默认开启验证**（当前 kurtosis/entropy 维度已实现，建议实机观察误判后逐步放开）。
3. **Velocity 的 SprintReset + kbArrivalServerTick 是差异化护城河**（MX 默认关、无此指纹），继续打磨。
4. **MX 的移动盲区是 YCBR 的机会**：SimulationCheck 引擎（已实现默认关）+ Timer tick 版 + Blink 事务 pong 全面领先，应实机调参启用（见 `2026-08-14-simulation-config-template.md`）。

---

## 六、总体评价（v2 更新）

| 维度 | YCBR-AC | MX |
|------|---------|-----|
| 战斗/Aim 深度 | ★★★★（统计+ML+交叉验证） | ★★★★★（统计+ML+RNN+灵敏度校准） |
| 移动检测覆盖 | ★★★★★（引擎+经验双轨） | ★（仅特定工具） |
| 协议检测覆盖 | ★★★★（Timer/Sprint/Blink 事务化） | ★（仅 Sprint-KA） |
| 误判控制（战斗） | ★★★★（交叉验证门控） | ★★★★（灵敏度校准+交叉） |
| 误判控制（移动） | ★★★★ | N/A（不检测） |
| 即装即用性 | ★★★★（默认开） | ★★（Velocity/AutoClicker 默认关） |
| 数据依赖风险 | 有（ML 默认关，风险可控） | 有（ML 默认开，依赖数据集质量） |

**定位总结：**
- **MX = 专精狙击手**：Aim/KA 检测业界罕见（RNN + 灵敏度校准），但移动/协议盲区明显。适合作为"战斗专精"层。
- **YCBR-AC = 全面多面手**：combat+movement+protocol 全覆盖，战斗类已追平 MX 同级技术（统计+ML+交叉验证），移动/协议/Reach/Scaffold 碾压 MX。适合作为通用基座。

**相同检测谁更强、误判少（当前基线）：**
- Aim/KillAura：**同级**（MX RNN/灵敏度 vs YCBR 交叉验证/默认可用）
- AutoClicker：**YCBR 略优**（同技术 + 默认开启）
- Velocity：**YCBR 胜**
- Sprint：**互补**
- 移动/协议/Reach/Scaffold/Criticals：**YCBR 全胜**（MX 不检测）

**真正"最强"的组合：YCBR 全面基座 + MX 的 RNN/灵敏度校准思路（作为 YCBR 战斗类下一阶段升级方向）。**

---

## 附：v1 → v2 变更说明

| 项 | v1（旧基线） | v2（当前源码） |
|----|------|------|
| YCBR Aim/KA | 纯启发式，被 MX 完胜 | 统计+ML+交叉验证，同级对抗 |
| YCBR AutoClicker | cps+burst+CV | 已加峰度/熵，略优 MX |
| YCBR Timer | EPS 频率 | tick 间隔版（TimerLogic） |
| YCBR Blink | 沉默时长 | 事务 pong（livePong） |
| YCBR Velocity | 无到达推算 | kbArrivalServerTick（事务 RTT） |
| YCBR Sprint | 未提 | 6 类状态合规 + 超时复位 |
| YCBR 移动 | 经验公式 | + SimulationCheck 引擎（默认关） |
| YCBR Reach | 事后 flag | 多帧射线-AABB + shouldCancelAttack |
