# YCBR-AC 与 Grim 2.0 共同检测项对比分析

> 分析对象：`YCBR-AC/`（用户自研反作弊，Java，阈值/行为统计架构）与 `Grim-2.0/`（开源预测型反作弊，Kotlin/Java）
> 分析日期：2026-08-13
> 核心问题：在两者都覆盖的检测项中，哪个更强大、误判更少？

---

## 1. 摘要：两类架构的根本差异

| 维度 | YCBR-AC | Grim 2.0 |
|------|---------|-----------|
| 核心范式 | 阈值 / 行为统计 | 物理重演 / 预测模拟 |
| 移动检测 | 手工列举加成 + 经验上限 + 容差 + VL 缓冲 | 统一 PredictionEngine 逐 tick 重演 Mojang 物理 + 容差盒 |
| 战斗检测 | 视角增量统计（GCD、方差、间隔 CV、射线命中盒） | 主要为协议校验 + Reach，KA/AutoClick 为 placeholder |
| 误判控制 | drain/bump 缓冲 + 魔法数容差 + 大量豁免列举 | transaction 三明治判定到达性 + UncertaintyHandler 容差盒 |
| 强项 | KillAura、AutoClick、Velocity 细分、Scaffold 行为 | 全部移动类、Reach、Blink/协议校验 |

**一句话结论**：移动类 Grim 几乎全面碾压且误判更少；战斗/行为类 YCBR 在 KillAura 与 AutoClick 上明显更强。

---

## 2. 对比矩阵总表

图例：🟩 = Grim 胜　🟧 = YCBR 胜　⬜ = 平手
（左列为"检测能力/覆盖抓取力"，右列为"误判控制/少误杀"）

| 检测项 | 检测能力 | 误判控制 |
|--------|:--------:|:--------:|
| Reach 攻击距离 | 🟩 | 🟩 |
| Fly 飞行 | 🟩 | 🟩 |
| Speed 移动速度 | 🟩 | 🟩 |
| NoFall 跌落伤害 | 🟩 | 🟩 |
| Velocity 击退 | 🟧 | 🟩 |
| Timer 计时器 | 🟩 | 🟩 |
| NoSlow 减速 | 🟩 | 🟩 |
| Sprint 疾跑状态 | 🟩 | 🟩 |
| KillAura 自瞄 | 🟧 | 🟧 |
| AutoClick 自动点击 | 🟧 | 🟧 |
| Scaffold 搭路 | ⬜ | 🟩 |
| Blink / 协议校验 | 🟩 | 🟩 |

---

## 3. 逐项深度分析

### 3.1 移动类（Grim 全面胜出）

#### Speed（移动速度）
- **YCBR**：`SpeedCheck` 分 ground/air 两套经验公式。`ground.limit=0.29` 加着陆 bonus、药水、冰、史莱姆、盒子、跳跃；`air` 用 `momentum=0.36*0.985^airTicks` 经验拟合 + burst 查表。4-tick 滑动窗口求和对比 limit 和。
- **Grim**：无独立 Speed，由 Simulation 穷举**所有合法输入组合**（sprint 1.3×、跳跃、介质摩擦、riptide、烟花鞘翅、史莱姆弹射），任何无法解释的位移 → offset，阈值 0.001。
- **评价**：Grim 明显更强。YCBR 必须手工列出每种加成，任何新介质/版本/模组组合（1.9+ end-tick、ViaVersion 转译）都可能失准；其 air momentum 公式是经验拟合，与真实 Mojang 物理有偏差，会被压在下沿的 cheat 绕过，或低 TPS 时误判。

#### Fly（飞行）
- **YCBR**：`FlyCheck` 的 `Rise`（上升速度超过重力预期 + jump tolerance）+ `Level`（悬停 ticks 超 `max-hover-ticks`）。对"关重力/改 velocity 不下落"型飞行，主要靠 Level 悬停检测，需悬停 > 8 ticks 才 flag，且 teleport/velocity 后被 drain 豁免。
- **Grim**：协议层 `FlightA`（无权限却发 flying 包直接 flag）+ 物理层由 Simulation 覆盖（任何不符物理的位移逐 tick 累积 offset）。
- **评价**：Grim 更强、误判更少。Simulation 是逐 tick 重演，对瞬停瞬飞的 bypass 更直接；FlightA 还能抓"有飞行客户端但无权限"。YCBR 的 Fly 逻辑透明易调，是其优点。

