# Phase 7：AABB 碰撞截断 + 特殊介质（P0-2，学 Grim 碰撞盒）

> 日期：2026-08-14
> 依据：`docs/2026-08-14-quad-analysis-grim-mx-ncp.md` §3 P0-2；`docs/2026-08-13-modification-plan-vs-8ac.md` P1.1 剩余项
> 目标：补移动类"世界交互层"精度，缩小与 Grim 碰撞盒重演的差距

## 0. 现状核对（先明确已覆盖项）

| 介质/场景 | 现状 | 结论 |
|-----------|------|------|
| 载具（船/矿车） | `data.inVehicle` 主线程探测 + `SimulationCheck.java:33` 跳过 | ✅ 已覆盖，无需改动 |
| 末影珍珠（传送） | shadow resync 直接取客户端实际位置 | ✅ 已覆盖（传送后下一包即重同步） |
| 鞘翅 | 1.8.8 无鞘翅 | ⚪ 不适用 |
| 粘液块弹射 | `WorldProbe.slimeBounceAllowed`（Phase 5） | ✅ 已覆盖 |
| 楼梯/台阶步进 | `WorldProbe.isStepMaterial` + `stepVerticalAllowed` | ✅ 已覆盖 |
| 头顶碰撞（跳跃撞头） | `HEAD_BLOCKED_JUMP_CAP` + `blockBoxedIn` | ✅ 已有简化 |
| 液体/网/梯子 | `LIQUID_*`/`WEB_DAMP`/`LADDER_CLIMB` + 容差 ×2 | ✅ 已有 |
| **活塞推动** | 无探测、无豁免 | ❌ 本 Phase 任务 1 |
| **水平墙碰撞（滑墙）** | 无碰撞盒模拟 | ❌ 本 Phase 任务 2 |

## 1. 任务 1（P0）：活塞推动豁免

### 背景
活塞推方块带动玩家时位移为**外部驱动**（与玩家 yaw/输入无关），且可与玩家输入叠加
（推动 0.1m/tick + 疾跑 0.26m/tick ≈ 0.36m > maxH≈0.33-0.35）→ sim-speed 误判风险；
direction-match 开启时推动方向与输入方向无关 → 必然误判。1.8 中活塞推动期间玩家
脚下的 PISTON_MOVING_PIECE（活塞臂实体）会短暂存在，是可靠指纹。

### 设计
- `PlayerData` 新增 `blockOnPiston`（boolean）：主线程 `snapshotBlockContext` 探测
  `feetMat`/`belowMat` == `Material.PISTON_MOVING_PIECE`。
- `WorldProbe.ProbeResult` 新增 `onPiston` 字段 + `fromPlayerData` 回填。
- `SimulationCheck.onMove`：`probe.onPiston` 时 sim-speed 容差 ×3（推动 0.1m/tick 量级，
  乘 3 即容忍 0.03 → 0.09+叠加量），sim-fly 不受影响；不整 tick 跳过（保留检测能力，
  推动结束后立即恢复正常判定）。

### 测试
- `WorldProbeStepTest`（或新 `WorldProbePistonTest`）：
  - `piston_movingPiece_below_isExempt`（fromPlayerData 后 onPiston=true）
  - `normalSurface_notExempt`

### 接线文件
`MainThreadHandler.java:237-257`、`PlayerData.java`、`WorldProbe.java`、`SimulationCheck.java`

## 2. 任务 2（P1→P0 主工程）：水平墙碰撞截断 + 滑墙

### 背景
Grim 用碰撞盒把合法位移由世界唯一确定。YCBR 当前仅模长上界匹配（位移短不误判），
但两个真实场景未被覆盖：
1. **斜向贴墙跑**：位移被墙截断后沿墙滑动，方向从输入方向偏转 45°→90°，
   direction-match（Phase 5，默认关）开启时会误判；
2. 活塞豁免后玩家被推到墙边时位移同样被墙截断。

### 设计

