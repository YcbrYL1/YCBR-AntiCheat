# 已有检测差距闭合实施计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 子技能，按任务逐项执行本计划。

**目标：** 对照 Grim-2.0 / NoCheatPlus / AntiCheatReloaded / MX / ACA 的检测实现，逐项强化 YCBR AC 已有 6 类检测（KillAura / Scaffold / Speed / Fly / Velocity / Criticals），补齐参考实现已验证的判据，缩小检测差距。

**架构方案：** 分三个阶段推进——A 阶段为纯包字段校验（铁证型，零误报，无世界依赖，可在异步 actor 线程安全执行）；B 阶段为已有检测的判据强化（多目标换攻、跳跃轨迹验证、竖直物理包络、部分抗击退分层）；C 阶段为算法级改造（GCD 众数估计、Rotation 射线化、Timer 包频、NoFall）。全部延续 PlayerActor 单线程模型与 config.yml 阈值可配模式，验证方式沿用"mvn 构建 + grep 自检 + 部署实测"（本项目无单元测试设施）。

**技术栈：** Java 8、Bukkit 1.8.9/Paper、ProtocolLib 5.0、Maven 3.9.16（`C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd -q -DskipTests package`）

**参考来源：** `Grim-2.0\common\src\main\java\ac\grim\grimac\checks\`、`NoCheatPlus-master\NCPCore\src\main\java\fr\neatmonster\nocheatplus\checks\`、`AntiCheatReloaded-master\src\main\java\com\rammelkast\anticheatreloaded\check\`、`MX-Project-master\src\main\java\kireiko\dev\anticheat\checks\`（详见 `learning\detection-gap-analysis.md`）

---

## 背景（差距结论摘要）

来自 `learning\detection-gap-analysis.md` 与 2026-08-11 源码对比：

1. **Fly 差距最大**：只判 motionY>1.25（Rise）+ 8 tick 悬停（Hover），0.5-1.2 的匀速爬升完全放行；参考实现（NCP SurvivalFly / ACR FlightCheck）都是完整竖直包络（起跳 0.42 衰减曲线 + 重力 0.0624-0.0834）
2. **KillAura 抓不到多目标换攻**：NCP Angle 记录每次攻击（目标/角度/时间）判定 forcefield；YCBR 每次只看单个目标
3. **Criticals 判据无效**：只抓"悬停攻击"（motionY<0.05），LB 等 crit 宏在跳跃顶点攻击（motionY 0.1-0.4）完全抓不到；参考做法是验证跳跃轨迹合法性
4. **Velocity 0.5 比率太宽**：容忍 50% 抗 KB，0.6-0.9 部分抗全放行
5. **GcdStable 语义退化**：`std<0.25` 兜底 = 恒定步长检测（与 ConstStep 冗余），抓不到"网格对齐+小抖动"aimbot；参考为众数估计灵敏度（Grim AimProcessor）
6. **缺铁证型协议校验**：InvalidPlace/FabricatedPlace/SelfInteract/AimModulo360/ExtremeMove 都是几行纯字段校验

---

## 任务拆解

### 阶段 A：铁证型校验（零误报，先落地）

#### 任务 A1：Place 包字段合法性校验（InvalidPlaceA/B + FabricatedPlace）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\packet\AsyncPacketListener.java:135-148`（handleBlockPlace 读取 cursor）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\data\context\PlaceContext.java`（加 cursorX/Y/Z 字段）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\ScaffoldCheck.java`（新增 checkPlaceFields）
- 修改：`YCBR-AC\src\main\resources\config.yml`（scaffold 下加 invalid-place/fabricated-place 键）

**步骤 1：PlaceContext 扩展**

```java
public final double cursorX, cursorY, cursorZ;
// 构造器加 3 参；handleBlockPlace 读取：
// packet.getFloat().read(0/1/2)（1.8 BLOCK_PLACE 的 cursor 是 float 分量，异常时回退 0.5/1.0/0.5 默认值不判）
```

