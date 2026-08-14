# YCBR-AC 修改计划：基于 8 款反作弊横向对比

> 分析对象：Grim 2.0、NCP、MX、ACA、ACR、Matrix、TaKa AC + YCBR-AC 自身源码
> 分析日期：2026-08-13
> 核心目标：在相同检测项上追平最强对手的检测能力，同时保持 YCBR 的差异化优势

---

## 0. 现状基线（基于 2026-08-13 源码实测）

### 0.1 已实现但未接入的组件

| 组件 | 状态 | 问题 |
|------|------|------|
| `simulation/PredictionEngine` | ✅ 已实现，严格转写 1.8.8 物理（重力/拖拽/液体/蛛网/梯子/跳跃/疾跑冲量） | 候选集仅 {idle,walk,sprint,sneak}×{跳,不跳}，strafe=0；无方向匹配（仅模长） |
| `simulation/ShadowPlayer` | ✅ 已实现（sync 双 onGround 签名） | 已在 SimulationCheck 使用 |
| `simulation/WorldProbe` | ✅ 已实现（数据适配层，从 PlayerData 已探测字段构建） | 方块探测职责在 MainThreadHandler，设计合理 |
| `check/movement/SimulationCheck` | ⚠️ 已实现但**默认关闭**（sim-speed/sim-fly） | 容差 0.01/0.02，液体/网/梯子放大 2 倍 |
| `core/TransactionTracker` | ⚠️ 已实现（RTT EMA + clientTicksAhead）但**零调用** | 全项目 grep `rttMs()/clientTicksAhead` 仅命中自身 |
| `util/Statistics` | ✅ 已实现 + 测试（熵/峰度/IQR/KS/Z-score/Jiff） | 战斗统计层基础，但无人调用 |
| `core/SensitivityProcessor` | ✅ 已实现 + 测试 | 战斗统计层基础，但无人调用 |
| 惩罚框架（blockAttacks/setback/crossSignals） | ✅ 已在 `Check.java` 基类实现 | 攻击阻断已在 `CheckRegistry.onAttack` 第 99 行生效；crossSignals 未实际使用 |

### 0.2 仍为弱点的检测

| 检测 | 现状 | 核心问题 | 威胁等级 |
|------|------|----------|:--------:|
| Timer | wall-clock EPS（6s/2s/500ms 窗口） | 网络抖动/丢包制造假 EPS 波动 | 🔴 |
| Blink | 沉默时长（`silence > 2000+ping`） | 改进版 Blink（囤包 + 保活 Pong）极易绕过 | 🔴 |
| Velocity | `pingTicks=ceil(ping/50)` 估算到达窗口 | 高 ping/抖动偏，ycbr 4 种 Velocity 模式利用此弱点 | 🔴 |
| Speed | 经验公式 `ground.limit=0.29` + `air.momentum=0.985^t` | 新介质/版本/模组可能失准 | 🟡 |
| Fly | 悬停 >8 ticks 统计 + Rise | 瞬停瞬飞 bypass | 🟡 |
| NoSlow | 经验衰减 + 手工豁免列举 | 易漏新减速源 | 🟡 |
| Sprint | 仅抓 <20ms flip spam | "非法保持疾跑"无能为力 | 🟡 |
| Reach | 上一帧位置 + 两档眼高 + ping 外推 | 差一帧误判，精度不及 Grim | 🟡 |
| KillAura | 16 子启发式强但无统计层 | 无熵/KS/IQR/灵敏度校准，深度不及 MX | 🟡 |
| AutoClick | cps+burst+CV 强但无峰度/熵 | 高级点击器（规律但 cps 不高）可能漏 | 🟢 |
| Scaffold | 行为级丰富但规整真人可能误判 | 无批处理统计 | 🟢 |

---

## 1. 总体策略：四层递进

| 层 | 借鉴对象 | 借鉴什么 | 优先级 |
|----|----------|----------|:------:|
| **P0 基础设施接入** | — | 把已实现的 TransactionTracker/SensitivityProcessor/Statistics 接入生产链路 | **P0（立即）** |
| **P1 协议事务化** | Grim（事务时间轴）+ NCP（TeleportQueue ACK） | Timer/Blink/Velocity 从 wall-clock 切换到事务往返 | **P1（最高）** |
| **P2 移动补全** | Grim（方向匹配 + strafe 候选）+ NCP（Magic 包络 + 缓冲） | SimulationCheck 补方向匹配、候选扩展、收紧容差后开启 | **P2（高）** |
| **P3 战斗增强** | MX（统计层 + 灵敏度校准）+ ACA（批处理统计 + 旋转指纹） | KillAura 加统计层、FastClick 加峰度/熵、Scaffold 加批处理 | **P3（中）** |

