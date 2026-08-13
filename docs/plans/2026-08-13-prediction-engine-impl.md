# 移动类预测引擎（SimulationCheck）实施计划

> **给 Claude：** 按任务逐项执行本计划，每个任务完成后验证通过再提交。

**目标：** 实现基于 1.8.8 NMS 源码的纯 Java 预测引擎（SimulationCheck），替代 Speed/Fly 的经验公式。

**架构方案：** PredictionEngine（纯 Java 静态工具）枚举候选输入生成预测包络；ShadowPlayer（每玩家一个）保存模拟状态并在每次移动包后 resync；SimulationCheck（CheckType.SIMULATION）读取预测结果与实际位移对比。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3

---

### 任务 1：添加 JUnit 5 测试基础设施

**涉及文件：**
- 修改：`pom.xml`
- 新建：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：在 pom.xml 中添加 JUnit 5 依赖**

在 `<dependencies>` 块末尾添加：

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

在 `<plugins>` 中添加 surefire 插件（确保测试被发现）：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
</plugin>
```

**步骤 2：创建测试目录和占位测试**

新建 `src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`：

```java
package com.ycbr.anticheat.simulation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionEngineTest {

    @Test
    void placeholder() {
        assertTrue(true);
    }
}
```

**步骤 3：运行测试确认通过**

```bash
cd YCBR-AC
mvn test -q
```

预期：**PASS**（1 test passed）

**步骤 4：提交**

```bash
git add pom.xml src/test/
git commit -m "Add JUnit 5 test infrastructure"
```

---

### 任务 2：实现 PredictionEngine 基础物理（地面行走 + 跳跃）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：写失败测试——地面行走 1 tick**

在 `PredictionEngineTest.java` 中添加：

```java
@Test
void groundWalkNormal_singleTick() {
    // 玩家在普通方块上正常行走，无跳跃
    double motionX = 0.0;
    double motionZ = 0.0;
    boolean onGround = true;
    float yaw = 0f;        // 面向正 Z
    double speedLevel = 0;
    double frictionFactor = 0.6;  // 普通方块
    boolean sprinting = false;
    boolean jumping = false;
    boolean sneaking = false;
    double jumpLevel = 0;
    double potionLevel = 0;

    PredictionEngine.Result r = PredictionEngine.predictSingle(
        motionX, motionZ, onGround, yaw, frictionFactor,
        sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel
    );

    // 走速 = 0.1 blocks/tick；向正Z走 sin(yaw=0)=0 cos=1
    // motZ += 0.1 * 1.0 = 0.1；motX = 0
    // 碰撞后摩擦：motX *= 0.546, motZ *= 0.546
    // 最终位移: motX=0, motZ≈0.1*0.546≈0.0546
    // 精度要求 ±0.01
    assertEquals(0.0, r.deltaX, 0.01);
    assertEquals(0.1 * 0.546, r.deltaZ, 0.01);
}
```

**步骤 2：运行测试确认失败**

```bash
mvn test -q 2>&1 | tail -5
```

预期：**FAIL**（Compilation error: cannot find symbol PredictionEngine）

**步骤 3：创建 PredictionEngine 基础骨架**

新建 `src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`：

```java
package com.ycbr.anticheat.simulation;

/**
 * 纯 Java 1.8.8 物理预测引擎（无 NMS 依赖）。
 * 公式来源：patched_1.8.8.jar v1_8_R3 EntityLiving.g() / Entity.a() / Entity.move()
 */
public final class PredictionEngine {

    private PredictionEngine() {}

    /** 基础玩家移动速度（EntityHuman.initAttributes:272） */
    public static final double BASE_SPEED = 0.1;

    /** 空中加速度（EntityLiving:186 aM） */
    public static final double AIR_ACCEL = 0.02;

    /** 重力（EntityLiving.g:1272） */
    public static final double GRAVITY = 0.08;

    /** 垂直拖拽（EntityLiving.g:1273） */
    public static final double VERTICAL_DRAG = 0.98;

    /** 水平摩擦基数（普通方块 0.6 × 0.91 = 0.546） */
    public static final double NORMAL_FRICTION = 0.546;

    /** 跳跃初速（EntityLiving.bE:1178） */
    public static final double JUMP_VELOCITY = 0.42;

    /** 加速因子常量（EntityLiving.g:1247） */
    public static final double ACCEL_FACTOR = 0.16277136;

    /** 疾跑属性修饰符（EntityLiving:160, multiply 0.3） */
    public static final double SPRINT_MODIFIER = 1.3;

    /** 冲刺跳跃冲量（EntityLiving.bF:1189） */
    public static final double SPRINT_JUMP_IMPULSE = 0.2;

    /** 空气摩擦（EntityLiving.g:1250 未碰撞时 f5=0.91） */
    public static final double AIR_FRICTION = 0.91;

    /**
     * 单步预测结果。
     */
    public static final class Result {
        public final double deltaX;
        public final double deltaZ;
        public final double motionY;
        public final boolean onGround;

