# YCBR-AC 四家反作弊对比：YCBR / Grim / MX / NCP

> 分析日期：2026-08-14
> 对象：Minecraft 1.8.9（PC Java）四款反作弊
> - **YCBR-AC**（`com.ycbr.anticheat`，本仓库）
> - **Grim 2.0**（`ac.grim.grimac`，`Grim-2.0/`）
> - **MX**（`kireiko.dev.anticheat`，`MX-Project-master/`，作者 Kireiko）
> - **NCP**（NoCheatPlus，`fr.neatmonster.nocheatplus`，`NoCheatPlus-master/`）
> 方法：四家源码实测核对（非文档宣传）。Matrix 基岩版、NCP-PE 是干扰项，已排除。
> 核心问题：
> 1. 相同检测项，谁抓取力更强、谁误判更少？
> 2. YCBR-AC 如何学习各家技术？

---

## 0. 四家定位（先给结论）

| 维度 | Grim | YCBR-AC | MX | NCP |
|------|------|---------|-----|-----|
| 核心范式 | **物理仿真**（逐 tick 重演客户端位置） | 统计 + 事务对齐 | **ML/RNN + 事务**（瞄准专精） | 魔数包络 + 频率桶 + 几何近似 |
| 物理精度 | 高（碰撞盒唯一确定合法位移） | 中（引擎公式一致但默认关，生产跑经验回退） | 低（仅 Velocity 轻量残差） | 低（`Magic.*` 经验拟合） |
| 事务(transaction) | 有（bookmark 同步、逐 tick 包序） | 有（TransactionTracker） | 有（仅 Velocity 用 CTransactionEvent 门控） | **无**（wall-clock + `TickTask.getLag`） |
| ML/RNN | 无 | 有 SimpleMLP（默认关） | **有 BiLSTM+Attention+LayerNorm + 在线学习** | 无 |
| 检测覆盖 | 移动/协议极强；战斗弱 | 移动/协议/战斗全覆盖 | **战斗专精**（Aim/KA/AC）；移动盲区 | 覆盖广但浅 |
| 误判哲学 | 精确→低误判，配置敏感 | 容差+VL+交叉验证可控 | 灵敏度校准+多信号交叉+渐进惩罚 | 重豁免/workaround/VL 衰减（低误判优先） |
| 数据依赖 | 无 | 无（MLP 需样本） | 有（RNN 数据集偏差风险） | 无 |

**一句话**：Grim 是移动类的天花板，MX 是战斗类的天花板，YCBR 是覆盖面最均衡的通用基座，NCP 是老牌稳定但精度受限的规则引擎。

---

## 1. 逐项对比：相同检测谁更强、谁误判少

图例（抓取力）：🔴 YCBR 更强 · 🟡 持平/互有侧重 · 🟢 对方更强 · ⚪ 该家不检测
图例（误判）：🟢 低 · 🟡 中 · 🔴 较高 · ⚪ 该家不检测（天然零误判=无检测）

### 1.1 Speed / Fly / NoFall（移动类）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟢 最强 | 🟢 极低 | 碰撞盒重演：台阶/半砖/墙边/活塞/载具/幽灵方块全纳入，容差 0.001 仅覆盖量化误差 |
| YCBR | 🟡 第二 | 🟡 中 | 引擎公式已与 1.8.8 一致（模长匹配+服务器 onGround+idle 候选+楼梯豁免）但**默认关**；生产跑经验回退（魔法数+上下文加成） |
| NCP | 🟢 第三 | 🟡 中（靠重豁免维持低） | `SurvivalFly` 魔数包络（`Magic.WALK_SPEED` 等）+ `LostGround`/`BlockChangeTracker` 大量 workarounds |
| MX | ⚪ 不检测 | ⚪ | 仅 `BaritoneCheck`（寻路 bot 旋转指纹）、`GhostBlockAbuseCheck`（setback 修正），非通用移动检测 |

**结论**：移动类是 Grim 的独占天花板（精度+误判双胜）。YCBR 第二，但引擎未默认开启、生产仍靠经验回退，是最大短板。NCP 靠海量豁免把误判压住但漏检率高（精确贴包络 cheat 绕过）。MX 在此类是盲的。

