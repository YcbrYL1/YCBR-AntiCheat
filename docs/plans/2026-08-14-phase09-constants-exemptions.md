# Phase 9：PhysicsConstants / KnownExemptions 集中化（蓝图重构项）

> 日期：2026-08-14
> 依据：`docs/2026-08-13-modification-plan-vs-8ac.md` §7（自适应新介质策略）
> 目标：纯重构，零行为变化。物理常量集中到一处防漂移；已知合法豁免集中成注册表 + 版本注释，便于维护与新豁免扩展。

## 1. 现状核对（摸底结论）

- 引擎常量已集中在 `PredictionEngine.java:34-74`（20 个 public static final），但存在 **4 处重复**：
  - `WorldProbe.Surface` 摩擦表（0.6/0.98/0.8/0.4/0.91，AIR 0.91 与 `AIR_FRICTION` 重复）
  - `VelocityLedger.HORIZONTAL_DECAY = 0.91`（与 `AIR_FRICTION` 重复）
  - `VelocityState` 内联 `0.91/0.98/0.08/0.02`（`verticalAt`/`horizontal`）
  - `MainThreadHandler.wallDistance` 步长 0.05 / 上限 0.65（与 `WALL_TRUNCATION_LIMIT` 呼应，魔法数）
- 内联魔法数：`jumpLevel * 0.1` 引擎内 3 处（:195/:335/:490）+ `ShadowPlayer`；水中贴地疾跑 `0.1` 3 处（:213/:351/:505）；CriticalsCheck/FlyCheck 内联 `0.42/0.98/0.08/0.1`。
- 豁免分散：引擎系豁免在 `SimulationCheck`（液体/网/梯子 ×2、活塞 ×3、多 tick ×√、步进/弹跳放行）+ `WorldProbe` 静态方法（`stepVerticalAllowed`/`slimeBounceAllowed`）；其余 check 内联豁免（Velocity/NoSlow/KillAura 等）不动（行为敏感区）。
- **`0.003`/`3.92` 仓库不存在**——不在范围（不是移动，是新增，不引入）。
- 蓝图落点为 `core/PhysicsConstants.java`；实际引擎域在 `simulation` 包（PredictionEngine/WorldProbe/VelocityLedger 均在），**决策：放 `simulation` 包**更内聚，蓝图为早期规划，以现状为准。

## 2. 任务 1：PhysicsConstants 集中化

新建 `simulation/PhysicsConstants.java`：

- 迁移 `PredictionEngine` 全部 20 个常量（保留 javadoc 出处），`PredictionEngine` 字段改为委托引用（`= PhysicsConstants.GRAVITY`），外部 `PredictionEngine.GRAVITY` 引用不受影响（API 兼容）。
- 新增常量（收拢内联魔法数）：
  - `JUMP_POTION_PER_LEVEL = 0.1`（跳跃药水每级 +0.1，替代引擎 3 处 + ShadowPlayer）
  - `LIQUID_GROUND_SPRINT_FACTOR = 0.1`（水中贴地疾跑输入系数，替代 3 处）
  - `SLIPPERINESS_NORMAL = 0.6` / `SLIPPERINESS_ICE = 0.98` / `SLIPPERINESS_SLIME = 0.8` / `SLIPPERINESS_SOUL_SAND = 0.4`（`WorldProbe.Surface` 摩擦表引用；ICE 0.98 与 `VERTICAL_DRAG` 数值相同但语义不同，**独立命名不合并**）
  - `WALL_PROBE_STEP = 0.05`（`MainThreadHandler.wallDistance` 引用；`WALL_TRUNCATION_LIMIT` 迁入本类，PredictionEngine 委托）
- 消重接线（全部值不变，零行为变化）：
  - `WorldProbe.Surface` 枚举构造参数改引用
  - `VelocityLedger.HORIZONTAL_DECAY = PhysicsConstants.AIR_FRICTION`
  - `VelocityState` 内联 0.91/0.98/0.08/0.02 → 引用
  - `MainThreadHandler` 步长/上限 → 引用
  - `CriticalsCheck`/`FlyCheck` 内联 0.42/0.98/0.08/0.1 → 引用（值不变）
  - `ShadowPlayer` 跳跃药水 0.1 → 引用
- **不动**：`SpeedCheck`（@Deprecated 经验类，经验值 0.29/0.36/0.985 是经验拟合非 1.8.8 物理常量，保持冻结）；`MovementTracker` tick 常量（方块状态持续 tick，非物理常量）。

## 3. 任务 2：KnownExemptions 集中化（注册表 + 版本注释）

新建 `simulation/KnownExemptions.java`：

- 注册表思想：每条豁免 = { type, mc 版本, 描述, 判定 lambda }，集中文档化。
- 门面静态方法（**判定逻辑原样迁移，行为不变**）：
  - `stepVerticalAllowed(dy, onStepTerrain)`（自 WorldProbe 迁移，≤0.6 步高）
  - `slimeBounceAllowed(dy, onSlime)`（≤0.65 弹跳包络）
  - `mediumToleranceMultiplier(probe)`：液体/网/梯子 → ×2.0（SimulationCheck 容差豁免）
  - `pistonToleranceMultiplier(probe)`：活塞 → ×3.0
  - `multiTickSqrtFactor(ticks)`：多 tick → √ticks
- 接线：`WorldProbe.stepVerticalAllowed`/`slimeBounceAllowed` 改为委托 KnownExemptions（保留签名防外部破坏）；`SimulationCheck` 三个容差豁免改调 KnownExemptions。
- 新增 `KnownExemptionsTest`（边界单测）：0.6/0.65 阈值两侧、活塞/液体介质返回倍率、多 tick √。
- **不动**：VelocityCheck 的 wall/ceiling、NoSlowCheck 介质、KillAura 战斗豁免等——行为敏感，仅在本类 javadoc 中登记清单（指引扩展点）。

## 4. 任务 3：收尾

- 全量 `mvn test`（预期 **129 + KnownExemptionsTest ≈ 133** 全绿，零破坏）。
- 蓝图勾选：`docs/2026-08-13-modification-plan-vs-8ac.md` §7.1/7.2 状态 ✅。
- 本计划回填实施结果；`mvn -DskipTests package`。
- 提交 3 个（t1 常量 / t2 豁免 / t3 文档）。

## 5. 验收标准

- 常量：全仓无未集中物理魔法数残留（grep 抽查 0.42/0.98/0.08/0.91 仅出现在常量类/测试）。
- 豁免：引擎系豁免全部经 KnownExemptions，注册表每项带版本注释。
- 回归：全量测试通过，生产零行为变化（重构不改任何值）。
