# YCBR-AC 与 MX 反作弊对比分析

> 评价对象：YCBR-AC（`com.ycbr.anticheat`）与 MX（`kireiko.dev.anticheat`，作者 pawsashatoy / Kireiko Oleksandr）
> 核心问题：在两者**相同的检测项**上，谁更强、谁误判更少？
> 分析依据：两边源码实测（非文档宣传）

---

## 一、一句话结论

**MX 是“狙击手”——Aim / KillAura / AutoClick 检测做到了统计+ML+RNN 的罕见深度，远超 YCBR 的纯启发式实现；但它在移动类（Speed/Fly/NoFall/NoSlow/Timer/Blink）和 Reach/Scaffold/Criticals 上几乎是空白，完全不检测。YCBR 是“步枪”——覆盖 combat+movement+protocol 三大类 ~13 个检测，移动与协议类远强于 MX，但战斗类的深度不如 MX。**

论“相同检测谁更强、误判少”：
- **Aim / KillAura：MX 完胜**（统计+ML+RNN 多引擎，且误判控制更严谨）
- **AutoClicker：基本持平**（MX 更学术但默认关闭；YCBR 更实用即时）
- **Velocity：YCBR 胜**（默认开启、覆盖广、有 SprintReset 高级指纹；MX 默认关闭、实验性）
- **Sprint：互补**（YCBR 查协议合规，MX 查 KA 行为指纹）
- **其余移动/协议/Reach/Scaffold/Criticals：YCBR 全胜**（MX 不检测）

---

## 二、架构与检测覆盖面

### 2.1 架构差异

| 维度 | YCBR-AC | MX |
|------|---------|-----|
| 核心范式 | 阈值 / 行为统计（手工经验公式 + 容差 + VL 缓冲） | 统计 + 机器学习（ProtocolLib 抓包 → 视角序列统计分析 → 经典 ML 模块 + RNN 投票） |
| 物理模拟 | 无（移动类靠经验公式拟合） | 无（纯视角/行为分析，无 Grim 式物理重演） |
| ML/RNN | 无 | 有（`ClientML` / Kireiko Millennium 5，`AimMLCheck` 默认开启，7 经典模块 + 1 RNN） |
| 训练数据 | 不依赖 | 依赖（`DatasetManager` + `RECORDING` 模式 + `/mx train` 收集样本） |
| 灵敏度校准 | 无显式（GCD 对灵敏度天然不敏感） | 有（`SensitivityProcessor.calculateSensitivity()`，灵敏度 20–150 区间才生效，避免误判） |
| 惩罚方式 | VL 累加 → punish | VL 累加 + `setAttackBlockToTime`（攻击阻断）而非直接 ban，多信号交叉 |

### 2.2 检测覆盖面对比（关键差异）

**YCBR-AC 检测清单（上次分析已读源码）：**
- 战斗：Reach、KillAura(16 子检测)、FastClick、Criticals、Scaffold
- 移动：Speed、Fly、NoFall、Velocity、NoSlow
- 协议：Timer、Sprint、Blink

**MX 检测清单（本次 Grep 实测 `class *Check`）：**
- 战斗/视角：`AimHeuristicCheck`(+7 启发式子组件)、`AimStatisticsCheck`、`AimAnalysisCheck`、`AimComplexCheck`、`AimMLCheck`、`AutoClickerCheck`、`SprintCheck`(实为 KA 指纹)
- 移动：`BaritoneCheck`(寻路工具视角指纹)、`GhostBlockAbuseCheck`(防利用 setback)
- 其他：`VelocityCheck`(默认关闭)

**MX 完全缺失的检测（Grep 确认无对应类）：**
Reach、独立 KillAura、Timer、Fly、Speed、NoFall、NoSlow、Blink、Scaffold、Criticals。

> MX 的“移动检测”只有两个特定场景工具指纹（Baritone 自动寻路、GhostBlock 幽灵方块），**不是通用 Speed/Fly 检测**。这意味着：在 MX 上跑 Fly/Speed/NoFall/Timer 不会被抓到。

---

## 三、逐项深度对比

### 3.1 Aim / KillAura —— MX 完胜

**YCBR-AC（`KillAuraCheck`）：** 16 个子检测的行为指纹体系
- `GcdStable`/`GcdGrid`（最大公约数网格）、`ConstStep`（常数步长）、`AxisAsym`（轴不对称）、`AimStep`/`Modulo360`/`BigRot`（旋转步进/模360/大旋转）、`Switch`/`MultiTarget`（切换/多目标）、`NoSwing`（无挥手）、`Interval`(CV 变异系数)、`Post` 等
- 本质是“工程师级”启发式，靠行为指纹刻画 KA

