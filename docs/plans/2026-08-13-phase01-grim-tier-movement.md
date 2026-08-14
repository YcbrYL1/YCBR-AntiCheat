# 移动类补强至 Grim 级（WorldProbe + 事务化 + 统计基础设施）实施计划

> **给 Claude：** 按任务逐项执行本计划，每个任务完成后验证通过再提交。

**目标：** 在现有 PredictionEngine/ShadowPlayer/SimulationCheck（初级版 Grim）基础上，补齐世界交互层（方块碰撞/液体/网/梯子/动态摩擦）、不信任客户端 onGround、垂直模拟修正、容差收紧，并新增 Transaction 延迟追踪、灵敏度校准、统计工具库三项基础设施，为后续协议事务化（Phase 2）与战斗统计层（Phase 3）铺路。

**架构方案：** WorldProbe 作为"世界查询门面"（主线程同步调用、结果缓存）供 PredictionEngine 消费；PredictionEngine 从"摩擦常量"升级为"方块状态 + 碰撞判定"输入；ShadowPlayer 自判 onGround 不再信任客户端；TransactionTracker 用客户端-服务器事务往返替代 wall-clock/ping 估算；SensitivityProcessor 用旋转 GCD 反推灵敏度；Statistics 为无状态纯函数工具库。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3、ProtocolLib（事务包）

---

### 任务 1：新建 util/Statistics.java 统计工具库（Phase 0.3）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/util/Statistics.java`
- 测试：`src/test/java/com/ycbr/anticheat/util/StatisticsTest.java`

**步骤 1：写失败测试**

```java
package com.ycbr.anticheat.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class StatisticsTest {

    @Test
    void average_handlesEmptyAndNormal() {
        assertEquals(0.0, Statistics.average(new ArrayList<Double>()), 1e-9);
        assertEquals(2.0, Statistics.average(Arrays.asList(1.0, 2.0, 3.0)), 1e-9);
    }

    @Test
    void varianceAndStdDev() {
        List<Double> xs = Arrays.asList(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        double var = Statistics.variance(xs);
        assertTrue(var > 4.0 && var < 5.0, "variance=" + var);
        assertEquals(Math.sqrt(var), Statistics.standardDeviation(xs), 1e-9);
    }

    @Test
    void shannonEntropy_lowForConstantHighForUniform() {
        List<Double> constant = Arrays.asList(1.0, 1.0, 1.0, 1.0);
        List<Double> uniform = Arrays.asList(1.0, 2.0, 3.0, 4.0);
        double hConst = Statistics.shannonEntropy(constant);
        double hUni = Statistics.shannonEntropy(uniform);
        assertTrue(hConst < 0.05, "constant entropy=" + hConst);
        assertTrue(hUni > 1.9, "uniform entropy=" + hUni);
    }

    @Test
    void kurtosis_negativeForMechanicalPattern() {
        List<Double> mechanical = Arrays.asList(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);
        List<Double> organic = Arrays.asList(50.0, 62.0, 41.0, 70.0, 33.0, 55.0, 48.0, 65.0);
        double kMechanical = Statistics.kurtosis(mechanical);
        double kOrganic = Statistics.kurtosis(organic);
        assertTrue(kMechanical < 0.0, "mechanical kurtosis=" + kMechanical);
        assertTrue(kOrganic > kMechanical);
    }

    @Test
    void iqr() {
        List<Double> xs = Arrays.asList(1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0);
        assertEquals(6.0, Statistics.iqr(xs), 1e-9); // Q1=3, Q3=9
    }

    @Test
    void zScoreOutliers_detectsFarValue() {
        List<Double> xs = new ArrayList<Double>(Arrays.asList(10.0, 12.0, 11.0, 13.0, 10.5, 11.5, 12.5, 60.0));
        List<Double> outliers = Statistics.zScoreOutliers(xs, 3.0);
        assertEquals(1, outliers.size());
        assertEquals(60.0, outliers.get(0), 1e-9);
    }

    @Test
    void kolmogorovSmirnov_uniformVsConstant() {
        List<Double> uniform = new ArrayList<Double>();
        for (int i = 0; i < 100; i++) uniform.add((double) (i % 10));
        List<Double> constant = new ArrayList<Double>(Arrays.asList(1.0, 1.0, 1.0, 1.0));
        double dUniform = Statistics.kolmogorovSmirnov(uniform, uniform); // 同分布 → 小
        double dConst = Statistics.kolmogorovSmirnov(uniform, constant); // 异分布 → 大
        assertTrue(dUniform < 0.05);
        assertTrue(dConst > 0.5);
    }

    @Test
    void jiffDelta_countsRepeatedSequences() {
        List<Double> xs = Arrays.asList(1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0, 2.0, 3.0);
        assertTrue(Statistics.jiffDelta(xs, 3) >= 2);
    }
}
```

