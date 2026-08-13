# 误报修复（Velocity/Speed/KillAura/Scaffold）实施计划

> **给 Claude：** 按任务逐项执行（项目无 git 仓库、无单元测试框架，验证 = 构建成功 + 任务最后的手动回归清单）。

**目标：** 消除绿玩误报：① 站着被连续攻击触发 Velocity 封禁；② 击退后 Speed 误报（包顺序竞争）；③ KillAura Reach 极限距离误报；④ KillAura AimStep 正常转向误报；⑤ Scaffold MovePlace/Rotation/FastPlace 搭桥误报。

**根因（已定位）：**
- Velocity `VelocityCheck.java:64-68` fall 分支未检查 `airborneSeen()`：合法击退"上升-回落"每次 +1 VL，14 次攻击即封禁。
- Speed `SpeedCheck.java:34`：位置包先于速度包到达 → `velocity.horizontal()` 补偿未生效 → 击退位移误判为超速。
- Reach `KillAuraCheck.java:78-101`：用"眼睛到目标中心"距离，合法极限攻击中心距离 ~3.3 > 3.05 必然误报；应为眼睛到 AABB 表面距离。
- AimStep `KillAuraCheck.java:26-55`：正常人水平转身 = 单轴帧，1° 阈值太紧。
- Scaffold MovePlace `ScaffoldCheck.java:54-65` 阈值 0.16 低于正常行走搭桥速度 0.22-0.28；Rotation `ScaffoldCheck.java:67-89` 平视射线够不到脚下方块必误报；FastPlace `ScaffoldCheck.java:38-52` 13cps 低于人类连点 15+。

**技术栈：** Java 8、Bukkit/Paper 1.8.9、ProtocolLib（不改 packet 层）。

---

