# 2026-08-10 检测力重构（LB 对抗）实施计划

> **给 Claude：** 按任务逐项执行本计划，构建命令：`& "C:\Users\WIN10\AppData\Local\Temp\opencode\maven\apache-maven-3.9.16\bin\mvn.cmd" -q -DskipTests package`（工作目录 YCBR-AC）

**目标：** 恢复对 LiquidBounce 默认配置（Scaffold 每 tick 放置、KillAura 180°/tick 平滑旋转 + GCD-Sync + 5-8cps 匀速攻击）的检测力，同时保持绿玩零误报。

**架构方案：** 攻击门控（MX 3500ms）限定全部瞄准统计窗口；GCD 稳定性（MX EXPANDER=2^24 辗转相除，gcd<2^17→机器）对抗 LB GCD 归一；恒定步长/熵/方差不对称统计；放置网格节拍、共线、45° 网格旋转、重复旋转量（Grim DuplicateRotPlace 移植）。

**技术栈：** Java 8 / Spigot 1.8.9 / ProtocolLib。

---

### 任务 1：MathUtil 统计工具集

修改 `YCBR-AC\src\main\java\com\ycbr\anticheat\util\MathUtil.java`（追加静态方法，无注释）：

```java
public static final double EXPANDER = Math.pow(2, 24);

public static long gcd(long a, long b) {
    while (b != 0L) {
        long t = a % b;
        a = b;
        b = t;
    }
    return a;
}

public static double mean(List<Double> data) {
    if (data.isEmpty()) return 0D;
    double sum = 0D;
    for (double v : data) sum += v;
    return sum / data.size();
}

public static double variance(List<Double> data) {
    if (data.isEmpty()) return 0D;
    double m = mean(data);
    double sum = 0D;
    for (double v : data) sum += (v - m) * (v - m);
    return sum / data.size();
}

public static double stdDev(List<Double> data) {
    return Math.sqrt(variance(data));
}

public static double shannonEntropy(List<Double> data) {
    if (data.isEmpty()) return 0D;
    Map<Double, Integer> freq = new HashMap<>();
    for (double v : data) freq.merge(v, 1, Integer::sum);
    double total = data.size();
    double entropy = 0D;
    for (int n : freq.values()) {
        double p = n / total;
        entropy -= p * (Math.log(p) / Math.log(2));
    }
    return entropy;
}

public static int distinct(List<Double> data) {
    return (int) data.stream().distinct().count();
}
```

import：`java.util.List`、`java.util.Map`、`java.util.HashMap`。

### 任务 2：PlayerData 数据窗口

修改 `YCBR-AC\src\main\java\com\ycbr\anticheat\data\PlayerData.java`：

```java
public volatile long lastAttackTime;
public volatile double lastYawDelta;
public volatile long pendingReversalTime;
public final java.util.List<Double> aimDeltas = new java.util.ArrayList<Double>();
public final java.util.List<Long> attackIntervals = new java.util.ArrayList<Long>();
public final java.util.List<Double> placeYawDeltas = new java.util.ArrayList<Double>();
public final java.util.List<Double> placeYaws = new java.util.ArrayList<Double>();
public final java.util.List<Double> placePitches = new java.util.ArrayList<Double>();
```

### 任务 3：KillAuraCheck —— 攻击门控 + 四个统计检测

**3a. 门控与窗口开关。** onMove 里：`now - data.lastAttackTime <= 3500` 时收集 `|Δyaw|∈(0.1, 30)` 到 `aimDeltas`（若窗口超 40 个移出最旧）；否则清空 `aimDeltas`（门控过期即重置，MX 思想）。`data.lastYawDelta` 每 tick 更新（带符号）。窗口满 10 → 依次跑 3b/3c/3d（复用同窗口数据）。

