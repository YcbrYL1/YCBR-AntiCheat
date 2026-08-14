# YCBR-AC 与 7 款反作弊同项检测横向对比

> 分析对象（8 款，全部源码实测）：
> - **YCBR-AC**（`com.ycbr.anticheat`，自研，阈值/行为统计 + 预测引擎雏形）
> - **Grim 2.0**（`ac.grim.grimac`，预测型，物理重演 + 事务时间轴）
> - **MX**（`kireiko.dev.anticheat`，统计 + ML/RNN 专精战斗）
> - **NCP**（`fr.neatmonster.nocheatplus`，Magic 物理包络 + 桶统计）
> - **ACA**（`de.photon.anticheataddition`，行为指纹 + 批处理统计）
> - **ACR**（`com.rammelkast.anticheatreloaded`，传统移动物理 + 规则引擎）
> - **Matrix**（基岩版 Bedrock 脚本反作弊，游戏 API 推断）
> - **TaKa AC**（`bg.dani02.taka.anticheat`，Java 插件，物理模型对比）
>
> 分析日期：2026-08-13
> 核心问题：在**相同的检测项**上，哪个更强大、误判更少？

---

## 1. 一句话结论

- **移动类（Speed/Fly/NoFall/NoSlow/Timer/Sprint）**：Grim 全面最强且误判最少（物理重演 + 事务时间轴）；NCP 次之（Magic 包络 + 延迟补偿，稳健老牌）。
- **战斗行为类（KillAura/Aim/AutoClick）**：MX 深度最强（统计 + ML/RNN），但依赖数据集；YCBR 的 16 子启发式是纯工程实现里最强的；ACA 的旋转指纹与批处理统计是另一条高水准路线。
- **Reach**：Grim 最强（射线求交 + 多帧 + 实时取消）；NCP 的 reachMod 动态收缩是误判控制亮点。
- **Scaffold**：ACA 最强（批处理平均延迟 vs 物理下限）；Grim 协议级误判最少。
- **Velocity**：YCBR 检测面最细（JumpReset/SprintReset 独有指纹）；Grim 误判控制最稳（transaction 三明治）。
- **Blink/协议**：Grim 最强（TransactionOrder/PacketOrder）；NCP 的 TeleportQueue ACK 状态机次之。
- **整体误判率**：Grim < NCP < ACA < YCBR < ACR < TaKa < Matrix < MX（ML 数据依赖风险）。

---

## 2. 各反作弊定位与架构总览

| 反作弊 | 平台 | 核心范式 | 强项 | 盲区 |
|--------|------|----------|------|------|
| **Grim 2.0** | Java (Bukkit/Fabric) | 物理重演 + 事务时间轴 + 协议校验（~150 检测） | 全部移动类、Reach、协议 | KillAura/AutoClick 无专门检测 |
| **MX** | Java (ProtocolLib) | 统计检验 + ML/RNN（Kireiko Millennium 5） | Aim/KA/AutoClick 深度 | 移动/协议几乎全盲 |
| **NCP** | Java (ProtocolLib) | Magic 物理包络 + 桶统计 + VL 衰减 | 移动包络、Reach 自适应、Velocity 记账 | 无独立 NoSlow/Sprint |
| **ACA** | Java (PacketEvents) | 行为指纹 + 批处理统计（KS/方差/平均） | Scaffold、背包、旋转协议、AutoTool | 无移动物理检测 |
| **ACR** | Java (ProtocolLib) | 单 tick 阈值 + vlBeforeFlag/buffer + 规则引擎 | 移动物理、Reach、Timer 包余额 | 战斗深度一般 |
| **Matrix** | 基岩版 (TS 脚本) | 游戏 API 速度/位置/旋转推断 + 统计特征 | 基岩版专用、传送回滚 | 无数据包级访问、误判面大 |
| **TaKa AC** | Java (Bukkit) | 物理模型对比 + 豁免清单 | 方块级豁免详尽、tpBack 回滚 | 战斗检测弱、WallHit 未完成 |
| **YCBR-AC** | Java (ProtocolLib) | 阈值/行为统计 + 预测引擎雏形 | Velocity 细分、KillAura 启发式、Scaffold 行为 | Timer/Blink 仍 wall-clock |