**步骤 2：运行测试确认失败**

运行：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" test -q -Dtest=StatisticsTest`
预期：**COMPILATION ERROR**（Statistics 类不存在）

**步骤 3：实现 Statistics.java**

```java
package com.ycbr.anticheat.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Statistics {

    private Statistics() {}

    public static double average(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double x : xs) sum += x;
        return sum / xs.size();
    }

    public static double variance(List<Double> xs) {
        if (xs == null || xs.size() < 2) return 0.0;
        double mean = average(xs);
        double sumSq = 0.0;
        for (double x : xs) {
            double d = x - mean;
            sumSq += d * d;
        }
        return sumSq / (xs.size() - 1);
    }

    public static double standardDeviation(List<Double> xs) {
        return Math.sqrt(variance(xs));
    }

    public static double shannonEntropy(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        Map<Double, Integer> counts = new HashMap<Double, Integer>();
        for (double x : xs) {
            Integer c = counts.get(x);
            counts.put(x, c == null ? 1 : c + 1);
        }
        double entropy = 0.0;
        for (int c : counts.values()) {
            double p = (double) c / xs.size();
            if (p > 0.0) entropy -= p * (Math.log(p) / Math.log(2.0));
        }
        return entropy;
    }

    public static double kurtosis(List<Double> xs) {
        if (xs == null || xs.size() < 4) return 0.0;
        double mean = average(xs);
        double std = standardDeviation(xs);
        if (std < 1e-12) return -3.0; // 完全恒定 → 极度机械
        double n = xs.size();
        double m4 = 0.0;
        for (double x : xs) {
            double d = (x - mean) / std;
            m4 += d * d * d * d;
        }
        m4 /= n;
        double m2 = variance(xs) * (n - 1) / n; // 总体方差
        return m4 / (m2 * m2) - 3.0; // 超额峰度
    }

    public static double iqr(List<Double> xs) {
        if (xs == null || xs.size() < 2) return 0.0;
        List<Double> sorted = new ArrayList<Double>(xs);
        Collections.sort(sorted);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        return q3 - q1;
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        double rank = p * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted.get(lo);
        double frac = rank - lo;
        return sorted.get(lo) * (1.0 - frac) + sorted.get(hi) * frac;
    }

    public static List<Double> zScoreOutliers(List<Double> xs, double threshold) {
        List<Double> outliers = new ArrayList<Double>();
        if (xs == null || xs.size() < 3) return outliers;
        double mean = average(xs);
        double std = standardDeviation(xs);
        if (std < 1e-12) return outliers;
        for (double x : xs) {
            if (Math.abs(x - mean) / std > threshold) outliers.add(x);
        }
        return outliers;
    }

    public static double kolmogorovSmirnov(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 1.0;
        List<Double> sa = new ArrayList<Double>(a);
        List<Double> sb = new ArrayList<Double>(b);
        Collections.sort(sa);
        Collections.sort(sb);
        int i = 0, j = 0;
        double maxDiff = 0.0;
        while (i < sa.size() && j < sb.size()) {
            double diff = Math.abs((double) (i + 1) / sa.size() - (double) (j + 1) / sb.size());
            if (diff > maxDiff) maxDiff = diff;
            if (sa.get(i) <= sb.get(j)) i++;
            else j++;
        }
        return maxDiff;
    }

    public static int jiffDelta(List<Double> xs, int patternLen) {
        if (xs == null || xs.size() < patternLen * 2) return 0;
        int repeats = 0;
        for (int start = 0; start + patternLen * 2 <= xs.size(); start++) {
            boolean match = true;
            for (int k = 0; k < patternLen; k++) {
                if (!xs.get(start + k).equals(xs.get(start + patternLen + k))) {
                    match = false;
                    break;
                }
            }
            if (match) repeats++;
        }
        return repeats;
    }
}
```

**步骤 4：运行测试确认通过**