**步骤 2：checkPlaceFields 骨架**（ScaffoldCheck 新增，onPlace 里调用）

```java
private void checkPlaceFields(PlaceContext ctx) {
    PlayerData data = ctx.data;
    double cx = ctx.cursorX, cy = ctx.cursorY, cz = ctx.cursorZ;
    if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz)) {
        if (bump(data, "invalid-place", 1D, i("invalid-place.vl-before-flag", 2))) {
            flag(data, "InvalidPlace", "NaN/Inf cursor");
        }
        return;
    }
    boolean faceOk = ctx.direction >= 0 && ctx.direction <= 5 || ctx.direction == 255;
    if (!faceOk) {
        if (bump(data, "invalid-place", 1D, i("invalid-place.vl-before-flag", 2))) {
            flag(data, "InvalidPlace", "bad face=" + ctx.direction);
        }
        return;
    }
    double eps = 1.0E-7D;
    boolean cursorOk = cx >= -eps && cx <= 1.0D + eps && cz >= -eps && cz <= 1.0D + eps
            && cy >= -eps && cy <= 1.5D + eps;
    if (!cursorOk) {
        if (bump(data, "fabricated", 1D, i("fabricated.vl-before-flag", 2))) {
            flag(data, "FabricatedPlace", "cursor out of bounds");
        }
    }
}
```

**步骤 3：验证**
- 构建：`mvn.cmd -q -DskipTests package`（预期 BUILD SUCCESS，jar 体积变化）
- grep 自检：无 `InvalidPlace`/`fabricated` 拼写残留不一致（config 键与代码一致）
- 部署实测：绿玩正常放置 100 次不触发；伪造 cursor 测试包（若可构造）触发

**风险：** ProtocolLib 1.8 BLOCK_PLACE 的 cursor 读取位置需实测确认（getFloat 或 modifier）；读取失败时回退不判（宁漏勿误）。

---

#### 任务 A2：KillAura 协议校验（SelfInteract + AimModulo360）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\KillAuraCheck.java`（onAttack 头部加 SelfInteract；checkAimStep 内加 AimModulo360）
- 修改：`YCBR-AC\src\main\resources\config.yml`（killaura 下加 selfinteract/modulo360 键）

**步骤 1：SelfInteract**（onAttack 开头，targetId 需要玩家自身 entity id——从 AttackContext 的玩家数据拿：`ctx.data` 无 entityId，需在 MoveContext 或 BukkitListener 存；简化：在 handleUseEntity 里比较 `targetId == player.getEntityId()`，AsyncPacketListener 有 player 引用）

```java
// AsyncPacketListener.handleUseEntity 开头加：
if (targetId == player.getEntityId()) {
    data.actor.submit(() -> manager.getRegistry().flagSelfInteract(data));
    return;
}
// CheckRegistry 加方法，转发到 KillAuraCheck.flagSelfInteract：
// bump("selfinteract", 1, i("selfinteract.vl-before-flag", 1)) -> flag("SelfInteract", "attacked self")
```

**步骤 2：AimModulo360**（checkAimStep 门控内，before 90° 放行判断）

```java
// 在 hasPrevRotation 已初始化、dYaw/dPitch 计算后：
double rawDelta = MathUtil.normalizeYaw(ctx.yaw - data.prevYaw);
double absRaw = Math.abs(rawDelta);
if (absRaw > 320D) {  // 近 360° 取模跳变
    if (bump(data, "modulo360", 1D, i("modulo360.vl-before-flag", 2))) {
        flag(data, "AimModulo360", "yaw wrap " + MathUtil.round(absRaw, 1));
    }
}
// 注：normalizeYaw 后 |delta|≤180，>320 不会命中——需用未归一化差值判断
// 正确实现：计算 raw = ctx.yaw - data.prevYaw（不归一化），若 |raw| > 320 判取模
```

**步骤 3：验证**：构建 + grep + 部署（绿玩正常转头不触发；LB/BadPackets 类的 yaw 取模触发）

**风险：** 归一化陷阱（上面注释已标注）；`data.prevYaw` 更新点在 checkAimStep 末尾，需在更新前取 raw 差值。