---

## 3. 同项检测对比矩阵（核心）

图例：🟩 = 该检测项最强/误判最少　🟧 = 次强/误判较少　⬜ = 中等　⬜⬜ = 弱/缺失
（左列"检测能力"，右列"误判控制"）

| 检测项 | Grim | NCP | MX | ACA | ACR | Matrix | TaKa | YCBR |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Speed** 能力 | 🟩 | 🟧 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **Speed** 误判 | 🟩 | 🟩 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **Fly** 能力 | 🟩 | 🟧 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **Fly** 误判 | 🟩 | 🟩 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **NoFall** 能力 | 🟩 | 🟧 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **NoFall** 误判 | 🟩 | 🟩 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **NoSlow** 能力 | 🟩 | 🟧(并入速度) | ✗ | ✗ | 🟧 | ⬜ | ✗ | ⬜ |
| **Timer** 能力 | 🟩 | 🟧 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜⬜ |
| **Timer** 误判 | 🟩 | 🟩 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜⬜ |
| **Sprint** 能力 | 🟩 | 🟧(sprintback) | 🟧(KA指纹) | ✗ | ✗ | 🟧 | ✗ | ⬜ |
| **Reach** 能力 | 🟩 | 🟧 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **Reach** 误判 | 🟩 | 🟩 | ✗ | ✗ | 🟧 | ⬜ | 🟧 | ⬜ |
| **KillAura/Aim** 能力 | ⬜ | 🟧 | 🟩 | 🟧 | 🟧 | 🟧 | ⬜⬜ | 🟧 |
| **KillAura/Aim** 误判 | ⬜ | 🟧 | 🟩(交叉) | 🟧 | 🟧 | ⬜ | ⬜ | 🟧 |
| **AutoClick** 能力 | ✗ | 🟧 | 🟩 | 🟧 | ✗ | 🟧 | 🟧 | 🟧 |
| **Scaffold** 能力 | 🟧 | 🟧 | ✗ | 🟩 | ✗ | 🟧 | 🟧 | 🟧 |
| **Scaffold** 误判 | 🟩 | 🟧 | ✗ | 🟧 | ✗ | ⬜ | 🟧 | 🟧 |
| **Velocity** 能力 | 🟧 | 🟧 | ⬜(默认关) | ✗ | 🟧 | ✗ | ✗ | 🟩 |
| **Velocity** 误判 | 🟩 | 🟧 | ⬜ | ✗ | 🟧 | ✗ | ✗ | 🟧 |
| **Blink/协议** 能力 | 🟩 | 🟧 | ✗ | ✗ | ✗ | 🟧(phase) | ✗ | ⬜⬜ |
| **Criticals** 能力 | ⬜(并入模拟) | 🟧 | ✗ | ✗ | 🟧 | ✗ | 🟧 | 🟧 |

> ✗ = 无此检测。各格结论依据见第 4 节逐项分析。

---

## 4. 逐项深度分析

### 4.1 Speed（移动速度）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **Grim** 🟩 | 物理重演 | 穷举所有合法输入组合（sprint 1.3×、跳跃、介质摩擦、riptide、烟花鞘翅、史莱姆弹射），无法解释的位移 → offset，阈值 **0.001** | 容差盒 + UncertaintyHandler + transaction 三明治判定到达性 |
| **NCP** 🟧 | Magic 包络 | `SurvivalFly.setAllowedhDist()` 按状态（sneak/swim/sprint/ice/药水/附魔）算每 tick 允许位移上限；hacc 统计（30 次平均 fcmh > 1.34）；bunnyHop 连跳校验 | `sfHorizontalBuffer` 水平缓冲先消耗再计 VL；`MagicAir.oddJunction` 大量合法异常豁免；`TickTask.getLag()` 延迟补偿；VL ×0.95 衰减 |
| **ACR** 🟧 | 摩擦模型 | Predict（0.16277136/friction³ 公式）+ AirSpeed（0.36×0.985^airTicks + 十余项补偿）+ AirAcceleration + GroundSpeed | buffer > 2.5 才 flag，正常 -0.05，flag 后 /2 |
| **TaKa** 🟧 | 阈值表 | `dist=(dX²+dZ²)/0.1` 按状态查表（地面冲刺 0.79/步行 0.468/冰/史莱姆/水/蛛网/药水）；SpeedAir 按 tick 查表 | `legitSpeedVL` 合法加速原因加权；tpBack 回滚而非踢出 |
| **Matrix** ⬜ | API 推断 | 速度突变（ΔXZ > 0.7，8s 内 >20 次）+ Timer 型（位移 vs 速度推算） | 极长豁免列表（飞行/滑翔/击退 1500ms/激流 5000ms/药水>3） |
| **YCBR** ⬜ | 经验公式 + 引擎雏形 | `ground.limit=0.29` + 加成；`air.momentum=0.36×0.985^airTicks`；SimulationCheck 模长匹配（默认关） | 魔法数容差 + 豁免列举；SimulationCheck 容差 0.01/0.02 |