运行：`mvn test -q -Dtest=StatisticsTest`
预期：**全部通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/util/Statistics.java src/test/java/com/ycbr/anticheat/util/StatisticsTest.java
git commit -m "feat: add Statistics utility library (entropy/kurtosis/IQR/KS/z-score/jiff)"
```

---

### 任务 2：新建 core/SensitivityProcessor.java 灵敏度校准（Phase 0.2）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/core/SensitivityProcessor.java`
- 测试：`src/test/java/com/ycbr/anticheat/core/SensitivityProcessorTest.java`

**步骤 1：写失败测试**

```java
package com.ycbr.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SensitivityProcessorTest {

    private static final double TAU = Math.PI * 2;

    private List<Double> syntheticRotations(int sensitivity, int n) {
        List<Double> out = new ArrayList<Double>();
        double step = TAU / sensitivity / 1.0; // 每个灵敏度对应一个 gcd
        double cur = 0.0;
        for (int i = 0; i < n; i++) {
            cur += step;
            out.add(cur);
        }
        return out;
    }

    @Test
    void calculateSensitivity_recoversIntensity() {
        SensitivityProcessor sp = new SensitivityProcessor();
        for (int sens = 30; sens <= 150; sens += 20) {
            double s = sp.calculateSensitivity(syntheticRotations(sens, 40));
            assertTrue(Math.abs(s - sens) <= sens * 0.2,
                    "sens=" + sens + " got=" + s);
        }
    }

    @Test
    void inRange_only20to150() {
        SensitivityProcessor sp = new SensitivityProcessor();
        assertTrue(sp.inRange(30));
        assertTrue(sp.inRange(150));
        assertFalse(sp.inRange(10));
        assertFalse(sp.inRange(200));
    }
}
```

**步骤 2：运行测试确认失败**

运行：`mvn test -q -Dtest=SensitivityProcessorTest`
预期：**COMPILATION ERROR**（类不存在）

**步骤 3：实现 SensitivityProcessor.java**

思路（借鉴 MX）：对旋转序列取相邻差值，取众数/最小间距作为 GCD（即 360°/灵敏度对应的弧度步长），灵敏度 = 2π / gcd。取序列差值的最大公约数近似。

```java
package com.ycbr.anticheat.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从旋转序列反推鼠标灵敏度（借鉴 MX SensitivityProcessor）。
 * 有效区间 [20,150]，区间外检测不执行。
 */
public final class SensitivityProcessor {

    private static final double MIN_SENS = 20.0;
    private static final double MAX_SENS = 150.0;
    private static final double TAU = Math.PI * 2.0;

    public double calculateSensitivity(List<Double> rotations) {
        if (rotations == null || rotations.size() < 4) return -1.0;
        Map<Long, Integer> buckets = new HashMap<Long, Integer>();
        for (int i = 1; i < rotations.size(); i++) {
            double diff = Math.abs(rotations.get(i) - rotations.get(i - 1));
            if (diff < 1e-9) continue;
            long bucket = Math.round(diff * 1e6);
            Integer c = buckets.get(bucket);
            buckets.put(bucket, c == null ? 1 : c + 1);
        }
        long best = -1;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : buckets.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best <= 0) return -1.0;
        double sens = TAU / (best / 1e6);
        if (sens < MIN_SENS || sens > MAX_SENS) return -1.0;
        return sens;
    }

    public boolean inRange(double sensitivity) {
        return sensitivity >= MIN_SENS && sensitivity <= MAX_SENS;
    }
}
```

**步骤 4：运行测试确认通过**（若 gcd 恢复不准，调整桶精度 1e6 → 1e5，或改用众数+平均）

运行：`mvn test -q -Dtest=SensitivityProcessorTest`
预期：**通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/core/SensitivityProcessor.java src/test/java/com/ycbr/anticheat/core/SensitivityProcessorTest.java
git commit -m "feat: add SensitivityProcessor (GCD-based sensitivity calibration)"
```

---

### 任务 3：新建 simulation/WorldProbe.java 世界交互层（Phase 1.1 核心）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/simulation/WorldProbe.java`

**步骤 1：设计接口（无需测试，Bukkit 依赖无法单测）**