---

#### 任务 A3：ExtremeMove 兜底重罚

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\data\MovementTracker.java`（handle 内暴露本次位移量）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\movement\SpeedCheck.java` 或新建 `ExtremeMoveCheck`

**步骤 1：MovementTracker.handle 记录 `lastMoveX/lastMoveY/lastMoveZ`（public volatile double，每次 handle 写入 |dx|/|dy|/|dz|；突变重置分支也写）**

**步骤 2：ExtremeMove 判据**（建议并入 SpeedCheck.onMove 头部，复用其豁免条件 creative/flying/vehicle/dead/ping）

```java
if (m.lastMoveY > 4.0D || m.lastMoveX > 22.0D || m.lastMoveZ > 22.0D) {
    if (bump(data, "extreme", 3D, i("extreme.vl-before-flag", 2))) {   // 重罚：3/次
        flag(data, "ExtremeMove", "dy=" + MathUtil.round(m.lastMoveY, 1)
                + " dxz=" + MathUtil.round(Math.max(m.lastMoveX, m.lastMoveZ), 1));
    }
    return;  // 跳过本 tick 其余 speed 判据（突变免误报）
}
```

**步骤 3：验证**：绿玩 22 格水平移动=15 秒疾跑单 tick 不可能；传送（突变重置分支）后 lastMove 仍记录但已豁免本 tick——确认传送不会触发（传送时 `initialized=false` 分支直接 return，检查在 onMove 里只在 initialized 后跑——需确认顺序）。

**风险：** 传送/活塞推送的 y 突变（活塞推 1-3 格 <4 安全）；地狱门传送（突变重置豁免）。ExtremeMove 需放在 MovementTracker 重置语义之后，只对"正常轨迹内的极端位移"判。

---

### 阶段 B：已有检测判据强化

#### 任务 B1：KillAura 多目标换攻检测（NCP Angle 思想）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\data\PlayerData.java`（加攻击目标历史字段）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\KillAuraCheck.java`（onAttack 记录 + checkMultiTarget）

**步骤 1：PlayerData 字段**

```java
public final java.util.List<Long[]> targetHistory = new java.util.ArrayList<Long[]>(); // {time, targetId, yawMillis}
public volatile int lastTargetId = -1;
```

**步骤 2：onAttack 记录 + 判定**

```java
// onAttack 内（targetId 已知处）：
data.targetHistory.add(new Long[] { now2, (long) ctx.targetId, (long) (data.lastYaw * 1000D) });
while (!data.targetHistory.isEmpty() && now2 - data.targetHistory.get(0)[0] > 3000L) {
    data.targetHistory.remove(0);
}
checkMultiTarget(data, now2);

private void checkMultiTarget(PlayerData data, long now) {
    if (data.targetHistory.size() < 4) return;
    Set<Long> targets = new HashSet<Long>();
    double yawMin = Double.MAX_VALUE, yawMax = -Double.MAX_VALUE;
    for (Long[] h : data.targetHistory) {
        targets.add(h[1]);
        double y = h[2] / 1000D;
        yawMin = Math.min(yawMin, y); yawMax = Math.max(yawMax, y);
    }
    double yawSpan = Math.abs(MathUtil.normalizeYaw(yawMax - yawMin));
    if (targets.size() >= 2 && yawSpan < 45D) {
        if (bump(data, "multitarget", 1D, i("multitarget.vl-before-flag", 5))) {
            flag(data, "MultiTarget", "targets=" + targets.size() + " yawSpan=" + MathUtil.round(yawSpan, 1));
        }
    } else {
        drain(data, "multitarget", 0.05D);
    }
}
```

**步骤 3：config 键** `killaura.multitarget.vl-before-flag: 5`

**验证：** 1.8 双人混战快速切换目标（每次转身 >45°）不触发；贴脸 A-B-A 双目标且视线不动（KB 卡角）触发。