**MX（Aim 体系，4 主检测 × 多子模块）：**
- `AimHeuristicCheck`：组合 7 个启发式组件（Basic/Constant/Invalid/Inconsistent/Pattern/Factor/Smooth），攻击后 3.5s 内监听每次视角变化
- `AimStatisticsCheck`：**IQR 四分位距、Kolmogorov-Smirnov 检验、Shannon 熵、Z-Score 离群值、Jiff 重复模式** 五种统计手段
- `AimAnalysisCheck`：Z-score 离群、Jiff 去重率、长期分析（distinctRank），带灵敏度校准
- `AimComplexCheck`：GCD 量化 + **机械心跳(Machine Heart) + 随机化缺陷(Randomizer flaw) + 完美/相似熵**
- `AimMLCheck`（**默认开启**）：7 个经典 ML 模块（`M1`–`M5`、`MHuge1/2`）+ 1 个 RNN 时序模型（`RNN1Module`），威胁分 `UNUSUAL/STRANGE/SUSPECTED` 三级投票

**谁更强：** MX 明显更强。YCBR 的 GCD/方差/行为指纹 MX 都有对应（且更细），而 MX 独有的 **熵分析、KS 检验、ML/RNN、机械心跳、随机化检测、灵敏度校准** 是 YCBR 完全没有的维度。

**误判控制：**
- MX 用**多信号交叉验证**（需多个统计维度同时异常才 flag）、**灵敏度校准**（不同鼠标 DPI/灵敏度不误杀）、**攻击阻断**(hitCancelTimeMS) 而非直接封禁、VL 缓冲衰减
- YCBR 用 GCD（对灵敏度天然鲁棒）+ CV + VL
- 两者都做了误判控制，但 MX 的统计交叉 + 灵敏度校准更严谨
- **MX 的 ML 风险**：依赖训练数据质量（`DatasetManager`），数据集偏差会导致误判；其 `TEST_MODE=false` 且需 `RECORDING` 收集样本，是数据驱动型

**结论：MX 在 Aim/KA 上远超 YCBR，且误判控制更先进（但 ML 有数据集依赖风险）。**

### 3.2 AutoClicker —— 基本持平，YCBR 更实用

**YCBR-AC（`FastClickCheck`）：** `cps` 阈值 + `burst`(突发) + `Interval`(CV 变异系数)，即时生效

**MX（`AutoClickerCheck`，默认关闭）：** 收集 100 个挥手包间隔，计算 **峰度(Kurtosis)** 与 **香农熵(Shannon Entropy)** 刻画机械规律性（Kurtosis<0 或 熵极低且规律 → flag）

**对比：** MX 的熵/峰度更“学术”、对机械规律更敏感；但默认关闭、需 100 样本（响应慢）。YCBR 的 cps+burst+CV 组合更即时、默认可用。两者误判都低（真人随机点击不会触发）。

**结论：基本持平。YCBR 默认可用更实用，MX 统计更稳健但需开启。**

### 3.3 Velocity —— YCBR 胜

**YCBR-AC（`VelocityCheck`）：** 完整细分——水平取消/部分减少/反向、`HorizontalPrecise` 精确比值、`Vertical` 百分比、`JumpReset`(KB 瞬间跳重置)、`SprintReset`(KB 前停疾跑重置)。`SprintReset` 是高级 speed/fly 滥用指纹，覆盖全面且默认开启。

**MX（`VelocityCheck`，`enabled: false`）：** 水平/垂直 + `JumpReset`([0.248136, 0.3332]) + `transactionLock`(事务锁防误判)。撞墙忽略列表、容差 0.005。代码显式 `enabled: false`，且 `flag()` 注释暗示实验性质。

**对比：** YCBR 覆盖更广、默认开启、有 SprintReset 高级指纹；MX 思路相似（也有 JumpReset + 事务锁）但**默认关闭、像半成品**，作者自己都未默认启用。

**结论：YCBR 胜（Velocity 是 YCBR 的强项）。**

### 3.4 Sprint —— 互补，不直接可比

**YCBR-AC（`SprintCheck`）：** 协议状态校验（饥饿/蹲伏/用物品/失明/撞墙/鞘翅/水中共跑等 sprint 非法）

**MX（`SprintCheck`）：** 实际是 **KillAura 行为指纹**——注释明示 `Goofy KillAura Zero's flaw`，检测攻击时 sprint 状态 <10ms 高频翻转（`zeros>buffer` 则 flag）

**对比：** 角度完全不同。YCBR 查“sprint 协议是否违规”，MX 查“用 sprint 翻转折射 KA”。两者互补，不冲突。MX 这个检测性能很好，但本质是 KA 检测而非传统 Sprint 检测。

**结论：互补。论传统 Sprint 协议合规 YCBR 更全；论 KA 行为指纹 MX 更准。**

### 3.5 移动类（Speed / Fly / NoFall / NoSlow / Timer / Blink）—— YCBR 全胜

