# YCBR-AC vs GrimAC：同项检测评估与改进路线

> 分析对象：`YCBR-AC/`（自研反作弊，Java，阈值/行为统计 + 新预测引擎雏形）vs `Grim-2.0/`（开源预测型反作弊，Kotlin/Java）
> 分析日期：2026-08-13
> 依据：两边源码逐项核对（Grim-2.0 `common/.../checks/impl/`，YCBR-AC `src/main/java/com/ycbr/anticheat/`）
> 说明：本文档反映 **YCBR-AC 已新增 PredictionEngine/ShadowPlayer/SimulationCheck/TransactionTracker 之后** 的最新状态；早前对比文档部分结论已过时。

---

## 1. 一句话结论

- **整体强度**：Grim 更强（约 150 项检测 vs 约 20 项；移动类用"物理重演证明合法"的预测范式，覆盖与鲁棒性全面占优）。
- **误判控制**：Grim 明显更少（Timer/Blink/Velocity/NoSlow 全部绑定 transaction 往返的客户端时间轴，高 ping/抖动不产生假阳性；YCBR 仍以 wall-clock 与经验公式为主）。
- **YCBR 唯一占优领域**：战斗行为检测（KillAura / AutoClick / Velocity 细分指纹）。

## 2. 重要前提：YCBR-AC 当前状态（2026-08-13）

| 组件 | 状态 | 证据 |
|------|------|------|
| `simulation/PredictionEngine` | ✅ 已实现，严格转写 1.8.8 物理（重力/拖拽/液体/蛛网/梯子/跳跃/疾跑冲量） | 源码常量表齐全 |
| `simulation/ShadowPlayer` | ✅ 已实现，每玩家影子状态，只信服务器 onGround | `sync()` 双 onGround 签名 |
| `check/movement/SimulationCheck` | ⚠️ 已实现但 **默认关闭**（sim-speed/sim-fly），容差 0.01/0.02 | config.yml `enabled: false` |
| `core/TransactionTracker` | ⚠️ 已实现（RTT EMA + clientTicksAhead）但 **零调用** | 全项目 grep `rttMs()/clientTicksAhead` 仅命中自身 |
| `Timer/Blink/Velocity` | ❌ 仍是 wall-clock / ping 估算 | `TimerCheck` 用 `System.currentTimeMillis()` 窗口 |

> 结论：预测引擎与事务追踪已铺好地基，但生产检测链路尚未切换到新架构 —— 这是本次改进的首要任务。

---

## 3. 相同检测项逐项评估矩阵

图例：🟦 = Grim 占优　🟧 = YCBR 占优　⬜ = 互有胜负（检测面 / 误判面分开看）

| 检测项 | 检测能力 | 误判控制 | 关键差异 |
|--------|:--------:|:--------:|----------|
| Speed 移动速度 | 🟦 | 🟦 | Grim 穷举合法输入重演（阈值 0.001）；YCBR 经验公式 `0.36×0.985^airTicks` + 魔法数加成 |
| Fly 飞行 | 🟦 | 🟦 | Grim 逐 tick offset + FlightA 协议层；YCBR 悬停 >8 ticks 统计 |
| NoFall 摔落 | 🟦 | 🟦 | Grim 引擎算真实 onGround 对比声明，可无声改写包；YCBR 启发式（`airTicks>8 && motionY<-0.4`） |
| NoSlow 减速 | 🟦 | 🟦 | Grim 把"使用物品 ×0.6"建模为预测候选分支；YCBR 经验衰减 + 手工豁免列举 |
| Timer 变速 | 🟦 | 🟦 | Grim 绑定玩家 transaction 往返做时钟余额（低 ping 抓 1.01×，ping 波动不误判）；YCBR 用 wall-clock EPS 窗口 |
| Sprint 疾跑 | 🟦 | 🟦 | Grim SprintA~G 七个细分；YCBR 仅抓 <20ms flip spam |
| Reach 攻击距离 | 🟦 | 🟦 | Grim 枚举当前/上一/上上帧视角射线 + 实体插值碰撞盒 + 攻击范围属性（阈值 0.0005）；YCBR 单帧 + 两档眼高 |
| Blink 协议 | 🟦 | 🟦 | Grim TransactionOrder/PacketOrder 校验到达性；YCBR 仅测沉默时长（2s） |
| Scaffold 搭路 | ⬜ | 🟦 | Grim 协议/几何 ulp 容差，误判极少；YCBR 行为级 Cadence/Colinear/Grid45 抓 bot 更强但规整真人可能误判 |
| KillAura 自瞄 | 🟧 | 🟧 | YCBR 16 个子检测（GCD/ConstStep/AxisAsym/Modulo360/AimStep/Interval/NoSwing/MultiInteract/SelfInteract/AutoBlock/Post/InventoryCombo）；Grim 仅 AimDuplicateLook/AimModulo360/AimProcessor，无完整 KA |
| AutoClick 自动点击 | 🟧 | 🟧 | YCBR FastClick burst + CPS + 间隔 CV<0.1；Grim 缺位 |
| Velocity 击退（检测面） | 🟧 | — | YCBR 8 子类含 JumpReset/SprintReset（Grim 没有的高级指纹） |
| Velocity 击退（误判面） | — | 🟦 | Grim transaction 三明治精确判定 KB 到达时刻；YCBR 用 `pingTicks=ceil(ping/50)` 估算，高 ping 偏 |

---

## 4. 改进路线（按优先级）

### P0：接入 TransactionTracker，改造 TimerCheck（消除最大误判源）

**现状**：`TransactionTracker` 已实现但未被任何检测调用；`TimerCheck` 仍用 `System.currentTimeMillis()` 的 6s/2s/500ms 窗口统计 EPS —— 高 ping、网络抖动会制造假 EPS 波动，是误判主要来源。