        public Result(double deltaX, double deltaZ, double motionY, boolean onGround) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
            this.motionY = motionY;
            this.onGround = onGround;
        }
    }

    /**
     * 预测单 tick 物理位移（含输入→motion→move→摩擦/重力）。
     * 
     * @param motionX 上一 tick 的 motX（碰撞后/摩擦后值）
     * @param motionZ 上一 tick 的 motZ
     * @param onGround 当前是否落地
     * @param yaw 玩家朝向（度）
     * @param frictionFactor 脚下方块摩擦系数（0.6 普通 / 0.98 冰 / 0.8 史莱姆）
     * @param sprinting 是否疾跑
     * @param jumping 是否本 tick 跳跃
     * @param sneaking 是否潜行
     * @param speedLevel 速度药水等级
     * @param jumpLevel 跳跃药水等级
     * @param potionLevel 速度药水等级（别名）
     */
    public static Result predictSingle(
            double motionX, double motionZ, boolean onGround, float yaw,
            double frictionFactor, boolean sprinting, boolean jumping,
            boolean sneaking, double speedLevel, double jumpLevel, double potionLevel) {

        double motX = motionX;
        double motZ = motionZ;
        double motY = 0.0; // 输入预测只关心水平

        // --- 跳跃 ---
        double jumpVel = 0.0;
        if (jumping && onGround) {
            jumpVel = JUMP_VELOCITY + (jumpLevel > 0 ? (jumpLevel) * 0.1 : 0.0);
            motY = jumpVel;
            if (sprinting) {
                double rad = yaw * Math.PI / 180.0;
                motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
            }
        }

        // --- 计算地面摩擦 ---
        double f5 = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
        double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

        // --- 输入加速（Entity.a(f,f1,f2)）---
        double inputSpeed;
        if (onGround) {
            inputSpeed = BASE_SPEED * (sprinting ? SPRINT_MODIFIER : 1.0);
            if (potionLevel > 0 || speedLevel > 0) {
                inputSpeed *= 1.0 + 0.2 * Math.max(potionLevel, speedLevel);
            }
            inputSpeed *= f6;
        } else {
            inputSpeed = AIR_ACCEL;
        }

        // 潜行因子（客户端施加，服务器通过 delta 体现）
        double inputFactor = sneaking ? 0.3 : 1.0;

        // Entity.a(fwd=1.0, strafe=0.0, inputSpeed): 前进方向
        double fwd = 1.0 * inputFactor;
        double strafe = 0.0;
        double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
        if (f3 < 1e-4) {
            // 无输入，不加速度
        } else {
            if (f3 < 1.0) f3 = 1.0;
            f3 = inputSpeed / f3;
            double sinYaw = Math.sin(yaw * Math.PI / 180.0);
            double cosYaw = Math.cos(yaw * Math.PI / 180.0);
            motX += (fwd * f3) * cosYaw - (strafe * f3) * sinYaw;
            motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;
        }

        // --- Entity.move（碰撞简化：不做 AABB，后续容差覆盖）---

        // --- 后处理（EntityLiving.g:1268-1275）---
        if (onGround) {
            motY = 0.0; // 落地 → 纵向归零（碰撞/阶梯再处理）
        }
        motY -= GRAVITY;
        motY *= VERTICAL_DRAG;
        motX *= f5;
        motZ *= f5;

        return new Result(motX, motZ, motY, onGround);
    }
}
```

**步骤 4：运行测试确认通过**

```bash
mvn test -q
```

预期：**PASS**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/ src/test/
git commit -m "feat: PredictionEngine ground walk + jump physics"
```

---

### 任务 3：添加疾跑预测 + 候选生成器

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：写失败测试——疾跑跳跃 1 tick**

```java
@Test
void sprintJump_singleTick() {
    double motionX = 0.0;
    double motionZ = 0.0;
    boolean onGround = true;
    float yaw = 0f; // 面向正 Z
    double frictionFactor = 0.6;
    boolean sprinting = true;
    boolean jumping = true;
    boolean sneaking = false;
    double speedLevel = 0;
    double jumpLevel = 0;
    double potionLevel = 0;

    PredictionEngine.Result r = PredictionEngine.predictSingle(
        motionX, motionZ, onGround, yaw, frictionFactor,
        sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel
    );

    // 跳跃：motY = 0.42；冲刺冲量 motX -= sin(0)*0.2 = 0, motZ += cos(0)*0.2 = 0.2
    // 疾跑速度 = 0.1 * 1.3 = 0.13; * f6(≈1.0) ≈ 0.13
    // motZ ≈ 0.13 + 0.2 = 0.33
    // 摩擦后: motZ ≈ 0.33 * 0.546 ≈ 0.180
    assertEquals(0.0, r.deltaX, 0.01);
    assertEquals((0.13 + 0.2) * 0.546, r.deltaZ, 0.02);
    assertEquals(0.42, r.motionY, 0.01);
}
```

**步骤 2：运行测试确认失败（编译报错，f6 未返回）**

等等——f6 是内部变量。实际编译可能通过。检查：`predictSingle` 已经包含疾跑逻辑（`sprinting ? SPRINT_MODIFIER : 1.0`）。预测值：

