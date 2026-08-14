# Phase 8：Velocity 速度账本 + Passable 多轴序强化（P2-8 / P2-10，学 NCP）

> 日期：2026-08-14
> 依据：`docs/2026-08-14-quad-analysis-grim-mx-ncp.md` §3 P2-8（NCP 速度账本）、P2-10（NCP Passable 多轴序）
> 目标：低成本高回报的账本/几何强化，不与现有事务到达判定冲突，不破坏护城河

## 0. 现状核对

| 项 | 现状 | 本 Phase |
|----|------|----------|
| Velocity 到达判定 | `kbArrivalServerTick` 事务三明治（Phase 2）+ 单 `VelocityState`（pending/expire，只存最后一条） | 账本排队多条，识别"发出但从未消费" |
| Velocity 细分指纹 | JumpReset/SprintReset/Horizontal/Precise/Vertical 等（护城河，不动） | 账本为独立子检测，默认关 |
| Passable/throughwalls | `KillAuraCheck.checkThroughWalls` 统一步长 0.35 点采样（L751-793），斜射擦角可能误判/漏判 | DDA 轴序步进 + 弦长判定（擦角放行） |

## 1. 任务 1（P0）：Velocity 速度账本（学 NCP SimpleAxisVelocity）

### 背景
现有单 `VelocityState` 只保留最后一条击退，且判定基于"位移比例"，对**多条击退排队**
和"玩家位移方向与在途击退完全不匹配（精确抵消绕过）"覆盖不足。NCP 账本思路：把服务端
发出的速度向量入队，按符号/方向匹配消耗，识别"发出但从未消费"的击退。

### 设计（纯逻辑类 `simulation/VelocityLedger.java`，可单测）

- `Entry`：`vx/vz`（水平，账本**只做水平**——垂直已有 Vertical 检测且落地吸收
  场景易误判）、`arrivalTick`（事务推算的到达 tick，`onKbIssued` 已算好）、`consumed`。
- `enqueue(vx, vz, arrivalTick)`：追加条目（同步，监听线程调用）。
- `consume(dx, dz, tick)`：对每个未消费项，若位移方向与该击退方向
  `dot >= direction-dot`（默认 0.6）且位移模长 `>= expected * min-consume-ratio`
  （默认 0.35，expected = |kb| * 0.91^(tick-arrivalTick)）→ 标记 consumed。
  - 玩家正常被击飞：位移沿击退方向且量级接近 → 消费 ✓
  - 绕过者位移 0/反向：不消费 ✓
  - 玩家被墙截断位移：可能不消费 → **必须排墙**（接入点跳过 wall/ceiling）
- `unconsumedCount(tick, windowTicks)`：`tick - arrivalTick > windowTicks` 仍未消费
  的条数（默认 window 12，与 expireAfter 一致）。
- `prune(tick, maxAgeTicks)`：清理过老条目。

### 接线

- `PlayerData` 新增 `public final VelocityLedger velocityLedger = new VelocityLedger();`
- `VelocityCheck.onKbIssued`（L233）：`data.velocityLedger.enqueue(data.velocity.x(),
  data.velocity.z(), data.kbArrivalServerTick);`（复用已算好的到达 tick）
- `VelocityCheck.onMove`：新增 `ledger` 子检测（默认关）：
  - `data.velocityLedger.consume(dx, dz, currentTick)`——位移用
    `m.lastX - m.lastLastX` / `m.lastZ - m.lastLastZ`（与 reversed 判定同源）。
  - 墙/天花板/液体等豁免路径**提前 return 前先不计数**（在 `!wall && !ceiling`
    条件下处理）。
  - `int un = ledger.unconsumedCount(nowTick, windowTicks);` 累积
    `kbLedgerStreak`，`>= ledger.streak`（默认 2）→ bump/flag "LedgerUnconsumed"
    （`velocity.ledger.vl-before-flag` 默认 3）。
  - 说明：被正常击退的玩家一定会在到达后数 tick 内产生沿击退方向的位移并消费；
    未消费条目持续存在本身即异常信号。多条目同时未消费不叠加 VL（只计 streak）。

### 测试（`VelocityLedgerTest`）