---

## 2. P0 — 基础设施接入（立即执行，0 新代码，只改调用）

### P0.1 TransactionTracker 接入检测链路

**现状**：`TransactionTracker` 已实现（RTT EMA + clientTicksAhead），但 `TimerCheck`/`BlinkCheck`/`VelocityCheck` 全都未调用它。

**改动**：

#### 文件：`TimerCheck.java`
```java
// 当前：wall-clock EPS
double eps = data.moveTimes.size() / (WINDOW_MS / 1000.0D);

// 改为：优先使用事务往返时间轴
TransactionTracker tx = data.transaction;
if (tx != null) {
    // 用 clientTicksAhead() 替代 wall-clock 窗口
    long clientTicks = tx.clientTicksAhead();
    // 每移动包 +50ms 余额，超 clientTicks 推定时间才 flag
    // 保留 burst 检测作为辅助
} else {
    // 回退到 wall-clock
}
```

**具体实现方案**：
1. 新增 `core/TimerLogic.java`：借鉴 Grim `Timer` 的余额模型
   - 每收到移动包：`balance += 50`（期望 1 tick = 50ms）
   - 每 tick（通过 TransactionTracker 知晓）：`balance -= 50`（实际消耗 1 tick）
   - `balance > threshold` → flag（加速）
   - `balance < -threshold` → flag（减速/冻结）
   - 用 `TransactionTracker.rttMs()` 校准余额基线（高 ping 玩家自然获得更多余额）
2. 保留现有 burst 检测（500ms 窗口）作为辅助，但降低权重

#### 文件：`BlinkCheck.java`
```java
// 当前：silence > max-silence-ms(2000)+ping
long silence = now - data.lastPositionMillis;
long maxSilence = si("max-silence-ms", 2000, 1500) + data.ping;

// 改为：pong 活跃检测 + 位置包序号连续性
// 1. 有 pong 但无位置包超过 N tick → Blink（核心）
// 2. 位置包序号不连续（跳号/重放）→ Blink（辅助）
// 3. 沉默时长作为兜底（辅助）
TransactionTracker tx = data.transaction;
if (tx != null) {
    long pongAge = now - tx.lastPongTime();
    // 有 pong 证明玩家在线，但无位置包 → 囤包
    if (pongAge < 2000 && silence > sd("pong-silence-ms", 500, 300) + data.ping) {
        // flag: 有 pong 无位置包
    }
}
```

**具体实现方案**：
1. 核心判定：`lastPongTime()` 距今 < 2000ms（玩家在线）但 `lastPositionMillis` 距今 > 500+ping ms（无位置包）→ 囤包 Blink
2. 辅助判定：新增 `AsyncPacketListener` 记录位置包到达的序号/时间戳，检测是否跳号或重放
3. 保留沉默时长（`max-silence-ms`）作为纯离线/低带宽兜底

#### 文件：`VelocityCheck.java`
```java
// 当前：pingTicks = ceil(ping/50) 估算
int pingTicks = (int) Math.ceil(cfg.ping(ctx.data) / 50.0D);

// 改为：transaction 三明治精确判定 KB 到达时刻
// 发送击退包前后各夹一个 transaction，用 pong 确认 KB 已到达客户端的精确 tick
TransactionTracker tx = data.transaction;
if (tx != null) {
    // 用 tx.rttMs() 估算 KB 到达的 tick 窗口
    // 保留 JumpReset/SprintReset 细分（不动）
}
```

**具体实现方案**：
1. 在 `AsyncPacketListener` 中，当服务器发出击退包时，前后各夹一个 transaction（发送时间戳）
2. 等客户端 pong 回这两个 transaction，精确计算 KB 到达客户端的 tick
3. 替换 `pingTicks` 估算，保留 `JumpReset`/`SprintReset`/`HorizontalPrecise` 全部细分

### P0.2 SensitivityProcessor 接入战斗检测

**现状**：`SensitivityProcessor` 已实现 + 测试，但 `KillAuraCheck` 未调用。