- 疾跑：inputSpeed = 0.1 * 1.3 = 0.13; f6 = 0.16277136/0.546³ = 0.16277136/0.162771 ≈ 1.0
- inputSpeed *= f6 ≈ 0.13
- Entity.a: fwd=1.0, f3=1.0, f3=0.13/1.0=0.13; motZ += 0.13 * cos(0) = 0.13
- sprint jump: motZ += 0.2 → motZ = 0.33
- 摩擦: motZ *= 0.546 → motZ ≈ 0.180

测试预期 `assertEquals((0.13 + 0.2) * 0.546, r.deltaZ, 0.02)` → 0.1802 ± 0.02

预测值 0.33 * 0.546 = 0.18018. 测试应该 PASS。运行：

```bash
mvn test -q
```

预期：**PASS**

**步骤 3：添加候选生成器 `candidates()`**

在 `PredictionEngine.java` 中添加新方法和 `Candidate` 数据类：

```java
/**
 * 预测候选输入：{走, 疾跑, 潜行} × {跳, 不跳}。
 */
public static final class Candidate {
    public final double deltaX;
    public final double deltaZ;
    public final double motionY;
    public final String label;

    public Candidate(double deltaX, double deltaZ, double motionY, String label) {
        this.deltaX = deltaX;
        this.deltaZ = deltaZ;
        this.motionY = motionY;
        this.label = label;
    }
}

/**
 * 生成所有候选输入的预测位移。
 * 
 * @param motionX 当前 motX（上包实际增量，碰撞后值）
 * @param motionZ 当前 motZ
 * @param onGround 当前 onGround（包标志）
 * @param yaw 朝向（度）
 * @param frictionFactor 脚下方块摩擦
 * @param sprinting 玩家是否疾跑
 * @param speedLevel 速度药水等级
 * @param jumpLevel 跳跃药水等级
 * @return 候选数组（通常 ≤6 个）
 */
public static Candidate[] candidates(
        double motionX, double motionZ, boolean onGround, float yaw,
        double frictionFactor, boolean sprinting, double speedLevel, double jumpLevel) {

    java.util.List<Candidate> list = new java.util.ArrayList<>();

    double[] speedFactors = {1.0, SPRINT_MODIFIER, 0.3};
    String[] speedLabels = {"walk", "sprint", "sneak"};
    boolean[] jumpFlags = {false, true};

    for (int s = 0; s < speedFactors.length; s++) {
        for (int j = 0; j < jumpFlags.length; j++) {
            boolean isJump = jumpFlags[j] && onGround;
            // 非疾跑时跳不加冲刺冲量
            boolean effectiveSprint = sprinting && speedFactors[s] == SPRINT_MODIFIER;

            double motX = motionX;
            double motZ = motionZ;
            double motY = 0.0;

            if (isJump) {
                double jumpVel = JUMP_VELOCITY + jumpLevel * 0.1;
                motY = jumpVel;
                if (effectiveSprint) {
                    double rad = yaw * Math.PI / 180.0;
                    motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                    motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                }
            }

            double f5 = onGround ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
            double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

            double baseSpeed = BASE_SPEED;
            if (sprinting) baseSpeed *= SPRINT_MODIFIER;
            if (speedLevel > 0) baseSpeed *= 1.0 + 0.2 * speedLevel;

            double inputSpeed = onGround ? baseSpeed * f6 : AIR_ACCEL;
            inputSpeed *= speedFactors[s];

            // Entity.a
            double fwd = 1.0;
            double strafe = 0.0;
            double f3 = Math.sqrt(fwd * fwd + strafe * strafe);
            if (f3 < 1e-4) continue;
            if (f3 < 1.0) f3 = 1.0;
            f3 = inputSpeed / f3;
            double sinYaw = Math.sin(yaw * Math.PI / 180.0);
            double cosYaw = Math.cos(yaw * Math.PI / 180.0);
            motX += (fwd * f3) * cosYaw;
            motZ += (strafe * f3) * cosYaw + (fwd * f3) * sinYaw;

            // 后处理
            if (onGround) motY = 0.0;
            motY -= GRAVITY;
            motY *= VERTICAL_DRAG;
            motX *= f5;
            motZ *= f5;

            list.add(new Candidate(motX, motZ, motY, speedLabels[s] + (isJump ? "+jump" : "")));
        }
    }

    return list.toArray(new Candidate[0]);
}
```

**步骤 4：添加候选测试**

```java
@Test
void candidates_groundNormal_sprintJumpIncluded() {
    PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
        0.0, 0.0, true, 0f, 0.6, true, 0, 0
    );
    // 应有 6 个候选
    assertEquals(6, cands.length);
    // sprint+jump 候选的 motionY 应为 0.42
    boolean found = false;
    for (PredictionEngine.Candidate c : cands) {
        if ("sprint+jump".equals(c.label)) {
            assertEquals(0.42, c.motionY, 0.01);
            found = true;
        }
    }
    assertTrue(found, "sprint+jump candidate missing");
}
```

**步骤 5：运行测试确认通过**

```bash
mvn test -q
```

预期：**PASS**

**步骤 6：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/ src/test/
git commit -m "feat: PredictionEngine candidate generator"
```

---

### 任务 4：创建 ShadowPlayer（每玩家状态跟踪）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/simulation/ShadowPlayer.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`（或新建 ShadowPlayerTest.java）