1. `enqueueAndConsume_directionMatch_consumes`：入队 (1,0) arrival=10，
   tick=11 位移 (0.8,0) → consumed=true，unconsumedCount=0
2. `zeroMovement_notConsumed`：位移 (0,0) → 不消费 → unconsumedCount=1
3. `oppositeDirection_notConsumed`：位移 (-0.8,0) → 不消费
4. `tooSmallMovement_notConsumed`：位移 (0.1,0)（< expected*0.35）→ 不消费
5. `multiEnqueue_separateEntries`：两条不同方向击退，只消费匹配的一条
6. `unconsumed_afterWindow_counts`：tick 到达 + window+1 仍未消费 → count=1；
   window 内不计数
7. `prune_removesOldEntries`

### 验收

- 全部新测试通过；默认关 → 生产零行为变化（回归安全）。
- 不触碰 JumpReset/SprintReset/Vertical 现有逻辑。

## 2. 任务 2（P0）：Passable 多轴序强化（学 NCP Passable.java）

### 背景
现有 throughwalls 用 `Math.ceil(len/step)` 统一步长点采样：斜射擦过墙角时采样点可能
落入墙内 → 误判合法攻击为穿墙；步长 0.35 斜射又可能跳过薄墙 → 漏判。NCP 用多轴序
DDA 步进"取最宽松"：射线**真正穿过**方块（内部弦长足够长）才算遮挡，擦角放行。

### 设计（纯几何类 `simulation/RayMarchUtil.java` + 函数式 `OcclusionChecker`）

- `interface OcclusionChecker { boolean occluding(int x, int y, int z); }`——测试用
  内存 map，生产用 `NmsUtil.isOccluding || isSolid`。
- `static Result march(OcclusionChecker c, sx, sy, sz, dx, dy, dz, maxLen, minSolidChord)`
  - 标准 DDA voxel traversal（Amanatides & Woo）：沿射线逐格前进，枚举穿过的每个方块
    （tMax 最小轴优先），**不跳格**（解决漏判）。
  - 对每个 occluding 方块：计算射线在该格内的路径长度（弦长 = |tOut - tIn| * |dir|）；
    弦长 > `minSolidChord`（默认 0.25）→ 实挡，记录 `blockedAt`（第一个实挡入口距离）
    并停止；弦长 ≤ 阈值 → 擦角，放行（取最宽松轴序语义）。
  - `Result { boolean blocked; double blockedAt; }`。
- 起点已在 occluding 方块内（玩家站在半砖/楼梯内）：起始格不判（NCP 同款，
  现有实现 k 从 1 起已等价，显式化）。

### 接线

- `KillAuraCheck.checkThroughWalls`（L751-793）：手写采样循环替换为
  `RayMarchUtil.march(checker, ...)`，三段射线逻辑（当前眼高/潜行眼高/上一帧）、
  `blockedAt` 取最松、`minBlockedDistance`、burst 双触发保持**完全不变**。
- 参数：`step`/`max-rays` 由 DDA 取代（自然更细），`min-solid-chord` 新配置
  （`throughwalls.min-solid-chord`，默认 0.25）；`ray-length`/`min-distance` 等不变。

### 测试（`RayMarchUtilTest`，纯几何 + 内存 checker）

1. `openSpace_notBlocked`
2. `thickWall_blockedAtEntry`：射线 (-1,0.5,0.5)→(1,0.5,0.5)，墙在 (0,0,0)，
   blocked=true，blockedAt≈0.5
3. `grazingCorner_shortChord_passed`：射线擦过方块角（弦长 < 0.25）→ 放行
4. `diagonalHole_passed`：斜向穿过多格空隙 → 不挡
5. `thinEdge_diagonal_notBlocked`：斜射穿过方块边角
6. `nonOccludingMaterial_passed`：checker 返回 false → 不挡
7. `startInsideOccluding_skipStartCell`：起点在方块内 → 起始格不判

### 验收

- 新测试全绿；现有 KillAura 相关测试不受影响。
- 默认参数下对正射厚墙行为与旧实现一致（blockedAt 偏差 ≤ 步长）。

## 3. 任务 3（收尾）：文档 + 回归 + 提交