```java
package com.ycbr.anticheat.simulation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 世界查询门面：主线程同步调用，结果带 ttl 缓存。
 * 只读世界，不做任何修改。
 */
public final class WorldProbe {

    public enum Surface {
        NORMAL(0.6),      // 普通方块（含草方块/石头等）
        ICE(0.98),        // 冰、浮冰
        SLIME(0.8),       // 粘液块
        SOUL_SAND(0.4),   // 灵魂沙
        AIR(0.91);        // 空中/无脚下方块

        public final double friction;
        Surface(double friction) {
            this.friction = friction;
        }
    }

    public static final class ProbeResult {
        public Surface surface;
        public boolean inLiquid;    // 水/熔岩
        public boolean inWeb;
        public boolean onLadder;    // 梯子/藤蔓
        public boolean headBlocked; // 头顶有碰撞盒（boxedIn）
        public double ladderY;
    }

    public static ProbeResult probe(Player player) {
        // 实现要点：
        // 1. 脚下方块：player.getLocation().add(0, -0.5, 0).getBlock() → Surface 映射
        //    - 冰/浮冰(ICE_2/ICE/STATIONARY_WATER? 见 BlockIce) → ICE
        //    - 粘液块(SLIME_BLOCK) → SLIME
        //    - 灵魂沙(SOUL_SAND) → SOUL_SAND
        //    - 否则 → NORMAL
        // 2. 液体：Material 属于 WATER/STATIONARY_WATER/LAVA/STATIONARY_LAVA → inLiquid
        // 3. 蜘蛛网：WEB → inWeb
        // 4. 梯子：LADDER/VINE → onLadder（取 y 增量）
        // 5. 头顶：getEyeLocation().add(0, 0.5, 0) 上方方块有碰撞 → headBlocked
        // 6. 结果缓存 5 tick（ttl），WorldProbe 实例存 PlayerData
    }

    public static Surface surfaceFor(Material m) {
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.FROSTED_ICE) return Surface.ICE;
        if (m == Material.SLIME_BLOCK) return Surface.SLIME;
        if (m == Material.SOUL_SAND) return Surface.SOUL_SAND;
        return Surface.NORMAL;
    }

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.STATIONARY_WATER
                || m == Material.LAVA || m == Material.STATIONARY_LAVA;
    }
}
```

**步骤 2：实现完整 probe() 方法**

- `WorldProbe` 实例字段：`Player player`、`long cacheExpiry`、`ProbeResult cached`
- `probe()` 若未过期直接返回缓存；过期则重新查询并缓存
- 方块查询全部在主线程（调用方保证），方法内不做异步

**步骤 3：编译确认无错**

运行：`mvn compile -q`
预期：**BUILD SUCCESS**

**步骤 4：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/WorldProbe.java
git commit -m "feat: add WorldProbe (surface/liquid/web/ladder/collision probe with ttl cache)"
```

---

### 任务 4：PredictionEngine 接收方块状态（Phase 1.1 接入）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java`

**步骤 1：写失败测试——液体减速**

```java
@Test
void predictSingle_inLiquidSlowsDown() {
    PredictionEngine.Result ground = PredictionEngine.predictSingle(
        0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0, false, false, false, false);
    PredictionEngine.Result liquid = PredictionEngine.predictSingle(
        0.0, 0.0, true, 0f, 0.6, false, false, false, 0, 0, 0, true, false, false, false);
    // 液体中水平位移应显著小于地面
    double groundH = Math.hypot(ground.deltaX, ground.deltaZ);
    double liquidH = Math.hypot(liquid.deltaX, liquid.deltaZ);
    assertTrue(liquidH < groundH * 0.4, "liquidH=" + liquidH + " groundH=" + groundH);
}
```

**步骤 2：运行确认失败**（方法签名不变则编译错误）

**步骤 3：重载 predictSingle 增加世界状态参数**

```java
public static Result predictSingle(
        double motionX, double motionZ, boolean onGround, float yaw,
        double frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
        double speedLevel, double jumpLevel, double potionLevel) {
    return predictSingle(motionX, motionZ, onGround, yaw, frictionFactor,
            sprinting, jumping, sneaking, speedLevel, jumpLevel, potionLevel,
            false, false, false, false);
}

public static Result predictSingle(
        double motionX, double motionZ, boolean onGround, float yaw,
        double frictionFactor, boolean sprinting, boolean jumping, boolean sneaking,
        double speedLevel, double jumpLevel, double potionLevel,
        boolean inLiquid, boolean inWeb, boolean onLadder, boolean headBlocked) {
    // 实现要点：
    // - inLiquid：水平摩擦改用液体摩擦 f5 = 0.8（参考 NMS Entity.a 液体分支），
    //   且重力改为 -0.02*tick、垂直拖拽 0.8（水），熔岩重力更大
    // - inWeb：motX *= 0.05? 参考 NMS Web 分支：motX *= 0.105, motY *= 0.105
    // - onLadder：motY 允许攀爬（爬梯速度 +0.15），重力忽略
    // - headBlocked：跳跃包络 +0.3（跳过 1.8 方块头被挡时 motY 限制）
    // - 原始实现保留（无世界状态 → 纯空气/地面），新参数仅叠加修正
}
```