**a. 墙探测（主线程，3 方向）**
- `MainThreadHandler.snapshotBlockContext` 新增 3 方向墙距采样：
  `wallFwd`（yaw 方向）、`wallLeft`（yaw-90°）、`wallRight`（yaw+90°）。
- 采样：从玩家脚部位置 (px, py, pz) 沿方向步长 0.05 前进，上限 0.70（覆盖 1 tick
  最大位移 + 碰撞盒半宽 0.3）；命中 `isSolid()` 且非可穿过方块（AIR/WATER/LAVA/
  网/梯子等）→ 记录距离（采样距离 - 0.05 为面距离），否则 `Double.POSITIVE_INFINITY`。
- 成本：3 方向 × ≤14 次 getType = ≤42 次/玩家/tick，与现有探测同级，可接受。
- 滞后说明：探测在上一 tick 主线程完成，本 tick 预测使用 → 1 tick（50ms）误差
  （最大位移 ~0.34m）。因此碰撞截断只在 `wallDist < 0.65` 时生效，且截断距离取下限
  0.05，超出范围视为无墙（安全方向：不截断 → 不产生新的误判面）。

**b. 纯函数 `PredictionEngine.applyCollision`**
```
applyCollision(deltaX, deltaZ, yaw, wallFwd, wallLeft, wallRight)
  → 返回截断后的 (dx, dz)
```
- 投影：`fwd = dx*cosYaw + dz*sinYaw`、`right = -dx*sinYaw + dz*cosYaw`
  （与引擎输入公式同款三角函数约定）。
- 逐轴截断（1.8 `Entity.move` 逐轴碰撞语义的 2D 近似）：
  - `fwd > wallFwd` → `fwd = wallFwd`（前方墙，负向不管）
  - `right > wallRight` → `right = wallRight`；`right < -wallLeft` → `right = -wallLeft`
- 反投影回世界坐标。
- 逐轴独立截断即"滑墙"：X 轴先撞墙则 X=0、Z 保留（对角角点近似忽略，可接受）。

**c. 候选接入**
- `PredictionEngine.candidates`/`candidatesMultiTick` 重载增加墙距参数（3 个 double，
  无墙 = POSITIVE_INFINITY）；每生成一个候选位移后套 `applyCollision`。
- 兼容旧签名：新增重载，旧签名内部传 INFINITY（无墙）→ 现有调用零改动。

**d. SimulationCheck 接入**
- `WorldProbe.ProbeResult` 新增 `wallFwd/wallLeft/wallRight` 字段 + `fromPlayerData` 回填
  （`PlayerData` 新增同名 3 字段）。
- `onMove` 传墙距给 candidates。
- 垂直判定（sim-fly）不受影响（墙不影响垂直，headBlocked 已有）。
- **注意**：截断后的候选模长 ≤ 原候选 → sim-speed 上界不变（不会漏检）；
  墙距 0.65 以上的截断被抑制 → 对普通无墙场景零行为变化（回归安全）。

### 测试
- 新 `CollisionLogicTest`（纯函数，无 Bukkit）：
  - `noWalls_unchanged`（INFINITY 三墙 → 原样返回）
  - `forwardWall_truncatesToWall`
  - `rightWall_truncatesRight`
  - `leftWall_truncatesLeft`
  - `diagonal_corner_bothAxesTruncated`（45° 撞墙角，两轴同时截断 → 滑动）
  - `negativeForward_notAffectedByForwardWall`（后退不被前方墙截断）
  - `wallBeyondLimit_ignored`（wallDist ≥ 0.65 → 不截断，模拟滞后安全）
- `PredictionEngineTest` 补充：candidates 带墙距重载冒烟（yaw=0 正前墙 → 候选
  deltaZ ≤ 墙距）。

## 3. 任务 3（收尾）：文档 + 回归 + 提交

- Phase 7 计划文档追加实施结果章节。
- 蓝图收敛记录更新：8AC P1.1 剩余项 → 活塞推动 ✅、墙碰撞 ✅；
  0.03 跳过 tick 仍余（低频，容差兜底）。
- 全量 `mvn test`（预期 ~112 用例）→ `mvn -DskipTests package` → 提交。