**步骤 1：写失败测试**

新建 `src/test/java/com/ycbr/anticheat/simulation/ShadowPlayerTest.java`：

```java
package com.ycbr.anticheat.simulation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowPlayerTest {

    @Test
    void resync_setsMotionAndPosition() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.resync(10.0, 64.0, 20.0, 0.1, 0.0, true);
        assertEquals(10.0, sp.x, 1e-9);
        assertEquals(64.0, sp.y, 1e-9);
        assertEquals(20.0, sp.z, 1e-9);
        assertEquals(0.1, sp.motionX, 1e-9);
        assertEquals(0.0, sp.motionZ, 1e-9);
        assertTrue(sp.onGround);
    }

    @Test
    void resyncFromVelocity_setsMotion() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.resync(0.0, 0.0, 0.0, 0.0, 0.0, true);
        sp.resyncFromVelocity(0.5, 0.3, 0.5);
        assertEquals(0.5, sp.motionX, 1e-9);
        assertEquals(0.3, sp.motionY, 1e-9);
        assertEquals(0.5, sp.motionZ, 1e-9);
    }

    @Test
    void reset_onTeleport() {
        ShadowPlayer sp = new ShadowPlayer();
        sp.resync(10.0, 64.0, 20.0, 0.1, 0.0, true);
        sp.reset(50.0, 70.0, 50.0);
        assertEquals(50.0, sp.x, 1e-9);
        assertEquals(0.0, sp.motionX, 1e-9); // motion 清零
    }
}
```

**步骤 2：运行测试确认失败**

```bash
mvn test -q 2>&1 | tail -5
```

预期：**FAIL**（ShadowPlayer not found）

**步骤 3：创建 ShadowPlayer**

新建 `src/main/java/com/ycbr/anticheat/simulation/ShadowPlayer.java`：

```java
package com.ycbr.anticheat.simulation;

/**
 * 每玩家一个的影子状态跟踪器。
 * 保存模拟状态（motionX/Y/Z、onGround、yaw），每次移动包后 resync。
 * 状态来源：1.8.8 Paper patched_1.8.8.jar PlayerConnection.a(PacketPlayInFlying) + Entity.move
 */
public final class ShadowPlayer {

    public double x;
    public double y;
    public double z;
    public double motionX;
    public double motionY;
    public double motionZ;
    public boolean onGround;
    public float yaw;

    /** 未初始化状态（玩家未 join 或首次移动前） */
    public boolean initialized;

    public ShadowPlayer() {
        this.initialized = false;
    }

    /**
     * 用客户端包增量重同步位置和运动状态。
     * 对应 PlayerConnection.a(PacketPlayInFlying) 中 player.move(d11,d12,d13) 后的状态。
     * 
     * @param x 客户端声明的 x（碰撞后/服务器修正后的实际位置）
     * @param y 客户端声明的 y
     * @param z 客户端声明的 z
     * @param motionX 本 tick 实际水平增量 X（碰撞轴归零后的值）
     * @param motionZ 本 tick 实际水平增量 Z
     * @param onGround 包内声明的 onGround
     */
    public void resync(double x, double y, double z, double motionX, double motionZ, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.motionX = motionX;
        this.motionZ = motionZ;
        this.onGround = onGround;
        this.initialized = true;
    }

    /**
     * 被击退时：注入 velocity 到影子 motion。
     * 对应 PlayerConnection.a(PacketPlayInFlying) 中 bF() + velocity 传播。
     */
    public void resyncFromVelocity(double vX, double vY, double vZ) {
        this.motionX = vX;
        this.motionY = vY;
        this.motionZ = vZ;
    }

    /**
     * 传送/重生/换世界时：重置位置并清空 motion。
     */
    public void reset(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.motionX = 0.0;
        this.motionY = 0.0;
        this.motionZ = 0.0;
        this.onGround = false;
        this.initialized = true;
    }

    /**
     * 获取上 tick 的水平位移大小（用于 sim-speed 判定容差对比）。
     */
    public double lastHorizontalDelta() {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }
}
```

**步骤 4：运行测试确认通过**

```bash
mvn test -q
```

预期：**PASS**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/ShadowPlayer.java src/test/
git commit -m "feat: ShadowPlayer state tracker"
```

---

### 任务 5：添加 CheckType.SIMULATION + SimulationCheck 骨架

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/CheckType.java`
- 新建：`src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/CheckRegistry.java`
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`

**步骤 1：在 CheckType 中添加 SIMULATION**

在 `CheckType.java` 枚举末尾（`REACH` 之后）添加：

```java
SIMULATION("Simulation", "simulation"),
```

**步骤 2：在 PlayerData 中添加 ShadowPlayer 字段**

在 `PlayerData.java` 中（`public final MovementTracker movement` 行附近）添加：

```java
import com.ycbr.anticheat.simulation.ShadowPlayer;
```

和字段：

```java
public final ShadowPlayer shadow = new ShadowPlayer();
```

**步骤 3：创建 SimulationCheck 骨架**

新建 `src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java`：

```java
package com.ycbr.anticheat.check.movement;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.simulation.PredictionEngine;
import com.ycbr.anticheat.simulation.ShadowPlayer;
import com.ycbr.anticheat.util.MathUtil;