**结论**：Grim 最强（穷举合法输入，无手工列举遗漏）。NCP 的包络 + 缓冲是"老牌稳健"代表。YCBR 的 SimulationCheck 已具备 Grim 雏形但默认关闭、只做模长匹配、无 strafe 维度、液体/网/梯子容差放大 2 倍——需补方向匹配 + 候选扩展 + 实机验证后开启。

### 4.2 Fly（飞行）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **Grim** 🟩 | 协议 + 重演 | FlightA（无权限发 flying 包直接 flag）+ Simulation 逐 tick offset |
| **NCP** 🟧 | 包络 | SurvivalFly 垂直 `vDistAir()` 重力包络（GRAVITY 0.0624~0.0834）+ 摩擦包络；CreativeFly 含鞘翅/烟花豁免 |
| **ACR** 🟧 | Y 轴模型 | AirFlight（airTicks>13 后 motionY>max）+ AirClimb（5 变体）+ GroundFlight（伪造地面）+ Gravity（重力模拟连续计数） |
| **TaKa** 🟧 | 6 检测 | FlyStableY（悬停非重力值）/FlyModulo（Y%1==0 塔楼）/FlyInvalidY（上升但速度向下 + 合法下落白名单）/FlySlowY/FlyDoubleJumpUP/Down |
| **Matrix** ⬜ | API 推断 | velocityY>0.7 计数 + BDS 预测（60 样本）+ 无鞘翅滑翔 |
| **YCBR** ⬜ | 统计 | Rise（上升超重力预期）+ Level（悬停 >8 ticks） |

**结论**：Grim 最强。NCP 的重力包络（上下限区间）比 YCBR 的"悬停 8 ticks"更精确。YCBR 的 Fly 对"瞬停瞬飞"型 bypass 覆盖弱。

### 4.3 NoFall（摔落伤害）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **Grim** 🟩 | 协议 + 预测 | 协议层 NoFall（无位置更新却声明 onGround）+ 预测层 GroundSpoof（引擎算真实 onGround 对比声明），可无声改写包 |
| **NCP** 🟧 | 状态跟踪 | 自维护摔落距离，落地按真实距离补发伤害（`dealFallDamage`），让"取消摔伤"失效；antiCriticals 小跳豁免 |
| **ACR** 🟧 | 状态对比 | 下落中 `getFallDistance()==0` 连续计数达 vlBeforeFlag |
| **TaKa** 🟧 | 期望伤害 | serverFallDistance>=4 时等 6 tick，800ms 内无 FALL 伤害则手动补伤 |
| **YCBR** ⬜ | 启发式 | `airTicks>8 && motionY<-0.4` 且脚下无方块；落地无伤害 |

**结论**：Grim 最强（预测引擎的 onGround 真相 vs 声明）。NCP 的"补伤害"思路（不检测作弊而是让作弊无效）是 YCBR 可借鉴的差异化方案。