## 4. 风险与决策记录

- **逐轴截断近似**：1.8 真碰撞是 AABB 逐轴检测，我们按"位移向量在 2 个垂直轴上独立
  截断"近似，角点处与真实行为有毫厘之差；但截断方向永远偏保守（截更多），
  只会让候选更短 → 不会产生新误判。
- **探测滞后**：墙距来自上一 tick 主线程快照；0.65 生效阈值 + 0.05 下限吸收误差。
- **不做完整碰撞盒重演**：Grim 式全 AABB 模拟需要每个候选做实体级移动+方块采样
  （成本 ~10-20 倍），当前墙距近似已覆盖主要误判面，且保持引擎纯函数可单测。
- 活塞豁免用容差 ×3 而非跳过：保留推动结束后立即恢复正常检测。

## 5. 验收标准

- 全部新测试通过；全量回归不破坏现有 103 用例。
- `applyCollision` 无墙输入返回原值（零行为变化验证）。
- 活塞探测仅认 PISTON_MOVING_PIECE，不干扰现有 surface 判定。
- 提交 2 个代码提交 + 1 个文档提交。

---

## 6. 实施结果（2026-08-14）

### 任务 1：活塞推动豁免 ✅（`08e798d`）

- `PlayerData.blockOnPiston` + `MainThreadHandler.snapshotBlockContext` 探测
  （feet/below == PISTON_MOVING_PIECE）。
- `WorldProbe.ProbeResult.onPiston` + `fromPlayerData` 回填。
- `SimulationCheck`：`sim-speed.piston-tolerance-multiplier`（默认 3.0）容差放大，
  sim-fly 不受影响。
- 测试：`WorldProbeStepTest` +2（`pistonMovingPiece_below_isExempted`、
  `normalSurface_notExempted`）。全量 105/105。

### 任务 2：水平墙碰撞截断 + 滑墙 ✅（`e5ca5b9`）

- `PredictionEngine.applyCollision(dx, dz, yaw, wallFwd, wallLeft, wallRight)`：
  2D 逐轴截断（1.8 Entity.move 逐轴碰撞语义近似），墙距 ≥ `WALL_TRUNCATION_LIMIT`
  (0.65) 视为无墙（吸收主线程探测 1 tick 滞后）。
- `candidates`/`candidatesMultiTick` 新增带墙距重载（旧签名内部传 INFINITY，
  零行为变化）；每候选位移套碰撞截断。
- `PlayerData.wallFwdDist/wallLeftDist/wallRightDist` +
  `MainThreadHandler.wallDistance()`（脚部位置步长 0.05 采样，上限 0.65，
  方向约定与引擎一致 yaw=0→+X）+ `WorldProbe` 墙距字段回填（0=未探测→无墙）。
- `SimulationCheck` 两处候选生成接入墙距。
- 测试：新 `CollisionLogicTest` 8 用例（无墙原样/前墙/右墙/左墙/斜撞墙角双轴/
  后退不受前墙/上限忽略/旋转 yaw）。全量 113/113。
- **偏离记录**：计划原写"0.65 上限 + 0.05 下限"生效条件；实现中负墙距（身后墙
  探测异常）额外加 `>= 0` 守卫，防止误截断。

### 任务 3：收尾 ✅

- 蓝图收敛记录：8AC P1.1 剩余项 → 活塞推动 ✅、墙碰撞 ✅；
  0.03 跳过 tick 仍余（低频，容差兜底，与末影珍珠/载具同为暂缓项）。
- `mvn test` 113/113 → `mvn -DskipTests package` 打包。

### 验收核对

| 标准 | 结果 |
|------|------|
| 全部新测试通过 | ✅ CollisionLogicTest 8 + WorldProbeStepTest 2 |
| 无墙零行为变化 | ✅ applyCollision INFINITY 原样返回（noWalls_unchanged） |
| 活塞探测不干扰 surface | ✅ 独立字段，Surface 判定未动 |
| 提交节奏 | ✅ cdd7991（计划）+ 08e798d（t1）+ e5ca5b9（t2）+ 待定（回填） |