**步骤 4：同样为 candidates 增加重载（液体/网/梯子/头挡布尔集合）**

**步骤 5：运行全部测试确认通过**（旧 7 测试不受影响，新测试通过）

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 6：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java src/test/java/com/ycbr/anticheat/simulation/PredictionEngineTest.java
git commit -m "feat: PredictionEngine world-state overloads (liquid/web/ladder/head-blocked)"
```

---

### 任务 5：ShadowPlayer 自判 onGround（Phase 1.3）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/simulation/ShadowPlayer.java`
- 修改：`src/test/java/com/ycbr/anticheat/simulation/ShadowPlayerTest.java`

**步骤 1：写失败测试**

```java
@Test
void sync_ignoresClientOnGroundWhenServerSaysAirborne() {
    ShadowPlayer sp = new ShadowPlayer();
    // 客户端谎报 onGround=true，但位置差显示仍在下降
    sp.sync(0, 64.0, 0, 0.0, -0.4, 0.0, true, 0f, 100L);
    // 下一 tick：motionY 继续 -0.4 → 证明 shadow 未被客户端 onGround 污染
    sp.tick(0.6f, false, false, false, 0, 0, 0, false, false, false, false);
    assertTrue(sp.motionY < -0.3, "motionY=" + sp.motionY);
}
```

**步骤 2：运行确认失败**（当前 sync 直接接受客户端 onGround，tick 后 onGround=false 但 motionY 已归零）

**步骤 3：修改 ShadowPlayer**

```java
// sync() 签名增加 serverOnGround 参数（由 WorldProbe/服务器实际判定）：
public void sync(double x, double y, double z, double motX, double motY, double motZ,
                 boolean clientOnGround, boolean serverOnGround, float yaw, long time) {
    ...
    this.onGround = serverOnGround; // 只信服务器判定，不信客户端
    ...
}
// 保留旧签名（调用方先退化用 clientOnGround），标记 @Deprecated
```

**步骤 4：SimulationCheck.resyncShadow 改造**

```java
// 不再用 ctx.data.movement.onGround，而是：
WorldProbe.ProbeResult probe = data.worldProbe.probe(); // 或复用已缓存结果
boolean serverGround = serverOnGround(ctx, probe);
shadow.sync(ctx.x, ctx.y, ctx.z, motX, motY, motZ, ctx.data.movement.onGround, serverGround, yaw, ctx.arrivalTime);
```

其中 `serverOnGround`：若 `probe.surface == NORMAL/ICE/SLIME` 且 `Math.abs(ctx.y - shadow.posY) < 0.001`（位置差近似贴地）→ 地面，否则按碰撞盒判定（简化：`Math.abs(ctx.y % 1.0 - 0.0) < 0.05 || Math.abs(ctx.y % 1.0 - 0.5) < 0.05` 时可能贴地——1.8 半格对齐）。

**步骤 5：运行全部测试确认通过**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 6：提交**

```bash
git add src/main/java/com/ycbr/anticheat/simulation/ShadowPlayer.java src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java src/test/java/com/ycbr/anticheat/simulation/ShadowPlayerTest.java
git commit -m "feat: ShadowPlayer onGround from server truth, not client claims"
```

---

### 任务 6：SimulationCheck 接入 WorldProbe + 收紧容差（Phase 1.1/1.4）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：改造 SimulationCheck.onMove**

```java
// 不再在液体/网/梯子时 return（消除盲区），改为带上世界状态预测：
WorldProbe.ProbeResult probe = data.worldProbe.probe();
// 液体/网/梯子/头挡 → 传入 candidates 重载
// 容差按介质放大：液体/网/梯子时 hTol *= 2（预测精度下降，防误判）

// 多 tick 场景同样传入世界状态
```

**步骤 2：收紧默认容差**

config.yml `simulation` 段：

```yaml
simulation:
  enabled: false
  sim-speed:
    enabled: false
    horizontal-tolerance: 0.01        # 原 0.03
    liquid-tolerance-multiplier: 2.0  # 新增
    vl-before-flag: 8
    strict:
      horizontal-tolerance: 0.005
  sim-fly:
    enabled: false
    vertical-tolerance: 0.02          # 原 0.05
    strict:
      vertical-tolerance: 0.01
```