### 1.2 NoSlow

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟡 | 🟢 低 | 协议级（客户端减速违规，候选分支） |
| YCBR | 🟡 | 🟢→🟡 较低 | 引擎 `predictSingle(usingItem=true)`；`ItemUseLogic` 只认吃/喝/拉弓 |
| NCP | 🟡 | 🟢→🟡 较低 | 融入 `SurvivalFly` 移动处理 |
| MX | ⚪ | ⚪ | 无经典 NoSlow（`SprintCheck` 实为 KA 行为指纹） |

**结论**：三家持平、误判均低。MX 无此项。

### 1.3 Timer

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟡 | 🟢 低 | 事务三明治测客户端真实经过时间 |
| YCBR | 🟡 | 🟢→🟡 较低（已 TPS 归一化，修掉 19.2 TPS 系统误判） | 服务器 tick 间隔（长/短/突发三窗口）+ 事务活性前置 |
| NCP | 🟢 第三 | 🟡 中（lag>1.5f 跳过 VL，阈值经验值） | `MorePackets` EPS 频率桶 + 突发检测 + `TickTask.getLag` 补偿 |
| MX | ⚪ | ⚪ | 不检测 |

**结论**：Grim≈YCBR 强且误判低；NCP 靠频率桶近似，对温和加速与漏包区分弱；MX 无。

### 1.4 Blink

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟡 | 🟢 低 | `TransactionOrder` 重放次序校验 |
| YCBR | 🟡 | 🟢→🟡 较低 | "有事务 pong 无移动包"核心判定（2s 阈值+保活） |
| NCP | 🟡 弱 | 🟡 | 无专门 Blink，`MorePackets` 是包率非囤包 |
| MX | ⚪ | ⚪ | 不检测 |

**结论**：Grim≈YCBR 强；NCP/MX 弱/无。

### 1.5 Velocity（击退/速度）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🔴 **最强** | 🟢→🟡 较低 | 事务化（`kbArrivalServerTick`+到达窗口±1 tick）**且保留 JumpReset/SprintReset 行为指纹**（Grim 无），覆盖面超 Grim |
| Grim | 🟢 第二准 | 🟢→🟡 较低 | `KnockbackHandler` 事务三明治精确判定到达；黑名单 `y=-0.04` bug 向量 |
| MX | 🟡 第三 | 🟢→🟡 较低 | 轻量单向量残差 + `transactionLock` 往返门控 + 橡皮筋式 setback 回滚（`SimulationFlagService`）；**默认关闭** |
| NCP | 🟡 第四 | 🟡 中 | `SimpleAxisVelocity`/`FrictionAxisVelocity` **速度账本**（入队-消耗-容差），无事务，纯统计 |

**结论**：YCBR 在到达时刻精度与 Grim 同级，且行为指纹覆盖超 Grim → **YCBR 最强**。MX 思路轻量但默认关。NCP 的"速度账本消耗"思路（把服务端发出但玩家从未消费的速度记为未用击退）值得 YCBR 借鉴。

### 1.6 Reach（攻击距离）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟡 | 🟢→🟡 较低 | 射线求交 + 插值碰撞盒 + 多帧视角 + 攻击范围属性，阈值 0.0005 |
| YCBR | 🟡 | 🟢→🟡 较低（已实时取消不可能攻击 `shouldCancelAttack`） | 多帧射线-AABB + 插值回退 + 双方移动 allowance |
| NCP | 🟢 第三 | 🟡 中 | 眼睛到实体中心"点到点"近似 + 动态 `reachMod` 收敛；贴包络 legit 抖动易误判、微调距离易绕过 |
| MX | ⚪ | ⚪ | 不检测 |

**结论**：Grim≈YCBR 强且误判低（YCBR 已补齐实时取消）；NCP 精度最低；MX 无。