**风险：** yaw 记录用 `lastYaw`（上一包），攻击与旋转可能不同 tick——接受（多目标特征在 yawSpan 窗口层面成立）；转身中间攻击会拉大 yawSpan 保护绿玩 ✓。

---

#### 任务 B2：Criticals 跳跃轨迹验证（LiftOffEnvelope 简化版）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\CriticalsCheck.java`
- 修改：`YCBR-AC\src\main\resources\config.yml`（criticals 下加 jump-curve 键）

**步骤 1：判据（替换现有 Air 判据，保持 criticals.enabled: false 默认关）**

```java
// 期望跳跃曲线（1.8 起跳 0.42，重力每 tick 0.08，容差 0.05）：
//   jumpTicks = 起跳后 tick 数（airTicks）
//   expectedMax = max(0, 0.42 - 0.08 * airTicks) + 0.05 容差
// 判据（攻击时 airborne 且非 jumpedThisTick）：
//   (a) airTicks <= 6 && motionY > expectedMax -> 悬停/匀速悬浮攻击（跳跃窗口内异常爬升）
//   (b) airTicks > 6 && motionY > 0.08 -> 跳跃窗口外仍在上升 = 悬浮攻击（正常此时已 <0）
// 两路任一 bump("air", 1, vl-before-flag 5)
```

```java
@Override
protected void onAttack(AttackContext ctx) {
    // ... 现有豁免条件保持 ...
    MovementTracker m = data.movement;
    if (!m.airborne || m.jumpedThisTick) { drain(data, "air", 0.1D); return; }
    double expectedMax = Math.max(0D, 0.42D - 0.08D * m.airTicks) + d("air.jump-tolerance", 0.05D);
    boolean susp = (m.airTicks <= 6 && m.motionY > expectedMax)
            || (m.airTicks > 6 && m.motionY > 0.08D);
    if (susp) {
        if (bump(data, "air", 1D, i("air.vl-before-flag", 5))) {
            flag(data, "Air", "jump-curve violation airTicks=" + m.airTicks
                    + " motionY=" + MathUtil.round(m.motionY, 3));
        }
    } else {
        drain(data, "air", 0.1D);
    }
}
```

**验证：** 保留 `criticals.enabled: false`（用户自行开启）；开启后绿玩跳跃暴击（正常抛物线）不触发；LB crit 宏（跳跃中持续攻击 motionY 异常）触发。

**风险：** 击退时攻击（velocity.vertical() 未计入曲线）→ 需在判据中加 `+ data.velocity.vertical()`（与 FlyCheck 一致）。低 TPS 时间归一已保证 motionY 按 50ms 缩放 ✓。

---

#### 任务 B3：Fly 竖直物理包络（核心强化）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\movement\FlyCheck.java`（checkRise 重构）
- 修改：`YCBR-AC\src\main\resources\config.yml`（fly 下加 jump-decay 键）

**步骤 1：checkRise 重构（保留 1.25 硬上限，新增跳跃衰减曲线判据）**

```java
// 目标：motionY > 期望值(跳跃衰减曲线或 0.42 上限) 且非起跳 tick -> 匀速爬升判罪
private void checkRise(PlayerData data) {
    MovementTracker m = data.movement;
    double motionY = m.motionY;
    if (m.slimeTicks >= 40 || m.jumpedThisTick || !m.airborne) {
        drain(data, "rise", 0.05D);
        return;
    }
    if (Math.abs(m.lastY - m.lastLastY) > 0.7D) {  // 活塞/电梯单 tick 竖直突变豁免
        drain(data, "rise", 0.05D);
        return;
    }
    double kb = data.velocity.vertical();
    // 1.8 竖直期望：起跳 0.42，每 tick 衰减 0.98 与重力 0.08 的近似曲线上限
    double expected = Math.max(0D, 0.42D * Math.pow(0.98D, m.airTicks)) + d("rise.jump-tolerance", 0.05D);
    double max = Math.max(d("rise.max-vertical", 1.25D), expected) + kb;
    if (m.nearLiquidTicks > 0) max += 0.05D;
    if (motionY > max) {
        if (lastRiseOver) {
            if (bump(data, "rise", 1D, i("rise.vl-before-flag", 5))) {
                flag(data, "Rise", "motionY=" + MathUtil.round(motionY, 2)
                        + " expected=" + MathUtil.round(expected, 2));
            }
        } else {
            lastRiseOver = true;
        }
    } else {
        lastRiseOver = false;
        drain(data, "rise", 0.05D);
    }
}
```