### 4.4 NoSlow（减速）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **Grim** 🟩 | 预测候选 | "使用物品 ×0.6"建模为预测候选分支，offset 落到 NoSlow（阈值 0.001） |
| **NCP** 🟧 | 并入速度 | 无独立检测，减速修正（modSneak/modBlock/modSwim）纳入 SurvivalFly 速度包络 |
| **ACR** 🟧 | 释放间隔 | 两次物品释放间隔 < 最小 + 移动距离门槛 |
| **Matrix** ⬜ | 部分 | invalidSprint 覆盖用物品冲刺 |
| **YCBR** ⬜ | 经验 | `expected=lastXZ*0.92+0.01` + 手工豁免列举 |

**结论**：Grim 最强（建模进预测候选，无豁免遗漏）。YCBR 手工列举豁免易漏新减速源。

### 4.5 Timer（变速）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **Grim** 🟩 | 事务时钟 | 每移动包 +50ms 余额，超 `System.nanoTime()` 才 flag；绑定玩家自身 ping/事务往返 | ping 波动永不误判，低 ping 抓 1.01× |
| **NCP** 🟧 | 桶统计 | FlyingFrequency（桶分数/秒 > PPS）+ MorePackets（EPS 双桶） | `TickTask.getLag()` 除以延迟系数，服务器卡顿自动放宽 |
| **ACR** 🟧 | 包余额 | packetBalance += 50 - rate，超 triggerBalance flag，惩罚性重置 | TPS/最大 ping/isLagging 豁免 |
| **TaKa** 🟧 | 包间隔 | 50 队列平均间隔，<42ms 或 >77ms 判违规 | TPS<=17 整体关闭 |
| **Matrix** ⬜ | 位移推算 | 实际位移 vs 速度推算位移 | 多级 flag |
| **YCBR** ⬜⬜ | wall-clock EPS | 6s/2s/500ms 窗口 EPS > 22/24/22 | 网络抖动/丢包制造假 EPS 波动，高 ping 不鲁棒 |

**结论**：Grim 最强且误判最少。**YCBR 的 Timer 是最大误判源**——`TransactionTracker` 已实现但零调用，TimerCheck 仍用 `System.currentTimeMillis()` 窗口。

### 4.6 Sprint（疾跑）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **Grim** 🟩 | 7 细分 | SprintA 饥饿/SprintB 蹲伏/SprintC 用物品/SprintD 失明/SprintE 撞墙/SprintF 鞘翅/SprintG 水中 |
| **NCP** 🟧 | sprintback | 疾跑中倒退移动判违规；sprintingGrace 宽限 |
| **Matrix** 🟧 | invalidSprint | 用弓/食物/药水/失明/饥饿冲刺 |
| **MX** 🟧 | KA 指纹 | 攻击时 sprint 状态 <10ms 高频翻转（Goofy KillAura Zero's flaw） |
| **YCBR** ⬜ | flip spam | 仅抓 <20ms 内 sprint flip >=3 次 |

**结论**：Grim 碾压（7 细分）。YCBR 仅抓 flip spam，对"非法保持疾跑"无能为力。

### 4.7 Reach（攻击距离）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **Grim** 🟩 | 射线求交 | 精确射线追踪 + 实体所有可能碰撞盒（插值）+ 枚举多帧视角 + 攻击范围属性，阈值 0.0005 | cancelBuffer 实时取消不可能攻击 |
| **NCP** 🟧 | 几何 + 自适应 | 眼睛到目标中心距离，SURVIVAL_DISTANCE=4.4 + 实体修正 | **reachMod 动态收缩**（临界距离攻击时逐步收紧允许距离）；`TickTask.getLag()<1.5` 才计 VL |
| **ACR** 🟧 | 动态上限 | allowedReach = base + 创造 1.5 + ping 补偿 + 目标 ping/速度补偿 | 双向 ping + 速度补偿 + 四舍五入（宽松上限） |
| **TaKa** 🟧 | XZ 距离 | 阈值 3.5/创造 4.6 | `getLaggReachDisstance` ping/TPS 补偿 + 冲刺 +0.6 |
| **Matrix** ⬜ | 线段距离 | killaura Type B 记录 20 样本 lineDistance，阈值 3.6/4.6 | isSafeDevice 豁免 |
| **YCBR** ⬜ | 单帧 + 眼高 | 上一帧位置 + 两档眼高（1.62/1.54）+ ping 外推 | ThroughWalls 体素采样是加分项 |