### 1.7 KillAura / Aim（战斗）

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| **MX** | 🔴 **完胜** | 🟢→🟡 较低（ML 有数据集风险） | 4 主检测 + 7 启发式组件（randomizer/constant/invalid/inconsistent/pattern/factor/smooth）+ `AimStatisticsCheck`（IQR/KS/熵/Z/Jiff）+ **BiLSTM+Attention+LayerNorm 可训练 RNN** + 灵敏度校准 + 攻击阻断渐进惩罚 |
| YCBR | 🟡 第二 | 🟡 中（9 个瞄准子项被 aimstat 交叉验证门控，默认关则零误判） | 16+ 启发式（GCD/方差/行为指纹）+ 统计层（熵/KS/IQR/峰度/Z/Jiff）+ SimpleMLP + 交叉验证 |
| NCP | 🟢 第三 | 🟡 中 | `Angle`（转向角加权）+ `Direction`（视线到盒偏移）+ `NoSwing`（无挥手）+ `SelfHit`（自伤），纯传统启发式 |
| Grim | ⚪ 无 | ⚪ 零（漏） | 无独立 KA/Aim，靠 Reach+协议异常间接 |

**结论**：**MX 完胜**（统计+ML+RNN 多引擎，且误判控制最严谨：灵敏度校准+多信号交叉+渐进惩罚）。YCBR 第二（启发式深度够，但缺 ML/RNN 与灵敏度校准）。NCP 第三（传统启发式，易被 aimbot 微偏绕过）。Grim 无。

### 1.8 AutoClicker

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| YCBR | 🟡 | 🟡 中 | burst + cps + Interval(CV) + `FastClickLogic` 峰度/熵机械节律 |
| MX | 🟡 | 🟡 中（默认关、需 100 样本） | 挥手包间隔 → 峰度 + 香农熵（机械规律性） |
| NCP | 🟡 弱 | 🟡 | 无专门 AC，靠 `fight/Speed` 攻击频率间接 |
| Grim | ⚪ | ⚪ | 无 |

**结论**：YCBR≈MX 强且误判低（YCBR 更即时默认可用，MX 更学术但默认关）；NCP/Grim 弱/无。

### 1.9 Scaffold

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟢 最强 | 🟢 低 | 协议级（FabricatedPlace/RotationPlace/PositionPlace/AirLiquidPlace，ulp 容差） |
| YCBR | 🟡 第二 | 🟢→🟡 较低（行为层默认关） | 协议/旋转/放置层（已开）+ 行为层（cadence/colinear/grid45/duprot，默认关） |
| NCP | ⚪ 无专门 | ⚪ | 无 Scaffold 类（`Passable` 是穿墙非 scaffold） |
| MX | ⚪ | ⚪ | 不检测 |

**结论**：Grim 协议最严谨；YCBR 行为覆盖更广但默认关；NCP/MX 无。

### 1.10 Sprint

| 家 | 抓取力 | 误判 | 判定范式 |
|----|--------|------|----------|
| Grim | 🟡 | 🟢 低 | 7 类状态合规（含鞘翅；1.8.8 无鞘翅故等效 6 类） |
| YCBR | 🟡 | 🟢→🟡 较低（翻转窗口 40ms，误判源转为服务端权威快照） | 6 类状态合规（饥饿/潜行/用物品/失明/头顶挡/水中） |
| NCP | 🟡 弱 | 🟡 | 无独立 sprint 合规（`SurvivalFly` 间接处理速度） |
| MX | 🟡 互补 | 🟢→🟡 | `SprintCheck` 实为 KA 行为指纹（Zero 的 sprint 高频翻转），非传统 Sprint |

**结论**：Grim≈YCBR 持平且低误判（1.8.8 无鞘翅，YCBR 6 类即完整）；NCP 中等；MX 互补（KA 指纹）。

### 1.11 其他特色检测

| 检测 | Grim | YCBR | MX | NCP |
|------|------|------|-----|-----|
| Criticals（假性暴击） | 部分 | ✅ 有 | ⚪ | ✅ 状态机（`Critical.java`） |
| NoSwing（无挥手攻击） | ⚪ | ✅ 有 | ⚪ | ✅ 有（`NoSwing.java`） |
| Passable（穿墙） | 整合进物理 | ✅ 体素射线（through-walls） | ⚪ | 🟢 **几何射线追踪**（`Passable.java`，NCP 最接近真仿真者） |
| GhostBlock 修正 | setback | 部分 | ✅ setback 回滚 | ⚪ |
| Baritone（寻路 bot） | ⚪ | ⚪ | ✅ 独特旋转指纹 | ⚪ |

