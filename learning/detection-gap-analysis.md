# YCBR AC vs 参考反作弊 检测差距分析（2026-08-11）

对比对象（本地仓库实际版本）：Grim-2.0（1.8 预测流）、AntiCheatAddition-master（4.x Photon 重写版）、NoCheatPlus-master（2.x 重构版）、MX-Project-master（1.16.5 ML 流）、AntiCheatReloaded-master（1.8 Backend 计时族）、AQ3（Grim 风格简化）、OpenZen（客户端，仅供反证）。

> 注意：NCP master 已无 AimBot/Hits/Predict 旧检查；MX 服务器侧仅 10 个检查（无 Reach/Inventory）；ACA 是 Photon 重写版（无旧 1.x aimbot/timer 包，但 Scaffold 十件套齐全）。

## 一、YCBR 现有覆盖（基线）

KillAura(AimStep/Reach/Angle/Cps/GcdStable/ConstStep/AxisAsym/BigRot/Interval)、Scaffold(FastPlace/MovePlace/Rotation/Cadence/Colinear/Grid45/DupRot)、Speed、Fly(Rise/Hover)、Velocity、Criticals(默认关)。

## 二、差距矩阵（YCBR 完全没有的检测维度，按价值排序）

| 维度 | Grim | NCP | ACR | MX/AQ3 | 价值与成本 |
|---|---|---|---|---|---|
| **Timer/包频**（玩家时钟余额法 / ActionFrequency 分桶） | Timer/NegativeTimer | MorePackets/FlyingFrequency | MorePackets | AQ3 Timer | 极高/低（~100行）。所有移动类作弊的第一道防线；1.8 用 idle flying + keepalive 时钟即可 |
| **GCD 众数+灵敏度估计**（RunningMode 众数 80 样本/15 显著 → 反推灵敏度 0-200% → deltaDots 整数点） | AimProcessor | — | Aimbot(mod 模测试) | MX SensitivityProcessor(sens>50 门槛) | 极高/低。改造现有 GcdStable 的判据语义 |
| **NoFall**（onGround 声明 vs 脚底碰撞盒，1.8 只查 LOOK 包的声明） | NoFall+GroundSpoof | NoFall | NoFall(0.08 重力) | AQ3 NoFall | 极高/低。1.8 最常见作弊之一 |
| **协议合规套餐**（AimModulo360 取模跳变、IllegalPitch>90、AimDuplicateLook、InvalidPlaceA/B、FabricatedPlace、SelfInteract、MultiInteract、BadPacketsD/J/T） | 26+ | WrongTurn | — | — | 高/极低（纯包字段校验，每项几行）。铁证型，误报≈0 |
| **ExtremeMove 兜底**（\|yDist\|>4 或 hDist>22 且非速度递减 → VL×100） | (预测流内置) | MovingListener | — | — | 高/极低。防瞬移/大跳的最后防线 |
| **竖直物理包络**（vAllowedDistance：起跳 0.42+宽容 / 下落原速 / GRAVITY 0.0624-0.0834 / LiftOffEnvelope 6tick） | PredictionEngine | SurvivalFly | FlightCheck 魔法常量 | PredictionEngine | 极高/高。Fly 检测的完整形态；YCBR 目前只有 Rise>1.25+Hover |
| **Post/包序**（flying 包后、事务前发包=非法；1.8 用 idle 界定） | Post/PacketOrderO | — | PacketOrder(同 tick 多目标) | — | 中/低。时序刷动作 |
| **Passable/Phase 穿墙**（碰撞盒边界扫描） | Phase | Passable | — | — | 高/高。需要世界方块查询 |
| **动作互斥**（同 tick 攻击+放置、用物品+攻击等） | MultiActionsA-G | — | — | — | 中/低 |
| **NoSlow/Sprint 系列** | NoSlow+SprintA-G | — | — | — | 中/中 |
| **行为画像**（熵/KS 检验/distinct 随机化、4 维特征异常） | — | — | — | MX AimComplex/AimAnalysis/AimStatistics、AQ3 CheatPatternDetector | 高/高。MX 用熵+grid 归一抓"抖动仍对齐"的现代 aimbot |
| **击退 offset 法**（双事务夹心"是否到达"，threshold=0.001） | KnockbackHandler | — | VelocityTracker | — | 中/中。YCBR 百分比法(0.5/30%)偏粗但可用 |
| **Reach 射线法**（攻击排队到下 tick+补偿 AABB 射线求交） | Reach | Reach | Reach(3.55) | — | 中/中。YCBR 3.05+ping 快照法可用，缺射线 |
| **Inventory/吃吃喝喝/弓类**（InstantBow 800ms、InstantEat、FastClick、AutoSign） | breaking 族 | 全套 | Backend 计时族 | — | 中/低 |