**目标**：把 Timer 从"服务器 wall-clock"切换到"客户端事务时间轴"。

**做法**（详见 `docs/plans/2026-08-13-phase02-protocol-transaction.md` 任务 1）：
1. `TimerCheck` 每收到移动包时，以 `TransactionTracker.clientTicksAhead()` / 最近 pong 间隔估算客户端侧 tick 进度，累加"余额"（每移动包 +50ms，超时即 flag），取代 EPS 窗口。
2. 高 ping 玩家自然获得更多余额，ping 波动只影响 pong 间隔，不影响判定正确性。
3. 保留现有 burst 检测作为辅助（抓瞬时加速），但降低其权重或仅在高 TPS 时启用。

**验收**：低 ping 能抓 1.01× Timer；高 ping 从 0→2000ms 跳变不误判。

### P1：启用并打磨 SimulationCheck（移动类升级主线）

**现状**：预测引擎与 ShadowPlayer 已就绪，但 `SimulationCheck` 默认关闭，且只用**模长匹配**（`Math.hypot(deltaX, deltaZ)`），方向无关。

**改进点**：
1. **方向 + 模长联合匹配**：当前只比较水平距离模长，斜向加速/方向伪造可绕过；改为对候选向量与实测向量求夹角 + 模长双条件。
2. **onGround 重同步**：当前 `resyncShadow` 用 `|ΔY| < 0.001` 推断服务器地面状态，不如 Grim 用服务器碰撞盒判定精确；改为优先使用服务器实体位置 / 方块查询判定。
3. **候选集扩展**：目前候选只有 `{idle, walk, sprint, sneak} × {跳, 不跳}` 且 strafe=0；补上 strafe 输入维度可消除侧移误判（模长匹配已部分缓解，方向匹配后必须补）。
4. **容差策略**：液体/网/梯子已放大 2 倍、多 tick 已 `×√ticks`，方向匹配后建议对高 ping（ticks≥3）再放宽 `hTol`，避免误判。

**验收**：开启后 Speed/Fly 场景下与旧 SpeedCheck 交叉验证；目标是在不误判正常玩家（含跳跃、斜跑、药水、冰面）的前提下抓到旧检测漏掉的中低档加速。

### P1：Reach 精度升级（缩小与 Grim 的差距）

**现状**：YCBR 用上一帧位置 + 两档眼高（1.62/1.54）+ ping 补偿外推；Grim 枚举多帧视角 + 实体插值碰撞盒 + 属性范围。

**做法**：
1. 引入实体位置插值（上一帧与当前帧之间按 tick 插值），替代单一上一帧位置。
2. 枚举当前/上一/上上帧 yaw·pitch 组合的射线求交，消除"差一帧"误判。
3. 读取实体 `ENTITY_INTERACTION_RANGE` 属性（若有），替代硬编码 3.1。
4. 保留 `ThroughWalls` 体素采样（Grim 无此能力，是差异化加分项）。

### P2：Velocity 护城河 —— 保留细分指纹 + 补 transaction 到达判定

**现状**：YCBR 的 `JumpReset`/`SprintReset`/`HorizontalPrecise`（带 band 区间）是 Grim 没有的细分指纹；但到达时刻依赖 `pingTicks` 估算。

**做法**：击退包发送时前后各夹一个 transaction（复用 `TransactionTracker`），用 pong 确认 KB 已到达客户端的精确 tick，替换 `pingTicks` 估算；其余细分指纹不动。

### P2：Scaffold 行为检测防误判

**现状**：Cadence/Colinear/Grid45 抓"每 tick 完美搭路"很强，但规整真人可能触发。

**做法**：行为类子检测增加"连续完美放置最低次数 + 方向一致性 + 旋转步长 GCD 校验"三重条件，任一不满足即 drain，降低误判。

---

## 5. 风险与注意

1. **SimulationCheck 开启前必须先跑 ShadowPlayer 重同步测试**：传送/重生/换世界/击退注入/回城必须重同步，否则连续误判。
2. **事务包带宽**：每玩家每 tick 1 个 transaction 包，100 人服务器约 100 包/秒，可接受；但低 TPS 服务器上 `send()` 节流（45ms）会降低 RTT 采样率，注意 `rttMs()` 默认 50ms 的兜底。
3. **GCD 类检测（KillAura）高 ping 误判**：保留 `max-ping` 豁免（现有 150ms），不要在高 ping 场景强制开启 gcdgrid。
4. **1.8 vs 新版本差异**：PredictionEngine 严格按 1.8.8（v1_8_R3）转写；若未来支持 1.9+，end-tick 语义、ViaVersion 转译需单独处理。

---

## 6. 总结

| 维度 | 胜方 | 原因 |
|------|------|------|
| 移动类（Speed/Fly/NoFall/NoSlow/Timer/Sprint） | **Grim** | 物理重演 + transaction 时间轴，能力与误判双胜 |
| 战斗行为类（KillAura/AutoClick） | **YCBR** | Grim 无专门检测，YCBR 行为指纹成体系 |
| Velocity / Scaffold | **互有胜负** | YCBR 细分指纹更强，Grim 误判控制更稳 |
| 整体误判率 | **Grim 更低** | 架构决定：证明合法 vs 刻画异常 |

**对 YCBR-AC 的核心建议**：发挥"战斗行为指纹"护城河，同时把已建好的 TransactionTracker 与 SimulationCheck 真正接入生产链路 —— 这是从"行为统计型"迈向"预测型"反作弊的关键一步，也是缩小与 Grim 差距的最短路径。