- Phase 8 计划文档回填实施结果。
- 蓝图/quad 路线图勾选：P2-8 ✅、P2-10 ✅。
- 全量 `mvn test`（预期 ~113 + 13 ≈ 126）→ `mvn -DskipTests package` → 提交
  （任务 1、任务 2、任务 3 各自独立提交）。

## 4. 风险与决策记录

- **账本默认关**：新检测一律默认关（与项目"默认关 → 观察 → 开启"哲学一致），
  不改变现有 Velocity 行为。
- **账本只做水平**：垂直落地吸收场景天然无法区分"吸收"与"绕过"，交给现有
  Vertical 检测；水平方向位移与击退方向强相关，账本语义清晰。
- **排墙**：被墙截断的位移不消费条目会导致误判，接入点仅在无墙/无天花板时计数。
- **弦长阈值 0.25**：半砖/台阶 occluding=false 天然放行；擦角弦长通常 < 0.1，
  阈值只影响"恰好斜穿厚墙边角"的极端场景，误判风险低。
- **DDA 成本**：每射线逐格步进 ≤ 穿过格数（≤ maxLen+1），与旧采样量级相当；
  三段射线保留。

## 5. 验收标准

- 账本：`VelocityLedgerTest` 7 用例通过；生产默认关零行为变化。
- 射线：`RayMarchUtilTest` 7 用例通过；throughwalls 正射行为等价。
- 全量回归 ≥ 现有 113 用例不破坏；提交 3 个（t1/t2/t3）。

---

## 6. 实施结果（2026-08-14）

| 任务 | 结果 | 提交 |
|------|------|------|
| t1 账本 | ✅ 完成。`VelocityLedgerTest` **9 用例**全绿（含窗口内不计数、`isAllConsumed` 断言）；`simulation/VelocityLedger.java` 实现（`HORIZONTAL_DECAY=0.91`、`DIRECTION_DOT=0.6`、`MIN_CONSUME_RATIO=0.35`）。接线：`PlayerData.velocityLedger`/`kbLedgerStreak`、`VelocityCheck.onKbIssued` 入队、`onMove` 新 `ledger` 子检测（**默认关**，仅 `!wall && !ceiling` 计数，streak=2、window=12、vl-before-flag=3，prune 30 tick）；config `velocity.ledger.enabled: false` | `36c1de6` |
| t2 射线 | ✅ 完成。`RayMarchUtilTest` **7 用例**全绿（开放/厚墙 blockedAt=1.0/擦角短弦长/斜向空隙/非遮挡材质/起点格跳过/超距不判）；`simulation/RayMarchUtil.java` DDA voxel traversal（tMax 最小轴优先、不跳格、弦长 > 0.25 实挡、起始格不判）。接线：`KillAuraCheck.checkThroughWalls` 手写 0.35 步长采样循环（L751-793）替换为三段 `march(checker,...)`，`OcclusionChecker` 生产实现 = `NmsUtil.isOccluding || isSolid`；`step`/`max-rays` 配置移除，新增 `throughwalls.min-solid-chord`（0.25）；三段射线/burst/`minBlockedDistance` 保持不变 | `45e7c99` |
| t3 收尾 | 本回填 + 全量回归 **129/129 通过** + jar 打包 | 进行中 |

### 实测偏差记录

- **设计修正 1（测试）**：`unconsumed_afterWindow_counts` 原设计"窗口内计数"错误——
  窗口语义是 `tick - arrivalTick > windowTicks` 才计，窗口内不计数（`isAllConsumed` 仍 false）。
- **设计修正 2（测试）**：`diagonalHole` 原墙位 (2,0,2) 恰在 45° 射线上（弦长 1.414 实挡），
  墙改至 (2,2,2)（y 层外）验证"穿过空隙不挡"。
- **lambda 约束**：Java 8 lambda 引用的 `world` 非 effectively final → 引入 `final World fWorld` 副本。
- **擦角几何**：弦长 < 0.25 需射线几乎沿格面（如 x 微斜 + 起点贴近边界），
  验证用例 `grazingCorner_shortChord_passed`（弦长 ~0.0025 放行）后补 `thickWall`（1.0 实挡）对照。

### 待部署

- 账本/射线均在默认参数下零行为变化（账本 `enabled: false`；射线参数与旧采样等效），
  无部署风险；账本可在服务器观察期后按 SOP 开启。