**改动**：在 `KillAuraCheck` 的 GCD 类子检测（`GcdStable`/`GcdGrid`）中，只在 `SensitivityProcessor.calculateSensitivity()` 返回 `[20,150]` 有效区间时执行。非 GCD 类子检测（`Interval`/`Switch`/`MultiTarget`）不受影响。

### P0.3 Statistics 工具库接入战斗检测

**现状**：`Statistics` 已实现 + 测试（熵/峰度/IQR/KS/Z-score/Jiff），但无人调用。

**改动**：新建 `combat/aim/AimStatisticsCheck.java`（见 P3），直接调用 `Statistics` 方法。

---

## 3. P1 — 协议事务化（最高优先级，消除最大误判源）

### P1.1 Timer 改造（借鉴 Grim + NCP）

**目标**：Timer 从 wall-clock EPS 切换到"客户端事务时间轴"。

**改动**：`TimerCheck.java` 改写
- 核心采用"余额模型"（Grim 思路）：
  ```java
  // 每移动包 +50ms
  data.timerBalance += 50;
  // 每 tick（通过 TransactionTracker.clientTicksAhead 推算）
  // 消耗 50ms，或以 rttMs() 校准
  int clientTicks = tx.clientTicksAhead();
  data.timerBalance -= clientTicks * 50;
  // 余额 > threshold → 加速
  // 余额 < -threshold → 减速/冻结
  ```
- 保留 burst 检测（500ms 窗口，`max-burst-eps`）作为辅助，但降低权重，仅在高 TPS 时启用
- 删除 wall-clock 的 6s/2s 窗口 EPS 计算

**验收**：低 ping 能抓 1.01× Timer；高 ping 从 0→2000ms 跳变不误判。

### P1.2 Blink 改造（借鉴 Grim TransactionOrder + NCP TeleportQueue）

**目标**：Blink 从"沉默时长"升级为"pong 活跃检测 + 包序号连续性"。

**改动**：`BlinkCheck.java` 改写
- 核心判定：`lastPongTime()` 距今 < 2000ms 但 `lastPositionMillis` 距今 > 500+ping ms → 囤包
- 辅助判定：新增位置包序号/时间戳连续性检测
- 保留沉默时长（`max-silence-ms`）作为兜底
- 保留冷却时间（`cooldown-ms`）

**验收**：改进版 Blink（囤包 + 保活 Pong）被正确识别。

### P1.3 Velocity 精确化（借鉴 Grim transaction 三明治 + NCP 速度记账）

**目标**：Velocity 从 `pingTicks` 估算升级为 transaction 到达判定。

**改动**：`VelocityCheck.java` 改造
- 在 `AsyncPacketListener` 击退包发送时夹 transaction
- 用 `TransactionTracker.rttMs()` 和 `lastPongTime()` 精确计算 KB 到达 tick
- **务必保留** `JumpReset`/`SprintReset`/`HorizontalPrecise` 全部细分（这是差异化护城河）

**验收**：高 ping 下 Velocity 误判大幅下降，且细分指纹不丢。

---

## 4. P2 — 移动补全（补齐最大短板）

### P2.1 SimulationCheck 方向匹配 + 候选扩展

**现状**：`SimulationCheck` 只做模长匹配（`Math.hypot(actualDX, actualDZ)`），候选集只有 {idle,walk,sprint,sneak}×{不跳,跳}，strafe=0。

**改动**：

#### 文件：`PredictionEngine.java`
- 候选集扩展：增加 strafe 输入维度（`strafe ∈ {-1, 0, 1}`），覆盖"侧移 + 斜跑"合法输入
- 当前候选数：4 speeds × 2 jumps = 8
- 扩展后：4 speeds × 3 strafes × 2 jumps = 24（仍可接受）

#### 文件：`SimulationCheck.java`
- 方向 + 模长联合匹配：不仅比较 `|actual|` 与 `|max|`，还计算候选向量与实测向量的夹角
  ```java
  // 联合匹配：夹角 < 30° 且模长差异 < hTol
  double angle = Math.acos(dot(c.deltaX, c.deltaZ, actualDX, actualDZ)
      / (Math.hypot(c.deltaX, c.deltaZ) * actualH + 1e-10));
  if (angle < 30° * Math.PI / 180 && actualH <= c.h + hTol) {
      hMatch = true;
  }
  ```