**3b. GCD 稳定性（杀 LB GCD-Sync）。** MX 移植：
```java
private void checkGcd(PlayerData data) {
    if (data.aimDeltas.size() < 10) return;
    List<Double> deltas = data.aimDeltas;
    long g = MathUtil.gcd((long) (deltas.get(0) * MathUtil.EXPANDER), (long) (deltas.get(1) * MathUtil.EXPANDER));
    for (int i = 2; i < deltas.size(); i++) {
        g = MathUtil.gcd(g, (long) (deltas.get(i) * MathUtil.EXPANDER));
    }
    if (g < 131072L && g > 0L && MathUtil.mean(deltas) > 1D && MathUtil.distinct(deltas) >= 3) {
        if (bump(data, "gcd", 1D, i("gcd.vl-before-flag", 6))) {
            flag(data, "GcdStable", "gcd=" + (g / MathUtil.EXPANDER) + " n=" + deltas.size());
        }
    } else {
        drain(data, "gcd", 0.05D);
    }
}
```
条件注释：gcd >0 防止所有样本互质时 gcd=1 误判——不，gcd=1 也是机器特征（delta 都是 1 的倍数恒为 1）。MX 判断 gcd<131072 即算。但**人类**随机 delta（如 3.2, 7.1, 4.5...）扩展后 gcd 也会快速退化为小值（10-1000）！关键差异：MX 是"**连续相邻两对**的 gcd 稳定小"。人类相邻 gcd 波动（时大时小）。**正确做法**：检查"相邻对 gcd < 131072 的连续次数"稳定（MX buffer 式）。实现修正：维护 `data.gcdStreak`（相邻对 gcd<131072 计数），≥6 才判：
```java
long g = MathUtil.gcd((long)(d*E), (long)(lastD*E));
if (g < 131072L && g > 0) gcdStreak++; else gcdStreak = 0;
if (gcdStreak >= 6 && mean>1) bump
```
（残留 lb 此条已修正，请按修正版写）

**3c. 恒定步长（杀 LB Linear 渐变）。**
```java
if (deltas.size() >= 20 && MathUtil.stdDev(deltas) < 0.05D && MathUtil.mean(deltas) > 1D) bump("conststep")
```

**3d. 轴不对称（MX Randomizer flaw）。** 收集时同时存 pitch delta 序列 `data.aimPitchDeltas`（需在 PlayerData 补该窗口）；10+ 样本时：`varYaw < 0.2 && varPitch > 30` 或 `varPitch < 0.2 && varYaw > 30` → bump("axisasym")。

**3e. ON_TICK 折返（杀 ON_TICK 模式）。**
- onAttack：`if (Math.abs(data.lastYawDelta) > 60D) data.pendingReversalTime = System.currentTimeMillis();`
- checkYaw：`long pend = data.pendingReversalTime; if (pend > 0 && now - pend <= 80 && lastYawDelta * data.lastYawDelta < 0 && Math.abs(lastYawDelta) > 60D) { data.pendingReversalTime = 0; bump("onsnap") }`

**3f. 攻击间隔恒定性。**
onAttack：`long now; if (data.lastAttackTime > 0) { long gap = now - data.lastAttackTime; if (gap > 0 && gap < 2000) { data.attackIntervals.add((double) gap); while (> 16) remove(0); } }`；窗口 ≥8 时：`cv = stdDev/mean < 0.15` → bump("interval")；`data.lastAttackTime = now`。

### 任务 4：KillAuraCheck 门控清单（保留原检测不回归）

- checkAimStep：维持现状（90° 放行保留——那是合法甩枪窗口）
- 新增扣点：aimDeltas 窗口只收集 0.1°~30°；≥90° 的单帧不收集（放行语义一致）

### 任务 5：ScaffoldCheck —— 四个检测

**5a. 网格节拍（替换 average 均匀性判定）。** 保留 batch 结构，删除 spread 逻辑：
```java
long[] gaps; // 由 points 计算（同现在）
boolean allGrid = true;
for (long gap : gaps) {
    int k = (int) Math.round(gap / 50.0D);
    if (k < 1 || k > 6 || Math.abs(gap - k * 50L) > 10L) { allGrid = false; break; }
}
if (allGrid && gaps.length >= 5) {
    if (bump(data, "cadence", 1D, i("cadence.vl-before-flag", 4))) flag("Cadence", ...)
} else drain
```
保留原 `average` 配置语义不兼容问题——直接在 onPlace 里调用新方法 `checkCadence(ctx)`，旧 checkAveragePlace 移除。config 键：`cadence.tolerance-ms: 10`、`cadence.vl-before-flag: 4`。