- **YCBR：** 全覆盖（Speed/Fly/NoFall/NoSlow 经验公式+容差，Timer EPS 频率，Blink 沉默时长）
- **MX：** 完全缺失（Grep 无对应类）。仅 `BaritoneCheck`(检测自动寻路 mod 的机器式旋转视角)、`GhostBlockAbuseCheck`(玩家声称在地面但实际下方无方块时 setback 修正)——都不是通用移动检测

**结论：YCBR 碾压。MX 在移动作弊上几乎是“盲”的。**

### 3.6 Reach / Scaffold / Criticals —— YCBR 胜

- **Reach：** YCBR 有 `ReachCheck`(上一帧位置+两档眼高+外推)；MX 无
- **Scaffold：** YCBR 有 `ScaffoldCheck`(cursor 越界/旋转未对准/过远/非法面 + 行为级 Cadence/Colinear/Grid45)；MX 无
- **Criticals：** YCBR 有 `CriticalsCheck`；MX 无

**结论：YCBR 全胜（MX 不检测）。**

---

## 四、误判控制机制对比

| 机制 | YCBR-AC | MX |
|------|---------|-----|
| VL / 缓冲 | 有（各 check 独立 VL + 衰减） | 有（多索引 buffer 数组 + fade 衰减） |
| 灵敏度校准 | 无（依赖 GCD 鲁棒性） | 有（SensitivityProcessor，20–150 区间生效） |
| 多信号交叉 | 行为指纹组合 | 统计多维度同时异常才 flag |
| 惩罚强度 | 直接 punish | punish + 攻击阻断(hitCancelTimeMS) 渐进 |
| 网络/ping 处理 | pingTicks 估算（高 ping 偏） | transactionLock 事务锁（Velocity） |
| 电影模式/特殊场景 | 部分 | 有（ignoreCinematic / ignoreFirstTick） |

**总体：** MX 在战斗类的误判控制更严谨（灵敏度校准 + 多信号交叉 + 渐进惩罚），但 ML 模块依赖数据集质量；YCBR 在移动类的经验容差 + VL 是工程稳妥方案，但高 ping 下有估算偏差。

---

## 五、给 YCBR-AC 作者的建议

1. **战斗类是最大短板，应借鉴 MX 的“统计+ML”思路**：YCBR 的 KillAura 是纯启发式（GCD/方差/行为指纹），而 MX 用 IQR/KS/熵/Z-score + RNN 做到了更高维度。若要补强 Aim/KA，应引入统计检验（尤其 Shannon 熵、KS 检验、Jiff 模式）和轻量 ML/RNN，而非再加启发式子检测。

2. **引入灵敏度校准**：MX 用 `calculateSensitivity()` 避免不同鼠标灵敏度误判，这是 YCBR 缺失的稳健性手段（虽 GCD 天然鲁棒，但非 GCD 类检测会受影响）。

3. **Velocity 的细分（JumpReset/SprintReset）值得保留并强化**：这是 YCBR 相对 MX 的差异化优势（MX 的 Velocity 默认关闭）。继续打磨。

4. **AutoClicker 可加熵/峰度维度**：MX 的 Kurtosis+Shannon 思路更稳健，YCBR 的 cps+burst+CV 可补充熵分析以提升对高级点击器的识别。

5. **MX 的移动盲区是 YCBR 的机会**：若要在市场上差异化，YCBR 应继续强化 Speed/Fly/NoFall/Timer/Blink/Reach/Scaffold 的全面移动+协议覆盖——这是 MX 短期补不上的。

---

## 六、总体评价

| 维度 | YCBR-AC | MX |
|------|---------|-----|
| 战斗/Aim 深度 | ★★☆（启发式） | ★★★★★（统计+ML+RNN） |
| 移动检测覆盖 | ★★★★★（全面） | ★（仅特定工具） |
| 协议检测覆盖 | ★★★★（Timer/Sprint/Blink） | ★（仅 Sprint-KA） |
| 误判控制（战斗） | ★★★ | ★★★★（灵敏度校准+交叉） |
| 误判控制（移动） | ★★★★ | N/A（不检测） |
| 即装即用性 | ★★★★（默认开） | ★★（Velocity/AutoClick 默认关） |
| 数据依赖风险 | 无 | 有（ML 数据集偏差） |

**定位总结：**
- **MX = 专精狙击手**：Aim/KA/AutoClick 检测水平业界罕见，但移动/协议是盲区。适合作为“战斗专精”层叠加在通用反作弊之上。
- **YCBR-AC = 全面步枪**：combat+movement+protocol 全覆盖，移动与协议稳，但战斗深度不及 MX。适合作为通用基座。

**相同检测谁更强、误判少：**
- Aim/KillAura：**MX 完胜**
- AutoClicker：**持平**（YCBR 更实用）
- Velocity：**YCBR 胜**
- Sprint：**互补**
- 移动/协议/Reach/Scaffold/Criticals：**YCBR 全胜**（MX 不检测）

两者互补性极强——真正“最强”的方案是 YCBR 的全面基座 + MX 的 Aim/KA 专精层。