#### NoFall（跌落伤害）
- **YCBR**：两种脆弱启发式——(1) 声称 onGround 但 `airTicks>8 && motionY<-0.4` 且脚下无方块（依赖 `blockBelowUnstandable` 方块查询）；(2) 落地无伤害（依赖服务器是否真造成摔伤）。阈值 fall > 3.0 块才 flag。
- **Grim**：协议层 NoFall（无位置更新的纯旋转/地面包却声明 onGround 但脚底碰撞盒离地）+ 预测层 GroundSpoof（客户端声明 onGround 与引擎算出 onGround 不一致）。GhostBlock 豁免，setback 改写 onGround。
- **评价**：Grim 更强。预测引擎的 onGround 真相 vs 客户端声明对比精确，且能即时改写 packet 拦截（无声 setback），误判更少。

#### NoSlow（减速）
- **YCBR**：`expected = lastDistanceXZ * 0.92 + 0.01`（经验衰减近似真实 ×0.6 减速），豁免列表（冰/史莱姆/梯子/盒/KB）手工列举。
- **Grim**：预测引擎把"使用物品减速 ×0.6"作为候选输入分支，offset 落到 NoSlow（阈值 0.001，比一般更低），连续两帧才 setback，1.8 切物品首帧特殊处理。
- **评价**：Grim 更强。手工列举豁免易漏新减速源；Grim 直接建模进预测候选。

#### Timer（计时器）
- **YCBR**：基于移动 packet 到达频率（6s 窗口 EPS > 22 + 2s 短窗口 + 500ms burst），依赖 wall-clock（`arrivalTime`）。
- **Grim**：`TimerA` 把计时绑到玩家自己的 ping/事务往返，每移动包 +50ms 余额，超 `System.nanoTime()` 才 flag。
- **评价**：Grim 更强、误判更少。YCBR 用真实时间，网络抖动/丢包制造假 EPS 波动，高 ping 玩家 packet 稀疏使 EPS 阈值不鲁棒；Grim 与 ping 绑定，ping 波动永不误判，低 ping 抓 1.01×。

#### Sprint（疾跑状态）
- **YCBR**：`SprintCheck` 只抓 sprint 状态在 < 20ms 内反复 flip >= 3 次。
- **Grim**：7 个细分检查（SprintA 饥饿过低、SprintB 蹲伏/爬行、SprintC 用物品、SprintD 失明、SprintE 撞墙、SprintF 鞘翅、SprintG 水中）。
- **评价**：Grim 碾压。YCBR 仅抓 flip spam，对"非法保持疾跑"无能为力。

### 3.2 Velocity（击退）：YCBR 细分更强，Grim 误判更稳

- **YCBR** `VelocityCheck` 做了极细子类：
  - `Horizontal`（KB 取消 ratio < minRatio）
  - `HorizontalPartial`（部分减少）
  - `HorizontalReversed`（反向移动）
  - `HorizontalPrecise`（精确比值带 `bandMin~bandMax`）
  - `Vertical`（KB 吸收 pct < minimum）
  - `JumpReset`（KB 瞬间跳跃重置 —— 高级 speed/fly 滥用指纹）
  - `SprintReset`（KB 前停疾跑重置）
  - 含 ping 补偿 ticks、wall/ceiling 豁免，`expectedH = |kb| * 0.91^t`。
- **Grim** `KnockbackHandler`（velocity sandwich + transaction 三明治精确判定到达性）+ `ExplosionHandler`，阈值 0.001，黑掉 -0.04 客户端 bug 向量。
- **评价**：**检测覆盖面 YCBR 更细**（JumpReset/SprintReset 是 Grim 没有的专门子类）；**误判控制 Grim 更稳**（transaction 精确知道 KB 何时到达客户端，区分"网络延迟" vs "真没被推"）。YCBR 用 `pingTicks=ceil(ping/50)` 估算，高 ping/抖动下偏。

### 3.3 战斗/行为类（YCBR 在 KA/AutoClick 上胜出）

#### Reach（攻击距离）—— Grim 胜
- **YCBR** `ReachCheck` / `KillAuraCheck.checkReach`：用 `max-reach=3.1` + 眼高 1.62 + 实体半宽 + ping 补偿 + 外推 cap，计算到 AABB 距离。另含独立 `Angle`（射线命中盒）+ `ThroughWalls`（体素采样射线 + 双触发窗口）。
- **Grim** `Reach`：精确射线追踪 + 实体所有可能碰撞盒（插值不确定）+ 枚举多帧视角（当前/上一/上上 yaw·pitch）+ 多眼高 + 攻击范围属性/组件，阈值 0.0005，含 `cancelBuffer` 实时取消不可能的攻击。
- **评价**：Grim 更强、误判更少。YCBR 用上一帧位置 + 两档眼高，工程合理但不够严谨；Grim 的射线求交 + 多帧视角容差几乎消除"差一帧"误判，且能实时取消非法命中。YCBR 的 ThroughWalls 射线采样是额外加分项。