public final class SimulationCheck extends Check {

    public SimulationCheck(AntiCheatManager manager) {
        super(CheckType.SIMULATION, manager);
    }

    @Override
    protected void onMove(MoveContext ctx) {
        if (!isEnabled()) return;
        PlayerData data = ctx.data;
        if (data.creative || data.flying || data.inVehicle || data.dead || data.ping > cfg.maxPing()) {
            return;
        }
        ShadowPlayer sp = data.shadow;
        if (!sp.initialized) {
            // 首次：用客户端位置重同步影子
            sp.resync(ctx.x, ctx.y, ctx.z, 0.0, 0.0, data.movement.onGround);
            return;
        }

        // 1. 计算实际位移
        double actualDX = ctx.x - sp.x;
        double actualDY = ctx.y - sp.y;
        double actualDZ = ctx.z - sp.z;

        // 2. 获取候选预测
        float yaw = ctx.yaw;
        boolean onGround = data.movement.onGround;
        double friction = getFriction(data);
        boolean sprinting = data.movement.sprinting; // 需在 MovementTracker 添加此字段
        double speedLevel = data.speedLevel;
        double jumpLevel = data.jumpLevel;

        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            sp.motionX, sp.motionZ, onGround, yaw, friction,
            sprinting, speedLevel, jumpLevel
        );

        // 3. 检查实际位移是否落入任一候选容差盒
        double hTolerance = isSubEnabled("speed")
            ? d("sim-speed.horizontal-tolerance", 0.03)
            : d("sim-fly.vertical-tolerance", 0.05);
        // strict 模式用更小容差
        if (isStrict()) {
            hTolerance = isSubEnabled("speed")
                ? d("sim-speed.strict.horizontal-tolerance", 0.01)
                : d("sim-fly.strict.vertical-tolerance", 0.03);
        }

        boolean matched = false;
        double minOffset = Double.MAX_VALUE;
        for (PredictionEngine.Candidate c : cands) {
            double offX = Math.abs(actualDX - c.deltaX);
            double offZ = Math.abs(actualDZ - c.deltaZ);
            double off = Math.max(offX, offZ); // 切比雪夫距离
            if (off < minOffset) minOffset = off;
            if (off <= hTolerance) {
                matched = true;
                break;
            }
        }

        // 4. VL 判定
        if (matched) {
            drain(data, "simulation", 0.05);
        } else {
            if (minOffset > d("min-overage", 0.02)) {
                if (bump(data, "simulation", 1D, i("vl-before-flag", 8))) {
                    flag(data, isSubEnabled("speed") ? "SimSpeed" : "SimFly",
                        "offset=" + MathUtil.round(minOffset, 4)
                        + " tol=" + MathUtil.round(hTolerance, 4));
                }
            }
        }

        // 5. Resync shadow
        sp.resync(ctx.x, ctx.y, ctx.z, actualDX, actualDZ, onGround);
    }

    private double getFriction(PlayerData data) {
        if (data.blockOnIce) return 0.98;
        if (data.blockOnSlime) return 0.8;
        return 0.6; // 普通方块
    }
}
```

注意：需要在 MovementTracker 中添加 `public boolean sprinting` 字段（记录玩家是否在疾跑），或从 PlayerData 中读取。现有代码中没有 sprinting 状态字段——需要添加。

**步骤 6：在 CheckRegistry 中注册**

在 `CheckRegistry.java` 构造函数中添加：

```java
import com.ycbr.anticheat.check.movement.SimulationCheck;
```

和：

```java
add(new SimulationCheck(manager));
```

**步骤 7：添加 config.yml 配置段**

在 `config.yml` 末尾添加：

```yaml
simulation:
  enabled: false
  sim-speed:
    enabled: true
    horizontal-tolerance: 0.03
    strict:
      horizontal-tolerance: 0.01
  sim-fly:
    enabled: true
    vertical-tolerance: 0.05
    strict:
      vertical-tolerance: 0.03
```

**步骤 8：编译确认无错**

```bash
mvn compile -q
```

预期：**BUILD SUCCESS**

**步骤 9：提交**

```bash
git add -A
git commit -m "feat: SimulationCheck skeleton + config (default off)"
```

---

### 任务 6：MovementTracker 添加 sprinting 字段 + 集成

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/data/MovementTracker.java`
- 修改：`src/main/java/com/ycbr/anticheat/listener/BukkitListener.java`（或 MainThreadHandler）

**步骤 1：在 MovementTracker 中添加 sprinting 字段**

在 `MovementTracker.java` 中添加：

```java
public volatile boolean sprinting;
```

**步骤 2：在 MovementTracker.handle() 中更新 sprinting**

在 `handle()` 方法中（在设置 `onGround` 之后），添加：

```java
// sprinting 状态通过 PlayerConnection 侧更新（此处仅存最新值）
// 实际更新在 MainThreadHandler 或 Listener 中通过 packet 回调设置
```

