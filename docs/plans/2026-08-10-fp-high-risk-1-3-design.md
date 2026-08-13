# 高危误报修复（珍珠豁免 / Cps 放宽 / Angle 3D 夹角）设计说明

## 背景与目标

用户实测（xiaoye_1，床战模拟）暴露三大高危误报源，均属合法日常玩法必炸：
1. **末影珍珠传送**：珍珠上塔/落地搭路 → 单 tick 位移突变 → Speed/Rise/MovePlace 连锁误报，多名检查同时累计 vl，封禁秒到
2. **攻击 Cps 12 上限**：1.8.9 无点击限制，jitter 手速玩家 14-20cps 常见，持续 1 秒即超
3. **Angle pitch 60° 硬上限**：塔下打塔上敌人 pitch 天然 60-80°，必报

成功标准：上述三场景实测零误报；宏类作弊（按键宏点击、killaura 视线背离）仍可检测。

## 现状与约束

- `MovementTracker.handle()`：按包计算 motionY/distanceXZ，无传送识别（MovementTracker.java:32）
- `KillAuraCheck.checkCps()`：window-ms 1000 + max-cps 12 单窗口（KillAuraCheck.java:143）
- `KillAuraCheck.checkAngle()`：yaw 70° 与 pitch 60° 双独立阈值（KillAuraCheck.java:107）
- 约束：1.8.9 运行时；改动最小化；config.yml 可调

## 方案对比

### 珍珠豁免
- 方案 A（PlayerTeleportEvent 打标跳过）：语义精确，但只覆盖珍珠/命令，活塞等意外突变漏网，多事件链路
- 方案 B（MovementTracker 突变自愈，**推荐**）：`handle()` 检测单 tick 位移突变（水平 |dx|>3.0 或 |dz|>3.0 或垂直 |dy|>2.5）→ 重置 `initialized=false`，下一包重新初始化基准 → 所有检查自动跳过 1 tick + `VelocityState.expire()`。零事件依赖，一处改全军豁免

### Cps 放宽
- 方案 A（单窗口 22）：顶层手速放行，但宏 22 也放行
- 方案 B（单窗 20 + 连续 2 窗口，**推荐**）：人类 2 秒持续 20cps 几乎不可能，宏轻松维持；`cpsStreak` 计数，≥2 才 bump，回落清零

### Angle
- 方案 A（pitch 按距离分级补偿）：仍绕，需反复调参
- 方案 B（3D 视线夹角取代双阈值，**推荐**）：视线向量（yaw/pitch 构造）与"玩家→目标中心"向量的 3D 夹角 `acos(dot)`；打高处目标视线精确对准 → 夹角小；killaura 视线背离 → 夹角大。yaw 检查一并并入，统一阈值

## 推荐方案详细设计

### 1. MovementTracker 传送自愈（MovementTracker.handle 开头，initialized 分支之前）
```java
if (initialized) {
    if (Math.abs(x - lastX) > 3.0D || Math.abs(z - lastZ) > 3.0D || Math.abs(y - lastY) > 2.5D) {
        lastX = x; lastY = y; lastZ = z;
        initialized = false;
        return; // 检查自然跳过 1 tick；VelocityState expire 由外部处理
    }
}
```
- VelocityState 清理：在 AsyncPacketListener 移动包处理处（MovementTracker.handle 后）检测 `!initialized` 且此前 initialized → `velocity.expire()`。或最简：MovementTracker 突变后各检查 1 tick 内不 pk（initialized=false → 全部 `if (!data.movement.initialized) return;`，已覆盖除 VelocityCheck 外的全部；VelocityCheck 的 `!vs.pending()` 需显式 expire）
- 阈值依据：疾跑 0.281 块/tick 的 10 倍有余；珍珠水平最小位移 ≈3 块（玩家站珍珠旁落地）
- 跨世界/命令 tp/末地传送门同样豁免（位移巨大）

### 2. Cps（KillAuraCheck.checkCps）
- config：`cps.max-cps: 12→20`、新增 `cps.required-consecutive-windows: 2`
- PlayerData 加 `volatile int cpsStreak`
- 逻辑：`cps > maxCps ? (++streak >= need ? bump + streak=0 : drain) : streak=0 + drain`

### 3. Angle 3D 夹角（KillAuraCheck.checkAngle 重写）
- 视线向量 a：`yaw → (sin∠, -cos∠)` 水平 + pitch 垂直分量（MathUtil 新增 `directionVector(yaw, pitch)`），或就地计算
- 目标向量 b：玩家位置 → 目标中心
- `angle3D = acos(clamp(dot / (|a||b|)))`，取 EYE_STANDING/EYE_SNEAKING 双候选 min（与 reach 一致）
- 阈值：`angle.max-angle-3d: 75°`（config 替换 max-pitch-difference，保留 max-angle 兼容或删除）
- 删除旧 pitch 独立阈值分支

## 异常与边界处理

- 珍珠落地瞬间已有布置：落地后 1 tick 内放置 → MovePlace 被 initialized=false 跳过 ✓
- 高 TPS 突变误判：物理速限内不可能 >3.0/tick（TNT 爆炸击退水平 <1.0、活塞推 <1）——**TNT 击退**水平速度可达 ~0.7-1.5 块/tick？1.8.9 TNT 爆炸水平推 3+ 格（多 TNT 叠爆速度 2+ 块/tick 极限）→ 阈值 3.0 安全（测试场景无 TNT 就绪；记录为风险项）
- 快照/hasRotation 不变
- Cps streak 在窗口回落即清，防慢热累计

## 测试策略

- mvn package 构建
- 服务器实测：珍珠上塔（Speed/Rise 静默）、珍珠落地搭路（MovePlace 静默）、塔下打塔上敌人（Angle 静默）、jitter 16-20cps 连点（Cps 静默）
- 回归：击杀宏 20cps 2 秒 → Cps flag；killaura 背对打 → Angle 3D flag

## 风险与待确认项

- TNT 多连爆极限推速 >3.0/tick：理论可豁免，后续若服内有 TNT 大炮玩法再调（记录，不修）
- max-angle 70 保留合并到 3D 后旧配置键失效：config.yml 同步删除 max-pitch-difference