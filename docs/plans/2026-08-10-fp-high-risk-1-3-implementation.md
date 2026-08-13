# 高危误报修复（任务 1-7）实施计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 子技能，按任务逐项执行本计划。

**目标：** 修复三大高危绿玩误报：珍珠/传送位移豁免、攻击 Cps 放宽为连续窗口判定、Angle 改为 3D 视线夹角。

**架构方案：** MovementTracker 层检测单 tick 突变位移自动重置基准（所有检查天然跳过 1 tick + velocity.expire 联动）；checkCps 增加连续窗口计数；checkAngle 用视线向量与目标方向的 acos 夹角取代 yaw/pitch 双独立阈值。

**技术栈：** Java 8 / Spigot 1.8.9 / ProtocolLib；构建 = `C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd -q -DskipTests package`

---

### 任务 1：MovementTracker.handle 传送自愈（返回 boolean）

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\data\MovementTracker.java:32-40`

**步骤 1：改签名与突变检测**

在 `handle()` 开头（`initialized` 判断之前）插入突变检测；方法签名 `void handle(...)` → `boolean handle(...)`：

```java
public boolean handle(double x, double y, double z, boolean onIce, boolean onSlime, boolean nearLiquid,
        boolean boxedIn, boolean inWeb) {
    if (initialized
            && (Math.abs(x - lastX) > 3.0D || Math.abs(z - lastZ) > 3.0D || Math.abs(y - lastY) > 2.5D)) {
        lastX = x;
        lastY = y;
        lastZ = z;
        initialized = false;
        return true;
    }
    if (!initialized) {
        lastX = x;
        lastY = y;
        lastZ = z;
        initialized = true;
        return false;
    }
    // ... 原逻辑不变
    return false;
}
```

注意：原 `!initialized` 首包初始化分支保留（逻辑等价改写），原尾部 `lastX/lastY/lastZ` 赋值不动。

**步骤 2：其余 return 路径补 false**

原方法体中无其他 return。改动后代码需无编译错误。

**步骤 3：构建确认**

运行：`& "...\mvn.cmd" -q -DskipTests package`（工作目录 `YCBR-AC`）
预期：编译报错 —— `AsyncPacketListener.java:101` 调 `handle` 未接返回值不影响编译（boolean 返回值可被忽略调用，Java 允许），**但真正确认方式见任务 2**。本任务构建应通过。

---

### 任务 2：AsyncPacketListener 联动 velocity.expire()

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\packet\AsyncPacketListener.java:101`

**步骤 1：接住返回值并清 pending**

原代码（101 行附近）：
```java
data.movement.handle(x, y, z, data.blockOnIce, data.blockOnSlime, data.blockNearLiquid, ...);
```
改为：
```java
if (data.movement.handle(x, y, z, data.blockOnIce, data.blockOnSlime, data.blockNearLiquid, ...)) {
    data.velocity.expire();
}
```
（第二个参数按原调用实参逐字保留，这里仅示意）

**步骤 2：构建**

运行：`mvn -q -DskipTests package`
预期：**成功**，`target\YCBR.jar` 生成。

---

### 任务 3：Cps 连续窗口判定

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\data\PlayerData.java`（`cpsStreak` 字段，加在 `rotationAwayStreak` 附近）
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\KillAuraCheck.java:143-157`（checkCps）

**步骤 1：PlayerData 加字段**

```java
public volatile int cpsStreak;
```

**步骤 2：重写 checkCps 计数段**

```java
private void checkCps(AttackContext ctx) {
    PlayerData data = ctx.data;
    long window = i("cps.window-ms", 1000);
    data.attackTimes.add(ctx.time);
    data.attackTimes.removeIf(t -> t < ctx.time - window);
    int cps = data.attackTimes.size();
    int maxCps = i("cps.max-cps", 20);
    if (cps > maxCps) {
        int need = Math.max(1, i("cps.required-consecutive-windows", 2));
        if (++data.cpsStreak >= need) {
            data.cpsStreak = 0;
            if (bump(data, "cps", 1D, i("cps.vl-before-flag", 6))) {
                flag(data, "Cps", "cps=" + cps + " max=" + maxCps);
            }
        } else {
            drain(data, "cps", 0.05D);
        }
    } else {
        data.cpsStreak = 0;
        drain(data, "cps", 0.05D);
    }
}
```

**步骤 3：构建**，预期通过。

---