**结论**：Grim 最强。NCP 的 reachMod 动态收缩是独特误判控制手段。YCBR 的 ThroughWalls 是差异化加分项，但主判定精度落后。

### 4.8 KillAura / Aim（自瞄）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **MX** 🟩 | 统计 + ML/RNN | AimHeuristic(7 组件) + AimStatistics(IQR/KS/Shannon 熵/Z-score/Jiff) + AimAnalysis + AimComplex(GCD/机械心跳/随机化缺陷) + AimML(7 经典 ML + 1 RNN 投票) | 多信号交叉 + 灵敏度校准(20-150) + 攻击阻断渐进 |
| **ACA** 🟧 | 旋转指纹 | AimStep（一轴不动一轴大转）+ PerfectRotation（0.1/0.25 整数倍）+ EqualRotation（完全相同）+ IllegalPitch + Animation（攻击后必须挥臂） | ViolationCounter 条件 +1/-1 达阈值；8 类时间窗豁免 |
| **NCP** 🟧 | 多特征加权 | Angle（攻击时几乎不动/间隔过短/转身过大/频繁切目标加权评分）+ Direction（视线偏移 >0.1） | `TickTask.getLag()<1.5` 才计 VL；复杂实体跳过 |
| **ACR** 🟧 | 角度/GCD | Angle（视线差）+ Variance（deltaPitch 方差<0.25）+ RepeatedAim + ThroughWalls | vlBeforeFlag 计数 |
| **Matrix** 🟧 | 统计特征 | aimAssist 4 型（平滑转向/整数化）+ killaura 12 型（多目标/命中箱/幽灵手/整数旋转/无挥砍） | 计数 + 衰减 + 软惩罚(weakness) |
| **YCBR** 🟧 | 16 子启发式 | GcdStable/GcdGrid/ConstStep/AxisAsym/AimStep/Modulo360/BigRot/Switch/MultiTarget/Interval(CV)/NoSwing/MultiInteract/SelfInteract/AutoBlock/Post/InventoryCombo | GCD 天然对灵敏度鲁棒 + VL 缓冲 |
| **Grim** ⬜ | 极简 | 仅 AimDuplicateLook/AimModulo360/AimProcessor（GCD 灵敏度提取） | — |
| **TaKa** ⬜⬜ | 未完成 | WallHit 射线扫描，代码标注 "DONT ACCURATE !!!"，未真正触发 | — |

**结论**：MX 深度最强（统计 + ML/RNN），但依赖数据集质量。**纯工程启发式里 YCBR 最强**（16 子检测成体系）。ACA 的旋转指纹（PerfectRotation/EqualRotation）是另一条高水准路线，且其 Jitter 豁免思路值得注意。

### 4.9 AutoClick（自动点击）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **MX** 🟩 | 峰度 + 熵 | 100 样本间隔 Kurtosis<0 或 Shannon 熵极低（默认关） |
| **NCP** 🟧 | 桶统计 | fight/Speed（中期桶 + 短期窗口取最大）+ AttackFrequency（0.5/1/2/4/8s 五档） |
| **ACA** 🟧 | 统计 | InventoryStatistical（KS 均匀性检验 pValue>=0.5）+ Fastswitch（<50ms 切换）+ AutoTool（<150ms 切正确工具） |
| **Matrix** 🟧 | CPS | avgCps > 14 → banAttack(weakness) |
| **TaKa** 🟧 | 槽位 | 切回原槽位 <120ms + 点击可点击物品 |
| **YCBR** 🟧 | cps + burst + CV | 1s 窗口 >20 + 200ms burst >=6 + 间隔 CV<0.1 |
| **Grim** ✗ | — | 无（2.3 才加入） |
| **ACR** ✗ | — | 无 |

**结论**：MX 统计最稳健（但默认关）。YCBR 的 cps+burst+CV 即时可用，可补峰度/熵维度。