- 注：当前模长匹配已抗斜向/侧移误判，方向匹配后需对高 ping（ticks≥3）再放宽 `hTol`，避免误判

### P2.2 容差策略优化

**现状**：容差 `hTol=0.01`/`vTol=0.02`，液体/网/梯子放大 2 倍，多 tick 按 `√ticks` 放大。

**改动**：
- 基准容差不动（0.01/0.02 已接近 Grim 的 0.001-0.01）
- 方向匹配后，对 `ticks≥3` 的高 ping 场景再放宽 `hTol += 0.005 * (ticks-2)`
- 液体/网/梯子的放大倍数从 2 倍改为配置可调（`sd("liquid-tolerance-multiplier", 2.0, 2.0)`）

### P2.3 NoSlow 接入引擎

**现状**：`NoSlowCheck` 仍用经验公式 `expected = lastDistanceXZ * 0.92 + 0.01` + 手工豁免列举。

**改动**：给 `PredictionEngine.predictSingle` 和 `candidates` 增加 `usingItem` 参数（已存在），`SimulationCheck` 在玩家使用物品时调用 `candidates(..., usingItem=true)` 校验，offset 落到 NoSlow。

### P2.4 Sprint 补强（借鉴 Grim SprintA~G）

**现状**：只抓 flip spam。

**改动**：`SprintCheck.java` 增加：
- 饥饿 < 6 时疾跑 → flag（借鉴 Grim SprintA）
- 蹲伏/爬行时疾跑 → flag（SprintB）
- 使用物品时疾跑 → flag（SprintC，与 NoSlow 联动）
- 失明时疾跑 → flag（SprintD）
- 撞墙时疾跑 → flag（SprintE，检测前方 1 格有方块）
- 水中疾跑 → flag（SprintG）

### P2.5 开启 SimulationCheck 并逐步替换经验公式

**改动**：
1. `config.yml` 中 `simulation.sim-speed.enabled` 和 `simulation.sim-fly.enabled` 默认改为 `true`
2. `SpeedCheck`/`FlyCheck`/`NoFallCheck` 标记 `@Deprecated`，保留为冗余兜底 1 个月
3. 实机验证：开启后与旧 SpeedCheck 交叉验证，目标是不误判正常玩家（含跳跃、斜跑、药水、冰面）

---

## 5. P3 — 战斗增强（补齐第二短板）

### P3.1 KillAura 加统计层（借鉴 MX）

**现状**：16 子检测纯启发式，无统计层。

**改动**：新增 `combat/aim/AimStatisticsCheck.java`

```java
// 在现有 GCD 启发式之上，新增统计层：
// 1. Shannon 熵（视角增量分布随机性）
// 2. IQR 四分位距（视角增量离散度）
// 3. KS 检验（视角增量分布是否均匀）
// 4. Z-score 离群值（随机化缺陷检测）
// 5. Jiff 模式（重复微调检测）
// 6. 机械心跳（固定频率旋转）
//
// 多信号交叉：启发式 flag + 统计 flag 同时命中才 punish
// 灵敏度校准：仅在 SensitivityProcessor 有效区间 [20,150] 执行
```

**调用方式**：`KillAuraCheck` 在完成现有 16 子检测后，调用 `AimStatisticsCheck` 的统计结果，只有两类检测同时异常时才 `flag()`。

### P3.2 FastClick 增强（借鉴 MX）

**现状**：cps + burst + CV。

**改动**：`FastClickCheck.java` 增加：
- 峰度（Kurtosis < 0 → 机械规律）
- Shannon 熵（极低熵 → 规律性点击）
- 默认开启，保守阈值

### P3.3 Reach 精度升级（借鉴 Grim + NCP）

**现状**：单帧 + 两档眼高。

**改动**：`ReachCheck.java` 改造：
1. 引入实体位置插值（上一帧与当前帧之间按 tick 插值）
2. 枚举当前/上一/上上帧 yaw·pitch 组合的射线求交
3. 引入 NCP 的 reachMod 动态收缩机制：玩家长期在临界距离攻击时逐步收紧允许距离
4. 保留 ThroughWalls 体素采样（差异化加分项，Grim 无此能力）

### P3.4 Scaffold 加批处理统计（借鉴 ACA）

**现状**：行为级检测（Cadence/Colinear/Grid45），对规整真人可能误判。