**步骤 3：编译 + 测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 4：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/movement/SimulationCheck.java src/main/resources/config.yml
git commit -m "feat: SimulationCheck uses WorldProbe, removes liquid/web/ladder blind spot, tighter tolerances"
```

---

### 任务 7：新建 core/TransactionTracker.java 事务延迟追踪（Phase 0.1）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/core/TransactionTracker.java`

**步骤 1：设计类（ProtocolLib 事务包）**

```java
package com.ycbr.anticheat.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.ycbr.anticheat.core.AntiCheatManager;

/**
 * 客户端-服务器事务往返追踪（借鉴 Grim LatencyHandler）。
 * 服务器发送 Transaction(id) → 客户端回 Transaction(id) → 计算 RTT。
 * 用于替换 wall-clock/ping 估算，供 Timer/Blink/Velocity 使用。
 */
public final class TransactionTracker {

    private final AntiCheatManager manager;
    private short nextId = 0;
    private final Map<Short, Long> sent = new ConcurrentHashMap<Short, Long>();
    private volatile double lastRttMs = 50.0;
    private volatile long lastPong = System.currentTimeMillis();

    public TransactionTracker(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void send() {
        // 主线程：构造 PacketPlayOutTransaction(windowId=0, action=nextId++, accepted=true)
        // 通过 ProtocolManager.sendServerPacket 发送，记录 sent.put(id, now)
        // 每 tick 最多 1 个（节流），超时 3s 未回则丢弃
    }

    public void onReceive(short id) {
        Long t0 = sent.remove(id);
        if (t0 != null) {
            lastRttMs = System.currentTimeMillis() - t0;
            lastPong = System.currentTimeMillis();
        }
    }

    public double rttMs() { return lastRttMs; }
    public long lastPongTime() { return lastPong; }

    /**
     * 玩家视角下的 tick 进度（以事务往返为准）：
     * 距上次 pong 已过 x ms → 客户端已处理约 x/50 tick。
     */
    public int clientTicksAhead() {
        return (int) Math.min(10, (System.currentTimeMillis() - lastPong) / 50L);
    }
}
```

**步骤 2：在 AsyncPacketListener 注册事务包监听**

- 出站：`PacketType.Play.Server.TRANSACTION` 记录发送时间
- 入站：`PacketType.Play.Client.TRANSACTION` 调用 `onReceive(id)`
- 接入 `PlayerData.transaction` 字段（PlayerData 新增）

**步骤 3：编译确认无错**

运行：`mvn compile -q`
预期：**BUILD SUCCESS**

**步骤 4：提交**

```bash
git add src/main/java/com/ycbr/anticheat/core/TransactionTracker.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/java/com/ycbr/anticheat/packet/AsyncPacketListener.java
git commit -m "feat: add TransactionTracker (client-server RTT via transaction packets)"
```

> **✅ 实施结果（2026-08-13）**
> - 已新建 `core/TransactionTracker.java`：每玩家实例，主线程每 tick（节流 ≥45ms）发 `PacketType.Play.Server.TRANSACTION`（windowId=0, action=自增 short, accepted=true），客户端回包时 `onReceive(short)` 计算 RTT 并以 EMA(0.7/0.3) 平滑。
> - 暴露 API：`rttMs()`（默认 50ms）、`lastPongTime()`、`clientTicksAhead()`（距 lastPong 的 tick 估计，上限 10）。
> - 玩家离线 >200 tick（≈10s）自停调度任务，避免任务泄漏；`sent` 用 `ConcurrentHashMap`，`lastRttMs/lastPong` 用 volatile。
> - `data.transaction(AntiCheatManager)` 懒初始化访问器（double-checked locking）；`AsyncPacketListener` 入站已加 `PacketType.Play.Client.TRANSACTION` 并在每次收包时确保 tracker 初始化，回包走 `handleTransaction`。
> - 编译与现有 12+ 测试全部通过（`mvn -o compile` / `mvn -o test`）。
> - 注：Phase 2 的 Timer/Blink/Velocity 事务化检测可直接消费本追踪器；建议用 `data.transaction(manager)` 获取实例。

---