**步骤 2：MovementTracker 增加 `lastLastY`（上一上一帧 y，用于单 tick 突变豁免）**

```java
// handle 内：lastLastY = lastY; lastY = y;（在现有 lastY 更新处加一行）
public double lastLastY;
```

**验证：** 绿玩跳跃（0.42 起跳后衰减）不触发；Fly 匀速爬升 0.5-1.2（airTicks≥6 时 expected≈0.37+0.05，0.5>0.42 判；0.5 爬升在 airTicks 0-5 段 expected 0.47-0.36，motionY 0.5 在 airTicks≥2 时 >0.47+0.05? 0.5>0.52? 否——0.5 爬升需到 airTicks≥3 后 expected 0.39+0.05=0.44 <0.5 ✓ 判）；活塞推 1 格（dy 1.0 >0.7 豁免）✓。

**风险：** 击退（kb 加成已含）；水上跳（nearLiquid +0.05）；slime（豁免）；低 TPS（时间归一）。**核心风险：1.8 跳跃的 motionY 曲线**——起跳后 airTicks=1 时 motionY≈0.42×0.98-0.08≈0.33（0.98 衰减模型）或 0.42-0.08=0.34（线性近似）→ expected=0.42×0.98^1=0.41+0.05=0.46 > 0.33 ✓ 安全。需实测校准容差。

---

#### 任务 B4：Velocity 部分抗 KB 分层

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\movement\VelocityCheck.java:41-53`（onGround 分支）
- 修改：`YCBR-AC\src\main\resources\config.yml`（horizontal-travel-ratio 0.5 → 0.35，新增连续判定）

**步骤 1：收紧比率 + 连续 2 tick 判定**

```java
// onGround 分支改为：
if (vs.hasVerticalKnockback() && !vs.airborneSeen() && vs.ticksSince() >= 2 && vs.ticksSince() <= 8) {
    double ratio = m.distanceXZ / vs.expectedHorizontal();
    if (ratio < d("horizontal-travel-ratio", 0.35D)) {
        if (++data.kbLowTicks >= 2) {   // 连续 2 tick 低于比率才判（防 ping 抖动）
            data.kbLowTicks = 0;
            if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
                flag(data, "Vertical", "low KB travel ratio=" + MathUtil.round(ratio, 2));
            }
        }
    } else {
        data.kbLowTicks = 0;
        drain(data, "vertical", 0.1D);
    }
}
// PlayerData 加：public volatile int kbLowTicks;
```

**步骤 2：config 改 `horizontal-travel-ratio: 0.35`**

**验证：** 100% 抗 KB（ratio≈0）立即判；50% 抗（0.5>0.35）不判；绿玩完整 KB（0.9-1.1）不判。连续 2 tick 防单 tick 抖动。

**风险：** 落地滑行（1.8 落地后 0.91 摩擦保留速度）ratio 可能短暂低——但该分支要求 `ticksSince()<=8`（KB 后 8 tick 内落地场景）；实测校准。若绿玩误报回退 0.4。

---

### 阶段 C：算法级改造（按需推进）

#### 任务 C1：GCD 众数估计 + 灵敏度网格（改造 GcdStable）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\KillAuraCheck.java`（checkGcd 重构）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\util\MathUtil.java`（加 RunningMode 简化版）

**步骤 1：简化 RunningMode（参考 Grim AimProcessor）**

```java
// MathUtil 加：
public static long mode(List<Long> values) {
    // 简单众数：Map<Long,Integer> 计数，返回频次最高者；空返回 0
}