**改动**：`ScaffoldCheck.java` 新增批处理统计子模块：
- 收集连续放置的间隔时间
- 计算实际平均延迟 vs 理论最小延迟（直线 238ms / 对角 138ms / 潜行 +90ms）
- 整批平均低于物理下限才 flag（抗单次抖动）
- 与现有行为级检测并行，行为级保留但降低权重

### P3.5 数据集收集管线（可选）

**改动**：新增 `core/DatasetManager.java` + `/ycbr record` 命令，收集合法/作弊视角样本，供未来 ML 训练。

---

## 6. 生产迁移策略

### 6.1 默认关闭 → 观察 → 调参 → 开启

| 改动 | 默认状态 | 观察期 | 目标状态 |
|------|:--------:|:------:|:--------:|
| Timer 余额模型 | 开启（新代码无旧代码） | 1 周 | 稳定后开 |
| Blink pong 检测 | 开启（新代码无旧代码） | 1 周 | 稳定后开 |
| Velocity transaction | 开启（新代码无旧代码） | 1 周 | 稳定后开 |
| SimulationCheck 开启 | 开启 | 2 周 | 全面取代经验公式 |
| Sprint 多细分 | 新子检测默认关 | 2 周 | 逐项开 |
| AimStatisticsCheck | 默认关 | 2 周 | 稳定后开 |
| FastClick 峰度/熵 | 默认关 | 2 周 | 稳定后开 |
| Reach 多帧插值 | 默认关 | 2 周 | 稳定后开 |
| Scaffold 批处理 | 新增默认关 | 2 周 | 稳定后开 |

### 6.2 回归测试

- 扩展现有 `SimulationCheckTest` / `ShadowPlayerTest` / `PredictionEngineTest`
- 新增：方向匹配单测、strafe 候选单测、Timer 余额模型单测
- 新增：Blink pong 检测单测（模拟囤包 + 保活场景）
- 新增：AimStatisticsCheck 统计层单测

### 6.3 风险与注意

1. **Transaction 包带宽**：每玩家每 tick 1 个 transaction 包，100 人 ≈ 100 包/秒，可接受。低 TPS 时 `send()` 节流（45ms）会降低 RTT 采样率，`rttMs()` 默认 50ms 兜底。
2. **SimulationCheck 开启前必须跑重同步测试**：传送/重生/换世界/击退注入/回城必须重同步，否则连续误判。
3. **方向匹配可能误判斜跑/侧移**：建议对 `ticks≥3` 的高 ping 场景放宽角度容差（如 30°→45°）。
4. **GCD 类检测高 ping 误判**：保留 `max-ping` 豁免（现 150ms），不要在高 ping 场景强制开启 gcdgrid。
5. **1.8 vs 新版本**：PredictionEngine 严格按 1.8.8 转写，若未来支持 1.9+ 需处理 end-tick 语义。

---

## 7. 自适应新介质策略（借鉴 NCP Magic 模型 + NCP workaround）

NCP 的 Magic 模型把所有物理常量集中定义，并通过大量 `oddJunction`/`workaround` 处理已知合法异常。YCBR 应借鉴此思路：

### 7.1 物理常量集中化

**现状**：物理常量散布在 `PredictionEngine.java`（常量）、`SimulationCheck.java`（容差）、`SpeedCheck.java`（魔法数）、`WorldProbe.java`（摩擦表）。

**改动**：将所有物理常量集中到 `core/PhysicsConstants.java`：
```java
public final class PhysicsConstants {
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_FRICTION = 0.91;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double BASE_SPEED = 0.1;
    // ... 全部常量
    // 魔法数集合（经验公式/容差/阈值）
    public static final class MagicNumbers {
        public static final double GROUND_SPEED_LIMIT = 0.29;
        public static final double AIR_MOMENTUM_BASE = 0.36;
        public static final double AIR_MOMENTUM_DECAY = 0.985;
        // ...
    }
}
```

### 7.2 已知合法异常豁免系统（借鉴 NCP MagicAir.oddJunction）

**现状**：`SimulationCheck` 对液体/网/梯子统一放大 2 倍容差。

**改动**：新增 `simulation/KnownExemptions.java`，集中处理已知合法异常：
- 重力突变（如活塞推动切换方向）
- 液体过渡（入水/出水该 tick）
- 头部碰撞（跳起撞到半砖/台阶）
- 粘液块弹跳
- 烟花/鞘翅加速
- 1.9+ 新方块/机制