### 任务 8：惩罚框架增强——攻击阻断 + setback + 交叉信号（Phase 0.4）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/Check.java`
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`
- 修改：`src/main/java/com/ycbr/anticheat/pipeline/Verdict.java`

**步骤 1：PlayerData 新增字段**

```java
public volatile long attackBlockedUntil;  // 攻击阻断截止时间（ms）
public final java.util.Set<String> crossSignals = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet();
public volatile long lastSetbackTime;
public volatile double setbackX, setbackY, setbackZ;
```

**步骤 2：Check 基类新增辅助方法**

```java
protected final void blockAttacks(PlayerData data, long ms) {
    data.attackBlockedUntil = Math.max(data.attackBlockedUntil,
            System.currentTimeMillis() + ms);
}

protected final boolean attacksBlocked(PlayerData data) {
    return System.currentTimeMillis() < data.attackBlockedUntil;
}

protected final void addSignal(PlayerData data, String signal) {
    data.crossSignals.add(signal);
}

protected final int signalCount(PlayerData data, String... names) {
    int n = 0;
    for (String name : names) if (data.crossSignals.contains(name)) n++;
    return n;
}

protected final void setback(PlayerData data) {
    // 通过 manager.queueSetback(uuid, x, y, z) → 主线程 teleport 到最近合法位置
}
```

**步骤 3：AntiCheatManager 新增 queueSetback**

```java
public void queueSetback(java.util.UUID uuid, double x, double y, double z) {
    Bukkit.getScheduler().runTask(plugin, () -> {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            p.teleport(new org.bukkit.Location(p.getWorld(), x, y, z));
        }
    });
}
```

**步骤 4：编译 + 全测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/Check.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/java/com/ycbr/anticheat/pipeline/Verdict.java src/main/java/com/ycbr/anticheat/core/AntiCheatManager.java
git commit -m "feat: punishment framework (attack-block, setback, cross-signal)"
```

---

### 任务 9：NoSlowCheck 接入引擎候选（Phase 1.5 部分）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/NoSlowCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java`

**步骤 1：PredictionEngine 增加 usingItem 参数**

```java
// predictSingle/candidates 重载增加 boolean usingItem：
// usingItem → 输入速度 × 0.6（吃东西/喝药减速，NMS EntityHuman.g 用物品分支）
// 保留原签名（usingItem=false）
```

**步骤 2：NoSlowCheck 改用引擎**

```java
// 替换经验公式 expected = lastDistanceXZ * 0.92 + 0.01：
// 用 candidates(...) 生成含 usingItem 的候选，实际位移匹配任一候选 → 合法
// 否则 bump("noslow", ...) → flag
```

**步骤 3：测试 + 提交**

运行：`mvn test -q`
预期：**全部通过（PASS）**

提交：
```bash
git add src/main/java/com/ycbr/anticheat/simulation/PredictionEngine.java src/main/java/com/ycbr/anticheat/check/movement/NoSlowCheck.java
git commit -m "feat: NoSlowCheck uses engine candidates with usingItem deceleration"
```

---

### 任务 10：标记 SpeedCheck/FlyCheck/NoFallCheck 为 @Deprecated（Phase 1.5 收尾）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/SpeedCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/FlyCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/movement/NoFallCheck.java`

**步骤 1：三个类上加 `@Deprecated` 注解 + Javadoc 说明**

```java
/**
 * @deprecated 经验公式检测，已被 SimulationCheck（预测引擎）取代。
 * 保留为短期冗余兜底，稳定后移除。
 */
@Deprecated
public final class SpeedCheck extends Check {
```

**步骤 2：编译确认**

运行：`mvn compile -q`
预期：**BUILD SUCCESS**（警告可忽略）