// checkGcd 重构（PlayerData 加 gcdModeBuckets Map<Long,Integer> + gcdMode 计数）：
// 1) 相邻对 gcd 加入桶（按 1 单位 = EXPANDER 精度，值域 [1, 2^24]）
// 2) 桶容量 80，满则整体移位丢弃最老（用 ArrayList<Long> 记录顺序）
// 3) 判定：众数频次 >= 15 且占桶 >= 30% -> mode 稳定
//    deltaDots = round(delta / mode) 对最近 10 个 delta 完全一致 -> flag("GcdGrid", "mode=" + mode)
// 4) 保留旧 GcdStable（std<0.25）作为恒定步长兜底，两条独立 bump
```

**步骤 2：config 键** `killaura.gcdgrid.vl-before-flag: 6`

**验证：** 人类鼠标 delta（网格不恒定）众数不稳定 → 不判；OpenZen 式 GCD-sync（delta=k×gcd 恒）→ mode 稳定且 dots 一致 → 判。

**风险：** 桶实现注意内存（80 项即可）；众数对"多网格并存"（人类偶尔对齐）需 30% 占比门槛。

---

#### 任务 C2：Scaffold Rotation 射线化（替代统计背离）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\ScaffoldCheck.java`（checkRotation 重构）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\util\MathUtil.java`（已有 rayIntersectsBox ✓ 直接复用）

**步骤 1：射线判定**

```java
// 用 MathUtil.rayIntersectsBox(eyeX, eyeY, eyeZ, lastYaw, lastPitch, blockX, blockY, blockZ, expand)
//   eye 高度：standing 1.62 / sneaking 1.54 双眼位（参考 Grim RotationPlace 三眼位）
//   expand：0.03（1.8 容差）
// 语义：两条射线（当前/上一 tick yaw）任一命中放置方块 AABB -> 合法；都未命中 -> bump("rotation")
// 替代现有 rotationAwayStreak 连续计数（保留 consecutive 需要 5 次机制避免单帧抖动）
// blockY != feetY（垂直塔）与贴身（<0.25）豁免保留
```

**验证：** 绿玩搭路看方块方向命中 ✓；LB GodBridge 视线 45° 网格但仍指向脚下方块——射线可能仍命中！**预期：射线法对 LB 失效**（GodBridge 看着放置点），真正有效的是 Task B 已做的 Grid45/Cadence。C2 仅对"完全背对放置"的旧式 Scaffold 有效——**若实测 LB 不触发则保留现状（C2 标记为可跳过）**。

**风险：** 射线实现与 MC 客户端判定差异（客户端的 ray trace 精度 vs 数学射线）→ expand 0.03 余量；1.8 放置点击面本身在方块外表面——射线必须命中面内侧，注意方向取反问题（参考 Grim 的 hitbox 面方向处理）。

---

#### 任务 C3：Timer 包频检测（新增维度，参考 Grim Timer）

**涉及文件：**
- 新建：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\protocol\TimerCheck.java`（CheckRegistry 注册）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\packet\AsyncPacketListener.java`（监听 POSITION/LOOK + KEEP_ALIVE）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\CheckType.java`、`CheckRegistry.java`、`config.yml`

**步骤 1：玩家时钟（1.8 简化版，参考 Grim Timer 余额法）**

```java
// 每收到位置/LOOK 包：balance += 50ms（1.8 每 tick 发飞包）
// 每收到 KEEP_ALIVE 响应：锚定 realClock = now（1.8 keepalive 周期 500ms）
// 判定：balance - (now - anchor) > 250ms -> Timer（发包比 20tps 快）
// TimerLimit：ping > 400ms 时平衡上限 1s（避免高 ping 误报）
// NegativeTimer（可选）：balance 落后 realClock 超 1200ms -> 减速/blink
```

**步骤 2：config 键** `timer.vl-before-flag: 5`

**验证：** 绿玩 20tps 恒定发包 balance 与 realClock 同步增长 → 不触发；Timer 1.1×（22tps 发包）持续 2 秒后 balance 超 250ms → 判。