### 4.10 Scaffold（搭路）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **ACA** 🟩 | 批处理统计 | ScaffoldAverageBatchProcessor：实际平均放置延迟 vs 理论最小延迟（直线 238ms/对角 138ms/潜行 +90ms/swift_sneak 修正），整批平均低于物理下限才 flag | 批处理天然抗单次抖动；去离群值；cancelVl=110 冷却 |
| **Grim** 🟧 | 协议/几何 | FabricatedPlace（ulp 浮点容差）/RotationPlace/PositionPlace/AirLiquidPlace/FarPlace/MultiPlace | ulp 容差精确，误判极少 |
| **Matrix** 🟧 | 7 型 | 转向率/低头角度/虚空桥/高延伸/整数旋转/塔楼 | isBlockTouched 精确判定相邻 |
| **TaKa** 🟧 | 5 型 | Basic/Advanced/Ground/Expand/Timer（pitch>=80 快速放） | 床/飞行/创造/潜行切换豁免 |
| **YCBR** 🟧 | 几何 + 行为 | InvalidPlace/FabricatedPlace/PlaceAim/FastPlace + 行为级 Rotation/Cadence/Colinear/Grid45/DupRot | 行为级对"手搭规整真人"有潜在误判 |
| **NCP** 🟧 | 规则 + 频率 | Against（依附方块合法性）+ FastPlace（桶频率） | againstVL ×0.99（"每 100 块 1 次误判"假设） |

**结论**：ACA 最强（批处理统计是独特优势）。Grim 协议级误判最少。YCBR 行为级最丰富但需防规整真人误判。

### 4.11 Velocity（击退）

| 反作弊 | 范式 | 关键机制 | 误判控制 |
|--------|------|----------|----------|
| **YCBR** 🟩 | 8 细分 | Horizontal/HorizontalPartial/HorizontalReversed/HorizontalPrecise(带 band)/Vertical/JumpReset/SprintReset | pingTicks 估算（高 ping 偏） |
| **Grim** 🟧 | 事务三明治 | KnockbackHandler（velocity sandwich + transaction 精确判定到达性）+ ExplosionHandler，阈值 0.001 | 精确知道 KB 何时到达客户端，区分网络延迟 vs 真没被推 |
| **NCP** 🟧 | 速度记账 | SimpleAxisVelocity 队列匹配 + 分裂 + 失效 + UnusedTracker（无视击退） | marginAcceptZero=0.005；unusedSensitivity=0.1 |
| **ACR** 🟧 | 期望比例 | motionY/expectedMotionY 百分比 < 阈值 | vlBeforeFlag + airTicks>5 超时重置 |
| **MX** ⬜ | 实验 | 水平/垂直 + JumpReset + transactionLock（默认关） | transactionLock 事务锁 |
| **Matrix** ✗ | — | 仅作 speed/fly 豁免 | — |
| **TaKa** ✗ | — | 仅作 Speed 豁免 | — |
| **ACA** ✗ | — | — | — |

**结论**：检测面 YCBR 最细（JumpReset/SprintReset 独有）；误判控制 Grim 最稳（transaction 三明治）。**YCBR 应补 transaction 到达判定，保留细分指纹**。

### 4.12 Blink / 协议

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **Grim** 🟩 | 事务/包序 | TransactionOrder/PacketOrder 校验到达性，囤包重放暴露为顺序异常 |
| **NCP** 🟧 | 传送 ACK | TeleportQueue 状态机：发出传送后必须回 ACK（PacketPlayInTeleportAccept），确认前取消移动包；maxAge 4000ms 防卡死 |
| **Matrix** 🟧 | phase | 基岩版穿墙/相位检测 |
| **YCBR** ⬜⬜ | 沉默时长 | `silence > max-silence-ms(2000)+ping` |

**结论**：Grim 最强。**YCBR 的 Blink 最弱**——只测沉默时长，改进版 Blink（囤包后重放 + 保活 Pong）极易绕过。

### 4.13 Criticals（暴击）