---

## 2. 双维度总评表

| 检测项 | 抓取力排名 | 误判最低 |
|--------|-----------|----------|
| Speed/Fly/NoFall | Grim > YCBR > NCP > MX(无) | Grim |
| NoSlow | Grim ≈ YCBR ≈ NCP > MX(无) | Grim/YCBR/NCP 均低 |
| Timer | Grim ≈ YCBR > NCP > MX(无) | Grim/YCBR 低 |
| Blink | Grim ≈ YCBR > NCP > MX(无) | Grim/YCBR 低 |
| Velocity | **YCBR > Grim > MX > NCP** | YCBR/Grim 低 |
| Reach | Grim ≈ YCBR > NCP > MX(无) | Grim/YCBR 低 |
| KillAura/Aim | **MX > YCBR > NCP > Grim(无)** | MX（含风险）/YCBR 可控 |
| AutoClicker | YCBR ≈ MX > NCP > Grim(无) | 均低 |
| Scaffold | Grim > YCBR > NCP/MX(无) | Grim 低 |
| Sprint | Grim ≈ YCBR > NCP；MX 互补 | Grim/YCBR 低 |

**抓取力总分**：YCBR 在 Velocity 独占鳌头，且在 KA/Aim（第二）、AutoClick、Sprint 上不弱；Grim 在移动类独占；MX 在战斗类独占；NCP 各项均非第一但覆盖广。
**误判总分**：Grim 在移动类结构性最低；YCBR 经本轮修复后除移动类外均与 Grim 同级或可控；MX 战斗误判低但 ML 有数据集风险；NCP 靠重豁免维持低误判但漏检率高。

---

## 3. YCBR-AC 如何学习各家技术（路线图）

> 原则：YCBR 已是覆盖面最均衡的通用基座。学习方向 = **补移动类精度（学 Grim）+ 升战斗智能（学 MX）+ 借鉴 NCP 的账本/融合思路**，而非照搬。

### P0 — 补移动类精度（学 Grim 物理仿真）

1. **实机开启并打磨 `SimulationCheck`**（当前默认关，是最大短板）
   - 按 `docs/plans/2026-08-14-simulation-tuning-sop.md` 先低负载观察误判，容差 0.01/0.02 起步。
   - 这是唯一仍明显弱于 Grim 的项，优先级最高。
2. **加 AABB 碰撞截断 + 特殊介质**（学 Grim 碰撞盒唯一确定）
   - 引擎当前无碰撞盒模拟，台阶/墙边/楼梯靠容差兜底。引入 `simulation` 的方块碰撞截断（台阶/半砖/墙边），把合法位移由世界唯一确定。
   - 补活塞/载具/末影珍珠/鞘翅的介质处理（Grim 已覆盖，NCP 用 `BlockChangeTracker` 推拉豁免）。
3. **方向+strafe 匹配**（学 Grim 候选枚举）
   - 当前 `SimulationCheck` 仅模长匹配 + idle 候选。补 sprint/跳跃/介质全候选 + 方向夹角判定，缩小与 Grim 的差距（对应 `修改计划 8AC` P2.1）。

### P1 — 升战斗智能（学 MX 的 ML/RNN + 统计 + 校准）

4. **Aim/KA 引入高级统计检验**
   - 复用已有 `Statistics` 工具，补 MX 独有的 **KS 检验、Jiff 重复模式、Shannon 熵、Z-score 离群** 维度（YCBR 已有熵/IQR/KS/Z/Jiff 雏形，需强化交叉验证门控）。
5. **灵敏度校准 `SensitivityProcessor`**
   - MX 用 `calculateSensitivity()` 避免不同鼠标 DPI/灵敏度误杀。YCBR 的 GCD 类天然鲁棒，但非 GCD 类检测（旋转步进、模360）会受灵敏度影响，应补校准。