### 任务 4：MathUtil 新增 3D 夹角方法

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\util\MathUtil.java`（追加静态方法）

**步骤 1：追加实现**

```java
public static double angleToTarget(double yaw, double pitch, double dx, double dy, double dz) {
    double yawRad = Math.toRadians(yaw);
    double pitchRad = Math.toRadians(pitch);
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1.0E-6D) {
        return 0D;
    }
    double cosPitch = Math.cos(pitchRad);
    double ax = -Math.sin(yawRad) * cosPitch;
    double ay = -Math.sin(pitchRad);
    double az = Math.cos(yawRad) * cosPitch;
    double dot = ax * dx + ay * dy + az * dz;
    double cos = dot / len;
    if (cos > 1D) {
        cos = 1D;
    } else if (cos < -1D) {
        cos = -1D;
    }
    return Math.toDegrees(Math.acos(cos));
}
```

视线方向约定（MC）：yaw 0=南(−z 方向为正)，`(-sin(yaw), -sin(pitch), cos(yaw))·cos(pitch)` 水平分量。

**步骤 2：构建**，预期通过。

---

### 任务 5：checkAngle 重写为 3D 夹角

**涉及文件：**
- 修改：`YCBR-AC\src\main\java\com\ycbr\anticheat\check\combat\KillAuraCheck.java:107-141`

**步骤 1：替换 checkAngle 方法体**

```java
private void checkAngle(AttackContext ctx, EntitySnapshot target) {
    PlayerData data = ctx.data;
    if (!data.hasRotation) {
        return;
    }
    double targetCenterY = target.y + target.height / 2D;
    double max = d("angle.max-angle-3d", 75D);
    double best = Double.MAX_VALUE;
    for (double eye : new double[] { EYE_STANDING, EYE_SNEAKING }) {
        double angle = MathUtil.angleToTarget(data.lastYaw, data.lastPitch,
                target.x - data.movement.lastX,
                targetCenterY - (data.movement.lastY + eye),
                target.z - data.movement.lastZ);
        best = Math.min(best, angle);
    }
    if (best > max) {
        if (bump(data, "angle", 1D, i("angle.vl-before-flag", 6))) {
            flag(data, "Angle", "angle3d=" + MathUtil.round(best, 1) + " max=" + MathUtil.round(max, 1));
        }
    } else {
        drain(data, "angle", 0.05D);
    }
}
```

删除旧 yaw 70°/pitch 60° 双分支逻辑（其中 `MathUtil.horizontal`、`MathUtil.pitchToTarget` 若不再被其他方法引用，保留不动，MathUtil 是公共工具类）。

**步骤 2：构建**，预期通过。

---

### 任务 6：config.yml 同步

**涉及文件：**
- 修改：`YCBR-AC\src\main\resources\config.yml`

**步骤 1：修改 killaura 节**

```yaml
    cps:
      max-cps: 20
      window-ms: 1000
      required-consecutive-windows: 2
      vl-before-flag: 6
    angle:
      max-angle-3d: 75.0
      vl-before-flag: 6
```

删除 `max-angle`、`max-pitch-difference`（被 3D 取代）。

**步骤 2：构建**（config 不编译校验，仅确认 jar 含新文件，见任务 7）。

---

### 任务 7：构建 + 全量验证

**步骤 1：mvn 干净构建**

运行：`& "...\mvn.cmd" -q -DskipTests package`
预期：成功；`Get-Item target\YCBR.jar | Select Length,LastWriteTime` 显示新时间戳。

**步骤 2：jar 内容抽查**

运行：`jar tf target\YCBR.jar | Select-String "KillAuraCheck|MovementTracker|MathUtil|config.yml"`（或用 PowerShell `tar -tf` 别名 `tar`）
预期：4 个条目都在。

**步骤 3：逻辑自检清单**（人工核对，无测试框架）
- `MovementTracker.handle`：initialized 分支重写正确、原首包初始化语义保持、突变路径不执行 ice/slime 等计数
- `KillAuraCheck`：无残留对旧 `max-angle`/`max-pitch-difference` 配置键的读取
- 全项目 grep `max-pitch-difference|max-angle"` 无残留引用

---

## 验证方式

- 服务器实测场景（部署后由用户执行）：珍珠上塔、珍珠落地搭路、塔下打塔上敌人、jitter 16-20cps 持续点击 → 全部静默
- 回归：20cps+ 持续 2 秒宏点击 → Cps flag；背对目标攻击 → Angle flag
- 传送后 1 tick 内 Velocity pending 被 expire，无 `no knockback taken` 误报

## 风险与注意事项

- TNT 多连爆水平推速理论上可超 3.0/tick 被当传送豁免：记录风险，不处理（当前测试场景无 TNT 大炮）
- `movement.handle` 调用点仅 AsyncPacketListener 一处（grep 确认），签名改动无遗漏
- velocity.issue（ENTITY_VELOCITY 包）在传送判定**后**进入——落地后接新击退不受影响