**步骤 3：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/movement/SpeedCheck.java src/main/java/com/ycbr/anticheat/check/movement/FlyCheck.java src/main/java/com/ycbr/anticheat/check/movement/NoFallCheck.java
git commit -m "chore: deprecate empirical Speed/Fly/NoFall checks (engine replaces)"
```

---

## 验证方式

- 每任务执行 `mvn test -q` 或 `mvn compile -q`，预期 BUILD SUCCESS / 全部测试通过
- 最终 `mvn -q -DskipTests package` 构建成功
- 全量回归：`mvn test -q` 现有 12 个测试 + 新增 Statistics/SensitivityProcessor 测试全部通过

## 风险与注意事项

- **WorldProbe 性能**：方块查询走主线程，必须 ttl 缓存（5 tick），避免每包查询
- **液体/网/梯子摩擦系数**：参考 NMS 源码确认精确值（水 0.8、网 0.105、梯 0.15），测试只验相对关系
- **灵敏度反推精度**：GCD 恢复在真实鼠标移动（含噪声）下可能不精确，桶精度需调参，不准确时降级为不执行
- **事务包**：1.8.8 事务包节流必须（每 tick ≤1），避免刷包攻击；windowId=0 无效窗口不可用
- **onGround 自判**：先采用"位置差近似贴地 + 方块判定"简化方案，后续可用 NMS 碰撞盒精确化
- **容差收紧**：0.01 水平容差依赖 WorldProbe 精度，若误判增多可回调至 0.015~0.02
- **任务依赖**：任务 3/4/5/6 依赖链：WorldProbe → PredictionEngine 重载 → ShadowPlayer 自判 → SimulationCheck 接入；任务 1/2/7/8 相互独立可并行

---

## ✅ Phase 0/1 实施结果（2026-08-13）

> **按 1.8.8 Paper (v1_8_R3，与 1.8.9 一致) 源码公式严格审查并重构。**

### 任务完成情况
| 任务 | 状态 | 说明 |
|------|------|------|
| 1 Statistics | ✅ 已有 | 8 测试通过 |
| 2 SensitivityProcessor | ✅ 已有 | 2 测试通过 |
| 3 WorldProbe | ✅ 已有+补强 | 数据适配层；补 SOUL_SAND 摩擦 0.4（MainThreadHandler 新增 blockOnSoulSand） |
| 4 PredictionEngine 世界状态 | ✅ 重构 | 见下方"引擎修正" |
| 5 ShadowPlayer 自判 onGround | ✅ | sync 双参数签名 + SimulationCheck 服务器地面判定（ΔY<0.001 且非网/梯） |
| 6 SimulationCheck 接入+容差 | ✅ | 传 motionY；水平改**模长匹配**（抗斜向/侧移误判）；液体容差 ×2 |
| 7 TransactionTracker | ✅ 已完成 | 见任务 7 备注 |
| 8 惩罚框架 | ✅ 新增 | PlayerData(attackBlockedUntil/crossSignals/setbackX/Y/Z) + Check(blockAttacks/addSignal/signalCount/setback) + AntiCheatManager.queueSetback + CheckRegistry.onAttack 门控 |
| 9 NoSlowCheck 引擎化 | ✅ | predictSingle(usingItem=true) 预测减速位移，NMS 1.8 使用物品 ×0.2；config 新增 tolerance |
| 10 Speed/Fly/NoFall @Deprecated | ✅ | 三个类已标注 |

### 引擎关键修正（严格 1.8.8）
1. **水平状态约定**：motionX/Z = 上一帧位置增量（客户端上报值）；delta = 携带×摩擦 + 输入，返回增量可直接与客户端位移比较（原实现返回摩擦后值，会误杀正常行走）。
2. **垂直状态约定**：motionY = 携带速度；空中 delta = 携带速度；**地面站立 delta = 0**（原实现返回 -0.0784 会误判站立玩家为下落）。
3. **速度属性**：`(0.1 + 0.2×速度等级) × (疾跑?1.3:1)`（NMS 操作码 0 加算药水 + 操作码 2 乘算疾跑；原实现药水 10 倍偏小）。
4. **水中垂直顺序**：`motY = motY*0.8 - 0.02`（先乘后减，NMS 顺序；原实现 (motY-0.02)*0.8 错误）。
5. **水中输入**：`bI()×0.02`（贴地疾跑 ×0.1）；原实现 ×0.4 严重偏快。
6. **蜘蛛网**：仅 Entity.move 内 motX/Y/Z ×=0.105，重力保持 0.08（原实现把摩擦改 0.6/重力 0.02 不符合 NMS）。
7. **候选集**：{idle, walk, sprint, sneak} × {不跳, 跳}（补 idle，静止玩家合法）；修正疾跑候选双重乘 1.3 的 bug。
8. **sim-speed 改模长匹配**（|实际| ≤ max|候选| + tol），方向无关，抗侧移/斜向误判。

### 验证
- `mvn -o test -q`：**35 个测试全部通过**（PredictionEngine 17 / ShadowPlayer 8 / Statistics 8 / SensitivityProcessor 2），0 失败 0 错误。
- `mvn -o -q -DskipTests package`：**BUILD SUCCESS**。

### 遗留风险
- SimulationCheck 仍**默认关闭**（config `simulation.enabled: false`），建议实机观察后再开启。
- 液体/蛛网/梯子为简化物理（无碰撞盒），容差 ×2 兜底；若误判增多可回调容差。
- 跳跃被头顶方块挡（headBlocked）用 motY 上限 0.3 近似，未做精确碰撞。