#### KillAura（自瞄）—— YCBR 明显胜
- **YCBR** `KillAuraCheck` 是一套专业体系：`GcdStable`/`GcdGrid`/`ConstStep`/`AxisAsym`/`AimStep`/`Modulo360`/`BigRot`/`Switch`/`MultiTarget`/`Interval`(CV)/`NoSwing`/`MultiInteract`/`SelfInteract`/`AutoBlock`/`Post`/`InventoryCombo`，覆盖 KA 主要行为指纹。
- **Grim** 2.2.x 自我注释明确"无独立 KillAura/Aim/AutoClicker 检测"，Aim 仅 `AimDuplicateLook`/`AimModulo360`/`AimProcessor`（GCD 灵敏度提取），KA 靠 Reach + 协议异常间接覆盖。
- **评价**：YCBR 明显更强。Grim 对"完美合法距离但瞬转锁敌"的高级 KA 几乎无专门检测。

#### AutoClick（自动点击）—— YCBR 胜
- **YCBR**：`FastClickCheck`（200ms 窗口 >= 6 次 burst）+ `cps`（1s 窗口 > 20 连续窗口）+ `Interval`（间隔 CV < 0.1 过于规律）。
- **Grim**：当前版本无 AutoClicker 检测（注释说明 2.3 才加入）。
- **评价**：YCBR 胜（Grim 缺位）。注意 YCBR 的 FastClick burst 阈值较宽松，且用真实时间窗口，高 ping 下攻击包密集可能误判（已有 max-ping 200 豁免）。

#### Scaffold（搭路）—— 平手偏 Grim（误判少）
- **YCBR** `ScaffoldCheck`：几何 + 行为双覆盖。`InvalidPlace`/`FabricatedPlace`/`PlaceAim`/`FastPlace`/`MovePlace` + 行为级 `Rotation`/`Cadence`(每 tick 放置)/`Colinear`(线性网格桥)/`Grid45`(45° 网格)/`DupRot`。
- **Grim** Scaffolding 系列：`FabricatedPlace`(ulp 浮点容差)/`RotationPlace`/`PositionPlace`/`AirLiquidPlace`/`FarPlace`/`MultiPlace`/`DuplicateRotPlace`，均为协议/几何合法性校验。
- **评价**：**Grim 协议级更精确（ulp 容差），误判极少**；**YCBR 行为级更丰富**（Cadence/Colinear/Grid45 抓每 tick 完美搭路的 bot）。对"手搭很规整的真人"，YCBR 行为统计有潜在误判。

### 3.4 协议类（Grim 更强）

- **YCBR** `BlinkCheck`：仅测"超过 `max-silence-ms`(2s) + ping 无位置包"的沉默时长。
- **Grim**：无 Blink 专门检测，但被 Timer/PacketOrder/TransactionOrder 自然捕获；`BadPackets A-Z`（26 个）全面协议校验（pitch 范围、slot 重复、spectate、teleport 接受、keepalive、视角一致性等）。
- **评价**：Grim 更强。YCBR 的 Blink 易被"边动边偶尔发包"改进版绕过，高 ping 下边界粗糙；Grim 用 packet 顺序/事务到达性校验，囤包重放暴露为顺序异常。

---

## 4. 对 YCBR-AC 的优化建议

1. **移动类是最大的短板**：考虑引入"预测/模拟"思路——至少对 Speed/Fly/NoFall 用统一物理重演 + 容差盒，替代一长串魔法数容差。这能同时提升抓取力与降误判（这是 Grim 的本质优势）。
2. **保留并强化 Velocity 的细分检测**（JumpReset/SprintReset/精确比值带）：这是相对 Grim 的差异化优势，建议继续打磨。
3. **补强 Blink**：借鉴 Grim 的 TransactionOrder 思路，不只测"沉默时长"，而是校验 packet 顺序/事务到达性，否则改进版 Blink 易绕过。
4. **巩固 KillAura / AutoClick 护城河**：继续提升 GCD/方差/间隔统计在高 ping 下的稳健性，避免误判。
5. **Reach 精度提升**：从"上一帧位置 + 两档眼高"升级为"多帧视角枚举 + 实体插值碰撞盒 + 实时取消非法命中"，缩小与 Grim 的差距。

---

## 5. 总体结论

- **移动类**：Grim 全面胜（物理重演 + 容差盒 vs 经验阈值 + 魔法数）。
- **战斗行为类**：YCBR 在 KillAura、AutoClick 上明显胜（Grim 当前无专门检测）。
- **互有胜负**：Velocity（YCBR 细分更广 / Grim 误判更稳）、Scaffold（Grim 协议更严谨 / YCBR 行为更全）。

两者定位互补：**Grim 强在"证明过程合法"**，**YCBR 强在"刻画行为指纹"**。在"相同检测谁更强、误判少"这个问题上——移动类选 Grim，战斗行为类选 YCBR。