**5b. 共线性。** `checkColinear(ctx)`：placePoints copy → ≥6 点，全部水平（xz 变化），首步向量 (dx1,dz1)，后续点积 `dx1*dxN+dz1*dzN / (len1*lenN) >= 0.99` 且步长 ∈[0.85,1.55]（对角 √2≈1.414 含）→ bump("colinear")。垂直塔（全点 xz 相同）跳过。步长检测也要首个步长 ∈[0.85,1.55]。

**5c. 45° 网格旋转。** 放置时记录 lastYaw/lastPitch 进 placeYaws/placePitches；窗口 ≥6 时：`gridHits = count(|yaw % 45| < 0.1)`，`pitchOK = count(pitch ∈ [70,85])`；`gridHits >= 5 && pitchOK >= 5` → bump("grid45")。

**5d. DuplicateRotPlace。** onPlace：`data.placeYawDeltas.add(data.lastYawDelta)`；窗口 ≥2 时：`|last - prev| < 0.0001 && |last| > 2` → bump("duprot")（连续两次放置前置旋转完全相同）。

### 任务 6：config.yml 同步

killaura 节新增：
```yaml
    gcd:
      vl-before-flag: 6
    conststep:
      vl-before-flag: 6
    axisasym:
      vl-before-flag: 6
    onsnap:
      vl-before-flag: 4
    interval:
      vl-before-flag: 5
```
scaffold 节新增：
```yaml
    cadence:
      tolerance-ms: 10
      vl-before-flag: 4
    colinear:
      vl-before-flag: 4
    grid45:
      vl-before-flag: 4
    duprot:
      vl-before-flag: 4
```

### 任务 7：构建 + 自检

1. `mvn -q -DskipTests package` 通过；jar 时间戳更新
2. grep 确认无 `checkAveragePlace` 残留调用、`average.batch-size` 键仍被 cadence 使用（batch-size 共用）
3. 逻辑审查清单：门控清窗时机（onMove 每次先判超时再决定收集）、gcdStreak 字段已在 PlayerData 声明

### 任务 8：Reach 快照诊断（验证项）

- 构建后人工核对：EntitySnapshotService 4 tick 刷新 + checkReach 的 target null 分支——当前 null → 直接跳过（静默漏检）。**修正：onAttack 时若 target==null → 用 Bukkit `Bukkit.getEntity(targetId)` 实时取一次位置**（1.8.9 netty 线程调 getEntity 有跨线程风险 → 改为放进 actor.submit 的回调里创建 snapshot？简化方案：target==null 时 debug 计数 + 按 reach 放行，并在 CheckRegistry.onAttack 里 actor.submit 内用 `manager.getEntitySnapshots()` 已是最新。**实施时先验证快照命中率**——若为 0，再排队主线程获取）。

## 验证方式

- 构建通过后部署实测：LB 默认配置搭路/KillAura → 应触发 Cadence/Colinear/GcdStable/ConstStep 之一及以上；绿玩塔/斜搭/防砍/环顾 → 全部静默
- AimStep 96° 甩枪（合法）仍静默；ON_TICK 折返仅 LB OnTick 触发

## 风险与注意事项

- GCD 相邻对连续逻辑（任务 3b 修正版）是核心——人类相邻 gcd 波动大，连续 6 对稳定小才判
- 共线 0.99 点积：人类直线桥 6 块手抖会破，误报风险低；若实测误报再放宽 0.98
- 网格节拍 10ms 容差：服务器 TPS 抖动超 10ms 且 LB 放置 → 可能漏；调整参数可在 config 改
- 攻击门控把全部 aim 统计限定在战斗窗内——搭路甩头不再误报 AimStep/新统计