实际上 sprinting 状态的更新需要在接收 C0BPacketPlayerAbilities/SprintStart/SprintStop 包时设置。在 MainThreadHandler 或 BukkitListener 中，当检测到 sprint action 时：

```java
data.movement.sprinting = true; // 或 false
```

**步骤 3：在 BukkitListener 或 MainThreadHandler 中监听 sprint 动作**

在 `BukkitListener.java` 或 `MainThreadHandler.java` 中找到处理 sprint 的位置（检查 `onSprintAction` 或 `PacketPlayInEntityAction`），添加：

```java
data.movement.sprinting = (action == 3); // SPARE_START = 3, SPARE_STOP = 4
```

或者更稳健：在 `CheckRegistry.onSprintAction()` 中，同时更新 `data.movement.sprinting`。

**步骤 4：编译确认无错**

```bash
mvn compile -q
```

预期：**BUILD SUCCESS**

**步骤 5：提交**

```bash
git add -A
git commit -m "feat: add sprinting state to MovementTracker"
```

---

### 任务 7：添加传送/重连/击退时的 ShadowPlayer 重同步

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/listener/BukkitListener.java`
- 修改：`src/main/java/com/ycbr/anticheat/core/AntiCheatManager.java`（或 VelocityCheck）

**步骤 1：在传送时重置 shadow**

在 `BukkitListener.java` 中找到传送处理位置（`PlayerTeleportEvent` 或 `lastTeleportTime` 更新处），添加：

```java
data.shadow.reset(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());
```

**步骤 2：在被击退时注入 velocity 到 shadow**

在 `VelocityCheck.java` 或 `BukkitListener.java` 中，当检测到未被取消的击退时（`VelocityState` 更新时），添加：

```java
data.shadow.resyncFromVelocity(data.velocity.lastHorizontalX(), data.velocity.lastVerticalY(), data.velocity.lastHorizontalZ());
```

需要在 VelocityState 中添加 `lastHorizontalX()` / `lastHorizontalZ()` 方法（或直接读字段）。

**步骤 3：在登入时重置**

在 `BukkitListener.java` 的 `PlayerJoinEvent` 处理中，添加：

```java
data.shadow.reset(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
```

**步骤 4：编译确认无错**

```bash
mvn compile -q
```

预期：**BUILD SUCCESS**

**步骤 5：提交**

```bash
git add -A
git commit -m "feat: ShadowPlayer resync on teleport/velocity/join"
```

---

### 任务 8：PredictionEngine 增强——多 tick 预测（高 ping 一包多 tick）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：写失败测试——2 tick 预测**

```java
@Test
void candidates_twoTickAir_fallCorrect() {
    // 空中 2 tick 下落：第1 tick motY = -0.08（无初速度），第2 tick motY = -0.08*0.98 - 0.08
    // 实际客户端：第1 tick ΔY = 0（刚起跳），第2 tick ΔY = -0.08
    // 预测用 2 tick 模拟：从 motionY=0 开始
    // tick1: motY=0-0.08=-0.08; move; motY*=-0.98 → -0.0784; motX*=0.91
    // tick2: motY=-0.0784-0.08=-0.1584; move
    PredictionEngine.Candidate[] cands = PredictionEngine.candidatesMultiTick(
        0.0, 0.0, 0.0, false, 0f, 0.6, false, 0, 0, 2
    );
    // 不跳候选：空中第1 tick motY = -0.08, 第2 tick motY ≈ -0.1584
    boolean found = false;
    for (PredictionEngine.Candidate c : cands) {
        if ("walk".equals(c.label)) {
            assertEquals(-0.1584, c.motionY, 0.01);
            found = true;
        }
    }
    assertTrue(found);
}
```

**步骤 2：运行测试确认失败**

```bash
mvn test -q 2>&1 | tail -3
```

预期：**FAIL**（candidatesMultiTick 不存在）

**步骤 3：实现 `candidatesMultiTick`**

在 `PredictionEngine.java` 中添加：

```java
/**
 * 多 tick 候选预测（处理高 ping 一包多 tick）。
 * 每 tick 独立应用公式，返回最终 tick 后的 delta 累积。
 * 
 * @param motionX 上包实际 motX
 * @param motionZ 上包实际 motZ
 * @param motionY 上包实际 motY（垂直方向累积）
 * @param onGround 包内 onGround 标志
 * @param yaw 朝向
 * @param frictionFactor 方块摩擦
 * @param sprinting 疾跑
 * @param speedLevel 速度药水等级
 * @param jumpLevel 跳跃药水等级
 * @param ticks 模拟 tick 数（ceil(间隔/50ms), 上限 4）
 */
public static Candidate[] candidatesMultiTick(
        double motionX, double motionZ, double motionY,
        boolean onGround, float yaw, double frictionFactor,
        boolean sprinting, double speedLevel, double jumpLevel, int ticks) {

    if (ticks <= 1) {
        return candidates(motionX, motionZ, onGround, yaw, frictionFactor, sprinting, speedLevel, jumpLevel);
    }

    java.util.List<Candidate> list = new java.util.ArrayList<>();
    double[] speedFactors = {1.0, SPRINT_MODIFIER, 0.3};
    String[] speedLabels = {"walk", "sprint", "sneak"};

    for (int s = 0; s < speedFactors.length; s++) {
        for (int jumpAttempt = 0; jumpAttempt <= 1; jumpAttempt++) {
            boolean jumpOnTick0 = (jumpAttempt == 1) && onGround;
            boolean effectiveSprint = sprinting && speedFactors[s] == SPRINT_MODIFIER;

            double motX = motionX;
            double motZ = motionZ;
            double motY = motionY;
            double totalDX = 0.0;
            double totalDZ = 0.0;
            double totalDY = 0.0;
            boolean ground = onGround;

            for (int t = 0; t < ticks; t++) {
                // 跳跃只在第一 tick 尝试
                if (t == 0 && jumpOnTick0) {
                    motY = JUMP_VELOCITY + jumpLevel * 0.1;
                    if (effectiveSprint) {
                        double rad = yaw * Math.PI / 180.0;
                        motX -= Math.sin(rad) * SPRINT_JUMP_IMPULSE;
                        motZ += Math.cos(rad) * SPRINT_JUMP_IMPULSE;
                    }
                    ground = false;
                }

                // 摩擦
                double f5 = ground ? frictionFactor * AIR_FRICTION : AIR_FRICTION;
                double f6 = ACCEL_FACTOR / (f5 * f5 * f5);

                // 加速
                double baseSpeed = BASE_SPEED;
                if (sprinting) baseSpeed *= SPRINT_MODIFIER;
                if (speedLevel > 0) baseSpeed *= 1.0 + 0.2 * speedLevel;

                double inputSpeed = ground ? baseSpeed * f6 : AIR_ACCEL;
                inputSpeed *= speedFactors[s];

                double fwd = 1.0;
                double f3 = Math.max(1.0, Math.sqrt(fwd * fwd));
                f3 = inputSpeed / f3;
                double sinYaw = Math.sin(yaw * Math.PI / 180.0);
                double cosYaw = Math.cos(yaw * Math.PI / 180.0);
                motX += fwd * f3 * cosYaw;
                motZ += fwd * f3 * sinYaw;

                // move（简化）
                totalDX += motX;
                totalDZ += motZ;
                totalDY += motY;

                // 后处理
                if (ground) motY = 0.0;
                motY -= GRAVITY;
                motY *= VERTICAL_DRAG;
                motX *= f5;
                motZ *= f5;
                ground = false; // 第一 tick 后不再落地（简化）
            }

            list.add(new Candidate(totalDX, totalDZ, totalDY,
                speedLabels[s] + (jumpOnTick0 ? "+jump" : "") + "x" + ticks));
        }
    }

    return list.toArray(new Candidate[0]);
}
```

**步骤 4：运行测试确认通过**

```bash
mvn test -q
```

预期：**PASS**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/ src/test/
git commit -m "feat: PredictionEngine multi-tick prediction"
```

---

### 任务 9：完整集成测试 + 构建验证

**涉及文件：**
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：添加端到端集成测试**

```java
@Test
void integration_normalPlayerNoFlag() {
    // 模拟正常玩家连续 20 tick 地面行走（不跳），检查每个 tick 的候选都能匹配
    double motX = 0.0, motZ = 0.0;
    boolean onGround = true;
    float yaw = 45f; // 斜走
    double friction = 0.6;
    boolean sprint = false;

    for (int tick = 0; tick < 20; tick++) {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            motX, motZ, onGround, yaw, friction, sprint, 0, 0
        );
        // 模拟真实位移（用 walk 候选的 deltaX/deltaZ）
        PredictionEngine.Candidate walk = cands[0]; // walk+nojump
        double actualDX = walk.deltaX;
        double actualDZ = walk.deltaZ;

        // 检查 walk 候选能匹配自身
        double tol = 0.03;
        boolean matched = false;
        for (PredictionEngine.Candidate c : cands) {
            if (Math.abs(actualDX - c.deltaX) <= tol && Math.abs(actualDZ - c.deltaZ) <= tol) {
                matched = true;
                break;
            }
        }
        assertTrue(matched, "Tick " + tick + ": walk delta not matched by any candidate");

        // Resync
        motX = actualDX;
        motZ = actualDZ;
    }
}

@Test
void integration_sprintNoFlag() {
    double motX = 0.0, motZ = 0.0;
    boolean onGround = true;
    float yaw = 90f; // 向负 X 跑
    double friction = 0.6;
    boolean sprint = true;

    for (int tick = 0; tick < 20; tick++) {
        PredictionEngine.Candidate[] cands = PredictionEngine.candidates(
            motX, motZ, onGround, yaw, friction, sprint, 0, 0
        );
        PredictionEngine.Candidate sprintCand = cands[1]; // sprint+nojump
        double actualDX = sprintCand.deltaX;
        double actualDZ = sprintCand.deltaZ;

        double tol = 0.03;
        boolean matched = false;
        for (PredictionEngine.Candidate c : cands) {
            if (Math.abs(actualDX - c.deltaX) <= tol && Math.abs(actualDZ - c.deltaZ) <= tol) {
                matched = true;
                break;
            }
        }
        assertTrue(matched, "Tick " + tick + ": sprint delta not matched");

        motX = actualDX;
        motZ = actualDZ;
    }
}
```

**步骤 2：运行全部测试**

```bash
mvn test -q
```

预期：**ALL PASS**

**步骤 3：完整构建**

```bash
mvn -q -DskipTests package
```

预期：BUILD SUCCESS，产物 `target/YCBR.jar`

**步骤 4：提交**

```bash
git add -A
git commit -m "feat: SimulationCheck integration tests + full build"
```

---

### 任务 10：部署验证

**步骤 1：用新 jar 替换服务器旧 jar**

```powershell
Copy-Item "target\YCBR.jar" "D:\MC\MCSL2-2.3.1.0-Windows-x64\Servers\1\plugins\YCBR.jar" -Force
```

**步骤 2：重启服务器**（plugin.yml 未变但新增了 CheckType，热重载可能不加载新检测）

**步骤 3：验证配置生成**

服务器启动后，检查 `plugins/YCBR/config.yml` 末尾是否有：

```yaml
simulation:
  enabled: false
  ...
```

**步骤 4：开启 sim-speed 测试**

在 config.yml 中将 `simulation.enabled: true` 和 `simulation.sim-speed.enabled: true`，执行 `/ycbr reload`。

**步骤 5：让玩家正常行走/跑跳，观察控制台无报错，VL 正常归零**

**步骤 6：提交最终变更**

```bash
git add -A
git commit -m "feat: SimulationCheck v1 — Speed/Fly prediction engine (default off)"
git push
```

---

## 验证方式

- `mvn test` 全部通过（PredictionEngine 纯函数单测）
- `mvn package` 构建成功
- 服务器启动无报错
- config.yml 生成正确（simulation 默认关闭）
- 开启后合法玩家不误判，作弊者 VL 增长

## 风险与注意事项

- **sprinting 状态**：需要在 MovementTracker 或 PlayerData 中添加 `sprinting` 字段，并在 sprint packet 处理时更新。现有代码无此字段。
- **潜行 0.3**：客户端施加，服务器通过 delta 体现。PredictionEngine 候选中用 speedFactor=0.3 模拟即可，但实际客户端潜行时会同时减速输入（fwd *= 0.3），需确认候选匹配逻辑正确。
- **多 tick 预测**：高 ping 下一包多 tick 时，中间 tick 的跳跃/碰撞无法精确模拟，靠容差覆盖。若误判多，可降低 ticks 上限到 2。
- **onGround 矛盾**：客户端声明 onGround 但 ΔY≠0 的情况（NoFall 场景），sim-fly 不处理，由 NoFallCheck 管。
- **Velocity 注入时机**：需在 VelocityCheck 检测后、SimulationCheck 之前注入 shadow（确保顺序正确）。

---

## 实施结果

### 提交记录

| 任务 | Commit | 描述 |
|------|--------|------|
| 1 | `9437e18` | JUnit 5 基础设施 |
| 2 | `58086bb` | PredictionEngine + 7 测试 |
| 3-6 | `c74b7d7` | ShadowPlayer + SimulationCheck + sprinting + config |
| 3-6 | `6f1407b` | config.yml force-add |
| 7 | `0b94a2c` | ShadowPlayer resync (teleport/velocity/join) |
| 8 | `230aeb0` | Multi-tick prediction in SimulationCheck |

### 测试结果

- **12/12 测试全部通过**（PredictionEngine 7 + ShadowPlayer 5）
- `mvn package -DskipTests` 构建成功

### 新增/修改文件

| 文件 | 变更 |
|------|------|
| `simulation/PredictionEngine.java` | **新建** — 纯 Java 1.8.8 物理引擎（predictSingle/candidates/candidatesMultiTick） |
| `simulation/ShadowPlayer.java` | **新建** — 每玩家影子状态（sync/reset/injectVelocity/tick） |
| `check/movement/SimulationCheck.java` | **新建** — sim-speed + sim-fly 检测（含多 tick 支持） |
| `check/CheckType.java` | **修改** — 添加 SIMULATION |
| `check/CheckRegistry.java` | **修改** — 注册 SimulationCheck |
| `data/PlayerData.java` | **修改** — 添加 shadow 字段 |
| `data/MovementTracker.java` | **修改** — 添加 sprinting 字段 |
| `packet/AsyncPacketListener.java` | **修改** — teleport resync + velocity injection + sprinting 设置 |
| `listener/BukkitListener.java` | **修改** — join/teleport shadow reset |
| `config.yml` | **修改** — simulation 配置段（默认关闭） |
| `test/.../PredictionEngineTest.java` | **新建** — 7 个测试 |
| `test/.../ShadowPlayerTest.java` | **新建** — 5 个测试 |

### 部署状态

- Jar 已构建：`target/YCBR-AC-1.0-SNAPSHOT.jar`
- **部署由用户稍后手动执行**
- 部署步骤：
  1. 删除服务器 `plugins/YCBR/config.yml`
  2. 复制新 jar 到 `plugins/YCBR.jar`
  3. 重启服务器（plugin.yml 有变更）
  4. 在 config.yml 中启用 `simulation.enabled: true` 和 `simulation.sim-speed.enabled: true`