**风险：** 1.8 客户端在站定时**不发飞包**（1.8 只有移动才发位置包！）——`idle flying` 是 1.9+ 概念，1.8 无 idle 包 → 余额法在 1.8 需要用"包间隔"统计（NCP FlyingFrequency：窗口内包数 vs 窗口时长），或 keepalive 时钟校准。**采用 NCP 式 ActionFrequency 分桶**（6 秒窗口 eps 20/s 阈值 22/s + burst），实现更简单且 1.8 验证过。**判据改为 NCP MorePackets 版。**

---

#### 任务 C4：NoFall / GroundSpoof（参考 Grim/NCP）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\packet\AsyncPacketListener.java`（LOOK 包 onGround 声明捕获）
- 新建：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\movement\NoFallCheck.java`（CheckRegistry 注册）
- 修改：`CheckType.java`、`config.yml`

**步骤 1：判据**

```java
// 仅 LOOK（含位置+旋转的 LOOK 包）包捕获 onGround 布尔（packet.getBooleans().read(0) 或 modifier）
// 主判据：客户端声明 onGround=true 但服务端预测自由落体 motionY < -0.5 且下降 >5 tick
//   （NCP：脚下碰撞盒查询；YCBR 无世界查询 -> 用运动学近似：|motionY| > 0.55 仍 onGround 声明）
//   > 真实落地 motionY≈0；自由落体 7+ tick 后 motionY 恒 < -0.5
//   判定：onGround 声明 && motionY < -0.5 && airTicks > 7 -> bump("ground", 1, 5)
```

**步骤 2：config 键** `nofall.vl-before-flag: 5`，默认 enabled: true

**验证：** 绿玩落地（motionY≈-0.1~-0.3 声明 onGround）不触发；NoFall（声称 onGround 免疫摔伤）持续触发。

**风险：** 1.8 客户端 onGround 声明与服务器实际状态延迟（网络抖动）→ airTicks>7 + motionY<-0.5 双条件已充分宽松；台阶/半砖落地（motionY 变化小）安全；低 TPS（时间归一 motionY 放大——50/elapsed 缩放：elapsed>50 时 timeScale<1 → motionY 缩小 ✓ 不会放大）。

---

## 验证方式（每任务通用）

1. **构建**：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" -q -DskipTests package`（workdir `YCBR-AC`），预期 BUILD SUCCESS
2. **自检**：grep 新旧类名/配置键一致性（如 `Select-String -Path "src\main\java\com\ycbr\anticheat\check\combat\ScaffoldCheck.java" -Pattern "InvalidPlace"`）
3. **部署验收**：`target\YCBR.jar` 部署 1.8.9 测试服，按每任务"验证"栏的绿玩/外挂场景跑，控制台日志确认 flag 与静默

## 风险与注意事项

1. **1.8 无 idle flying 包**（站定不发位置包）：所有"每 tick 包"类检测（Timer）必须用 NCP 式窗口统计，不能照抄 Grim 事务时钟（C3 已标注改用 NCP 方案）
2. **协议字段读取版本差异**：BLOCK_PLACE 的 cursor（1.8 float vs 1.9+ double）需实测确认（A1）
3. **竖直判据的 1.8 曲线校准**：B3 的 0.42×0.98^t 曲线是近似，绿玩跳跃实测校准容差（jump-tolerance），宁可漏判不可误判
4. **不引入世界查询**：所有检测保持在 actor 异步线程纯数据计算（A1/B/C 设计均无 Bukkit 世界 API 依赖；PositionPlace/FarPlace/AirLiquidPlace 需要方块数据——**本计划不包含**，避免异步线程读世界）
5. **默认开关**：criticals.enabled 保持 false；新检测默认 enabled true（铁证型 A 类）/ 高风险判据默认 false 待实测后开启
6. **每任务独立可测**：不依赖前序任务；可任意顺序执行

## 执行顺序建议

A1 → A2 → A3 → B4 → B1 → B3 → B2 →（C1 → C3 → C4 → C2 按需）