## 三、GCD 检测专项对比（GcdStable 的核心问题）

共同内核：`EXPANDER=2^24` 定点化相邻 delta 对 → 欧几里得 gcd → 阈值 131072（≡0.0078125°）。

| 实现 | 判据 | 误报压制手段 |
|---|---|---|
| MX AimConstant | gcd<131072 + delta∈(0.25,20) + sens>50 + buffer 11 连击 | 灵敏度门槛 + 高连续数 + 电影镜头豁免 |
| MX AimComplex | 固定 0.45° 网格归一 + 双轴熵 + distinct/方差 | 熵一致性 |
| ACR Aimbot | gcd(pitch)∈(0,131072) + 加速度≥5.5 + **mod = pitch % (gcd/2^24) ≤ 8e-4** 模对齐测试 | 单发即判（注释自认会误报） |
| Grim AimProcessor | delta 对 gcd 的 **RunningMode 众数**（80 样本/15 显著）→ 反推灵敏度 → deltaDots=delta/mode 整数点 | 统计众数天然抗噪声 |
| **YCBR GcdStable** | 相邻对 gcd<131072 + **std<0.25** + streak≥6 + mean>1 | 靠 std 窗口，与 ConstStep 冗余 |

结论：
1. **人类 float32 量化噪声下单对 gcd<131072 人人命中**——三大家各有压制手段，YCBR 的 std<0.25 让 GcdStable 退化成"恒定步长"检测（与 ConstStep 语义重叠），且**抓不到 OpenZen 式"网格对齐+小幅抖动"现代 aimbot**（抖动破坏 std 条件）。
2. 正确方向 = Grim 式**众数估计灵敏度**（mode 恒定 → deltaDots 整数）或 ACR 式**模对齐测试**（delta 与估计网格求余 ≤ 8e-4）。YCBR 数据层（PlayerData 40 窗口）可直接支撑，预计 ~80 行。
3. MX/ACR 都用 **pitch 轴**（比 yaw 稳定，不受转向影响）；YCBR 目前用 yaw，建议双轴。

## 四、落地路线图（建议优先级）

P0（先做，低成本高价值）：
1. **Timer 包频**：1.8 用 idle flying 界定 tick + 每包 +50ms 玩家时钟余额，超实时 → flag（Grim Timer 逻辑 ~100 行）
2. **GCD 众数+模对齐**：改造 GcdStable（PlayerData 加 RunningMode 众数桶，deltaDots 整数判定）
3. **NoFall**：LOOK 包的 onGround 声明 + 脚底 0.6×0.001 碰撞盒查询（1.8 无需事务）
4. **协议合规套餐**：AimModulo360 / IllegalPitch / InvalidPlaceA/B / FabricatedPlace / SelfInteract / MultiPlace
5. **ExtremeMove**：\|y\|>4 / \|h\|>22 兜底重罚

P1（下一步）：
6. 竖直物理包络（NCP SurvivalFly vAllowedDistance 体系改造 YCBR Fly）
7. Post/包序（idle flying 界定，先做 PacketOrderO 版）
8. 动作互斥（MultiActionsA 的 1.8 版：flying 界定 tick）

P2（远期/大工程）：
9. 预测引擎 + 碰撞（Phase/Passable/Simulation）
10. 行为画像（熵/KS/distinct）——对抗现代 aimbot