6. **轻量可训练模型（学 MX RNN）**
   - YCBR 已有 `SimpleMLP`（默认关）。升级为 **BiLSTM + Attention + LayerNorm**（参考 MX 的 `RNNModelML(16,48)`、HYBRID 预处理、AdamW、checkpoint 按 recall/FPR 择优），做攻击旋转序列分类。
   - 配 `DatasetManager` 在线采集样本 + 增量训练（YCBR 已有 `/ycbr record`）。
   - 渐进惩罚：模型高置信度时**禁攻击(cancel)** 而非直接封，降低误判后果。
7. **多信号交叉 + 置信度分级**
   - 借鉴 MX 的"多统计维度同时异常才 flag" + 四级置信度（UNUSUAL/STRANGE/SUSPECTED），与 YCBR 现有 aimstat 交叉验证门控整合。

### P2 — 借鉴 NCP 的账本与融合（低成本高回报）

8. **Velocity 速度账本**（学 NCP `SimpleAxisVelocity`/`FrictionAxisVelocity`）
   - 把服务端发出的速度向量入队、按符号匹配消耗、带容差与摩擦衰减，识别"被发出但玩家从未消费的速度"（击退绕过）。与 YCBR 现有 `kbArrivalServerTick` 事务到达判定互补，提升精度。
9. **跨检测多源融合**（学 NCP `Improbable`）
   - 各检测小违规喂入统一频率桶，短窗/全窗超阈值才升级 VL。YCBR 已有交叉信号框架，可升级为 NCP 式" improbable 融合"。
10. **Passable 几何射线穿墙**（学 NCP `Passable.java`）
    - YCBR 已有 `through-walls` 体素射线采样。借鉴 NCP 的多轴序（YXZ/YZX/XZY/ZXY）取最宽松 + 起步已在方块内不判 + 实时方块变化跟踪，提升稳定性。

### 不学什么（避免退化）

- **不学 NCP 的 `Magic.*` 魔数经验拟合**：精度上限低、维护成本高（源码满是 `TODO: Remove fumbling with magic constants`）。YCBR 已有事务化与引擎公式，优于此路。
- **不学 MX 把 Velocity 默认关**：YCBR 的 Velocity 是差异化强项（JumpReset/SprintReset 指纹），应保持默认开。
- **不学 Grim 放弃战斗**：YCBR 的战斗覆盖是护城河，应继续强化而非舍弃。

---

## 4. 总结

- **移动类**：Grim 天花板（碰撞盒），YCBR 第二（引擎默认关是最大短板），NCP 靠豁免维持，MX 盲。→ YCBR 学 Grim 物理仿真。
- **战斗类**：MX 天花板（ML/RNN+统计+校准），YCBR 第二（启发式深但缺 ML/校准），NCP 传统启发式，Grim 无。→ YCBR 学 MX 的 ML/统计/校准。
- **协议/速度类**：YCBR 在 Velocity 独占鳌头（指纹超 Grim），Timer/Blink/Sprint 与 Grim 持平。→ 保持并借鉴 NCP 账本。
- **最值得 YCBR 立即做的三件事**：
  1. P0 实机开启 `SimulationCheck`（补移动差距，这是评分唯一硬伤）
  2. P1 战斗引入 KS/熵/Jiff 统计 + 灵敏度校准（向 MX 看齐）
  3. P2 Velocity 加 NCP 式速度账本 + Improbable 融合

**最终定位**：YCBR-AC 已是"最均衡通用基座"，学习 Grim 的物理精度 + MX 的战斗智能 + NCP 的账本融合，可成为四家中唯一"移动准、战斗智、覆盖全"的方案。

---

*配套文档：`YCBR-AC_vs_Grim_对比分析_v2.md`（双家）、`YCBR-AC_vs_MX_对比分析.md`（双家）、`docs/2026-08-14-code-audit-empty-auth.md`（代码体检）、`docs/2026-08-14-simulation-tuning-sop.md`（仿真调参 SOP）。*