## 任务 1：VelocityCheck 修复 + 站桩免击退检测

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/data/VelocityState.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/VelocityCheck.java`

**步骤 1：VelocityState 增加垂直击退标记**

`issue()` 记录本次垂直击退值，新增：

```java
private boolean verticalKnockback;
// issue() 内：
this.verticalKnockback = y >= 0.05D;
// expire() 内：this.verticalKnockback = false;
public boolean hasVerticalKnockback() {
    return verticalKnockback;
}
```

**步骤 2：VelocityCheck 重写判罚分支**

- fall 分支（现 64-68 行）改为：

```java
} else if (m.motionY <= 0 && !vs.airborneSeen() && vs.ticksSince() >= 2 && !m.jumpedThisTick) {
```

- 地面分支（现 41-50 行）改为站桩免击退检测（移除 groundTicks 限制，窗口 2-8 tick）：

```java
if (m.onGround) {
    if (vs.hasVerticalKnockback() && !vs.airborneSeen() && vs.ticksSince() >= 2 && vs.ticksSince() <= 8) {
        if (bump(data, "vertical", 1D, i("vertical.vl-before-flag", 4))) {
            flag(data, "Vertical", "no knockback taken");
        }
    } else if (vs.ticksSince() > 8) {
        vs.expire();
    }
    return;
}
```

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`（workdir=YCBR-AC）
预期：**构建成功**。

---

## 任务 2：SpeedCheck 瞬间突增豁免

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/SpeedCheck.java`
- 修改：`src/main/resources/config.yml`（加 `speed.spike-grace`）

**步骤 1：onMove 超速判定前加豁免**

在 `SpeedCheck.onMove` 的 `double over = m.distanceXZ - limit;` 之后、`if (over > 0D)` 之前插入：

```java
if (m.distanceXZ - m.lastDistanceXZ > d("spike-grace", 0.25D)) {
    drain(data, "speed", 0.02D);
    return;
}
```

**步骤 2：config.yml speed 节新增**

```yaml
    spike-grace: 0.25
```

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 3：Reach 改为 AABB 表面距离

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/util/MathUtil.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/KillAuraCheck.java`

**步骤 1：MathUtil 新增 distanceToAabb**

```java
public static double distanceToAabb(double ex, double ey, double ez, double cx, double cy, double cz,
        double halfW, double halfH) {
    double dx = Math.max(0D, Math.abs(ex - cx) - halfW);
    double dy = Math.max(0D, Math.abs(ey - cy) - halfH);
    double dz = Math.max(0D, Math.abs(ez - cz) - halfW);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
}
```

（按现有 MathUtil 风格：显式泛型、4 空格缩进、无 javadoc 冗余。）

**步骤 2：checkReach 改用表面距离**

现 78-101 行，距离计算改为（目标中心 Y、半高=height/2、半宽 0.3）：

```java
private void checkReach(AttackContext ctx, EntitySnapshot target) {
    PlayerData data = ctx.data;
    double targetCenterY = target.y + target.height / 2D;
    double reached = Double.MAX_VALUE;
    for (double eye : new double[] { EYE_STANDING, EYE_SNEAKING }) {
        reached = Math.min(reached, MathUtil.distanceToAabb(data.movement.lastX,
                data.movement.lastY + eye, data.movement.lastZ, target.x, targetCenterY, target.z,
                0.3D, target.height / 2D));
    }
    ... // ping 补偿与 flag 逻辑保持不变
}
```

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 4：AimStep 放宽

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/KillAuraCheck.java`
- 修改：`src/main/resources/config.yml`（`aimstep.min-step-delta` 1.0 → 20.0）

**步骤 1：checkAimStep 增加巨幅放行 + 断链重置**

在 step 判断前插入（单轴 ≥90° 视为大转弯，直接刷新基线放行）：

```java
if (dYaw >= 90D || dPitch >= 90D) {
    data.prevYaw = ctx.yaw;
    data.prevPitch = ctx.pitch;
    return;
}
```

**步骤 2：config.yml aimstep 节**

```yaml
    aimstep:
      max-no-delta: 0.00001
      min-step-delta: 20.0
      vl-before-flag: 8
```

（`data.creative || data.flying || data.inVehicle || data.ping > cfg.maxPing()` 豁免保留。）

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 5：Scaffold MovePlace / Rotation / FastPlace 修复

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/ScaffoldCheck.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：checkRotation 改用 yaw 朝向差 + underFeet 收紧**

现 67-89 行替换为：

```java
private void checkRotation(PlaceContext ctx) {
    PlayerData data = ctx.data;
    if (!data.hasRotation) {
        return;
    }
    int feetY = (int) Math.floor(data.movement.lastY);
    double minPitch = d("rotation.min-pitch", -60D);
    boolean underFeet = ctx.blockY == feetY - 1 && data.lastPitch < minPitch;
    double dx = ctx.blockX + 0.5D - data.movement.lastX;
    double dz = ctx.blockZ + 0.5D - data.movement.lastZ;
    double yawToBlock = MathUtil.yawToTarget(dx, dz);
    double yawDiff = Math.abs(MathUtil.normalizeYaw(data.lastYaw - yawToBlock));
    boolean lookingAway = yawDiff > d("rotation.max-yaw-diff", 100D);
    if (underFeet || lookingAway) {
        if (bump(data, "rotation", 1D, i("rotation.vl-before-flag", 6))) {
            flag(data, "Rotation", (underFeet
                    ? "under-feet pitch=" + MathUtil.round(data.lastPitch, 1) + " min=" + MathUtil.round(minPitch, 1)
                    : "not looking at placed block"));
        }
    } else {
        drain(data, "rotation", 0.05D);
    }
}
```

（`MathUtil.yawToTarget` 已存在；`normalizeYaw` 已存在。若 yawToTarget 返回值为任意角度，确保 normalizeYaw 兼容其范围。）

**步骤 2：config.yml scaffold 节**

```yaml
    fast-place:
      max-cps: 20
      window-ms: 1000
      vl-before-flag: 6
    move-place:
      max-speed: 0.35
      vl-before-flag: 5
    rotation:
      min-pitch: -60.0
      max-yaw-diff: 100.0
      vl-before-flag: 6
```

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 6：最终构建与回归验证

**步骤 1：完整构建 + class 版本检查**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。class major 52（Java 8）。

**步骤 2：1.8.9 测试服手动回归清单**

1. 绿玩站着被普通玩家连续攻击 15+ 次 → **零** Velocity 告警，不再封禁。
2. 绿玩行走/疾跑搭桥 → MovePlace **零**误报；水平视线搭桥（平视右键）→ Rotation **零**误报；连点搭桥 15-18/s → FastPlace **零**误报。
3. 绿玩对打：贴脸极限距离攻击、快速甩视野转身 → Reach/AimStep **零**误报。
4. 击退后速度：绿玩被击退滑动 → Speed **零**误报。
5. 作弊验证（可选）：no-knockback 客户端站着被打 → 仍报 "no knockback taken"。

---

## 验证方式

任务内联 `mvn -q -DskipTests package` + 任务 6 回归清单。

## 风险与注意事项

- 站桩免击退检测窗口 2-8 tick：8 tick（0.4s）内无垂直反应才报，宽松但安全；极高 ping（>400 除外）或极端卡顿玩家可能误报，可再调 `vertical.vl-before-flag`。
- `spike-grace` 只豁免突增 tick，持续超速的下一 tick 照常入网，不会产生规避漏洞。
- Reach 表面距离与 1.8.9 判定一致（≤3.0 合法），3.05 阈值保留 ping 补偿空间。
- AimStep 20° 后仅抓瞬移式极速翻转，轻度/平滑 aimbot 不再触发（按用户要求）。