每个豁免附带描述 + 对应 Minecraft 版本，方便后续维护。

---

## 8. 关键文件改动清单

| 文件 | 动作 | Phase | 难度 |
|------|------|:-----:|:----:|
| `check/protocol/TimerCheck.java` | 改写（余额模型） | P1 | 中 |
| `check/protocol/BlinkCheck.java` | 改写（pong 检测 + 包序号） | P1 | 中 |
| `check/movement/VelocityCheck.java` | 改造（transaction 到达判定，保留细分） | P1 | 中 |
| `core/TimerLogic.java` | 新增（余额模型逻辑） | P1 | 中 |
| `check/movement/SimulationCheck.java` | 改造（方向匹配 + 容差策略） | P2 | 中 |
| `simulation/PredictionEngine.java` | 改造（候选扩展 strafe 维度） | P2 | 中 |
| `check/movement/NoSlowCheck.java` | 改造（接入引擎） | P2 | 低 |
| `check/protocol/SprintCheck.java` | 扩展（SprintA~G 多细分） | P2 | 中 |
| `combat/aim/AimStatisticsCheck.java` | 新增 | P3 | 高 |
| `check/combat/KillAuraCheck.java` | 扩展（接入统计层 + 交叉） | P3 | 中 |
| `check/combat/FastClickCheck.java` | 扩展（峰度 + 熵） | P3 | 低 |
| `check/combat/ReachCheck.java` | 改造（多帧插值 + 射线求交 + 动态收缩） | P3 | 高 |
| `check/combat/ScaffoldCheck.java` | 扩展（批处理统计） | P3 | 中 |
| `core/PhysicsConstants.java` | 新增 | P0 | 低 |
| `simulation/KnownExemptions.java` | 新增 | P2 | 低 |
| `core/DatasetManager.java` | 新增（可选） | P3 | 中 |

---

## 9. 优先级建议

- **本周**：P0（接入 TransactionTracker/SensitivityProcessor/Statistics）+ P1（Timer/Blink/Velocity 事务化）
- **本月**：P2（SimulationCheck 方向匹配 + 候选扩展 + Sprint 多细分 + NoSlow 引擎接入）
- **下月**：P3（AimStatisticsCheck + FastClick 增强 + Reach 升级 + Scaffold 批处理）

**一句话**：先接 TransactionTracker 堵 Timer/Blink/ Velocity 三大高危，再开 SimulationCheck 补移动短板，最后上战斗统计层——按此顺序，YCBR-AC 的检测能力与误判控制可追平 NCP 级，在战斗行为领域保持领先。

---

## 10. Phase 5 收敛记录（2026-08-14）

| 蓝图项 | 状态 | 说明 |
|--------|------|------|
| P1 Blink 改写（pong 检测 + 包序号） | ✅ Phase 2 已做 pong 检测；**Phase 5 补重放 burst 确认** | `BlinkLogic`（`cf01afa`）：静默期（活体 pong）+ 突发补发（连续 8 包 ≤25ms）双条件，默认关 |
| P2.1 SimulationCheck 方向匹配 + strafe 候选扩展 | ✅ Phase 5 | `PredictionEngine.candidates` 扩展至 24 候选（4 speeds × 2 jumps × 3 strafe）；方向匹配夹角 ≤30°（ticks≥3 放宽 45°），config `sim-speed.direction-match` 默认关（`7bd0c57`） |
| P3.3 Reach 动态收缩 reachMod | ✅ Phase 5 | `ReachModLogic`：边缘连击 8 次收缩 0.05、上限 0.5、正常攻击 ×0.8 衰减，config `reach.reach-mod` 默认关（`f1e388f`） |
| P3.4 Scaffold 批处理统计 | ✅ 已覆盖 | `cadence` 子项已存在（Phase 1，默认关），批量统计思想已实现 |
| 7.1 PhysicsConstants 物理常量集中化 | ⬜ 剩余 | 纯重构、收益低、改动面大，暂缓 |
| 7.2 KnownExemptions 豁免系统 | 🟡 雏形 | `stepVerticalAllowed`（Phase 4）/`slimeBounceAllowed`（Phase 5）已分散实现，集中化为注册表形式未做 |