| 反作弊 | 范式 | 关键机制 |
|--------|------|----------|
| **NCP** 🟧 | 下落状态 | fallDistance>0 但很小（<criticalFallDistance）却打出暴击 → 伪造下落；排除载具/失明/velocityJumpPhase |
| **ACR** 🟧 | Y 整数 | 声明暴击但 Y 是整数（没真正跳起）+ 脚下实心 → 取消事件 |
| **TaKa** 🟧 | 期望伤害 | 实际伤害 == 期望暴击伤害但条件不满足 → flag |
| **YCBR** 🟧 | 启发式 | 击中瞬间 fallDistance/onGround 矛盾 |
| **Grim** ⬜ | 并入模拟 | 无专门检测 |
| **MX/ACA/Matrix** ✗ | — | 无 |

**结论**：NCP/ACR/TaKa/YCBR 各有思路，水平接近。YCBR 需确认阈值覆盖 `y-=0.000001` 级微小偏移（YcbrGrimCrit 绕过）。

---

## 5. 误判控制机制横向对比

| 机制 | Grim | NCP | MX | ACA | ACR | Matrix | TaKa | YCBR |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| VL/缓冲衰减 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 延迟/TPS 补偿 | ✅(事务) | ✅(TickTask) | ✅(事务锁) | ✅(TPS/Ping 门槛) | ✅(isLagging) | ⬜ | ✅(TPS 保护) | ⬜(ping 估算) |
| 灵敏度校准 | ⬜ | ⬜ | ✅ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 多信号交叉 | ✅ | ⬜ | ✅ | ✅(批处理) | ⬜ | ⬜ | ⬜ | ⬜(有框架未用) |
| 攻击阻断软惩罚 | ⬜ | ✅(Penalty) | ✅ | ⬜ | ⬜ | ✅(weakness) | ⬜ | ✅(blockAttacks) |
| setback 回滚 | ✅ | ✅ | ⬜ | ⬜ | ✅ | ✅ | ✅(tpBack) | ✅ |
| 豁免清单 | ✅ | ✅(极多) | ✅ | ✅(时间窗) | ✅ | ✅(极长) | ✅(方块级) | ✅ |
| 批处理统计 | ⬜ | ⬜ | ⬜ | ✅ | ⬜ | ⬜ | ⬜ | ⬜ |

---

## 6. 对 YCBR-AC 的启示（按优先级）

1. **Timer/Blink 是最弱环节**（wall-clock/silence）——Grim 的事务时间轴 + NCP 的 ACK 状态机是解药。`TransactionTracker` 已实现但零调用，**先接入**。
2. **移动类**——SimulationCheck 已具备 Grim 雏形但默认关、只做模长匹配、无 strafe 维度。补方向匹配 + 候选扩展 + 实机验证后开启，可追平 NCP 级。
3. **KillAura 护城河**——16 子启发式是纯工程最强，可借鉴 MX 的统计层（熵/KS/IQR）与 ACA 的旋转指纹做交叉验证。
4. **Scaffold**——借鉴 ACA 的批处理统计（实际平均延迟 vs 物理下限），替代纯行为级（防规整真人误判）。
5. **Reach**——借鉴 NCP 的 reachMod 动态收缩 + Grim 的多帧射线求交。
6. **Velocity**——保留细分指纹，补 transaction 到达判定（Grim 思路）。

---

## 7. 总体结论

| 维度 | 最强 | 次强 | YCBR 定位 |
|------|------|------|-----------|
| 移动类 | Grim | NCP | 有雏形（SimulationCheck），需补全并开启 |
| 战斗行为类 | MX | YCBR/ACA | 纯启发式最强，可加统计层 |
| Reach | Grim | NCP | 需升级多帧射线 + 动态收缩 |
| Scaffold | ACA | Grim | 行为级丰富，可加批处理统计 |
| Velocity | YCBR(检测面)/Grim(误判) | NCP | 细分是护城河，补事务到达判定 |
| Blink/协议 | Grim | NCP | 最弱，需事务化改造 |
| 整体误判率 | Grim < NCP < ACA < YCBR < ACR < TaKa < Matrix < MX(ML 风险) | | |

**一句话**：移动类学 Grim/NCP，战斗类学 MX/ACA，协议类学 Grim/NCP——YCBR 的 Velocity 细分与 KillAura 启发式是差异化护城河，保住它们，把 Timer/Blink 事务化、SimulationCheck 补全开启，是缩小差距的最短路径。
