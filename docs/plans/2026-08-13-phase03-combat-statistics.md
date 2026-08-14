# 战斗统计层（KillAura/FastClick/Reach 借鉴 MX）实施计划

> **给 Claude：** 按任务逐项执行本计划，每个任务完成后验证通过再提交。

**目标：** 借鉴 MX 的统计+交叉验证思路，在现有 GCD/启发式 KillAura 之上补统计层（熵/IQR/KS/Jiff/Z-score/机械心跳）、FastClick 补峰度+熵维度、Reach 补多帧射线求交，并新增数据集采集管线与可选的轻量 MLP，使战斗类检测深度接近 MX 但保留 YCBR 的启发式保底（ML 不独立误判）。

**架构方案：** 新增 `combat/aim/AimStatisticsCheck`（依赖 Phase 0 的 `Statistics` 库与 `SensitivityProcessor`）；`KillAuraCheck` 保留原 16 子检测作为启发式信号，统计信号与启发式信号通过 `crossSignals` 交叉后才 punish（P0.4）；`FastClickCheck` 扩展峰度/熵；`ReachCheck` 增加多帧视角枚举 + 实体插值碰撞盒；`DatasetManager` 采集合法/作弊样本供可选 MLP 训练。

**技术栈：** Java 8、JUnit 5、Paper 1.8.8 v1_8_R3

**前置依赖：** Phase 0 任务 1（Statistics）、任务 2（SensitivityProcessor）、任务 8（crossSignals/攻击阻断）已完成。

---

### 任务 1：新建 combat/aim/AimStatisticsCheck.java 统计子检测（Phase 3.1）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/check/combat/aim/AimStatisticsCheck.java`
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/CheckRegistry.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：PlayerData 新增统计样本字段**

```java
// 攻击视角增量样本（每攻击一次记录一次 yaw 增量，最近 50 个）
public final java.util.ArrayDeque<Double> aimDeltasStat = new java.util.ArrayDeque<Double>(50);
public final java.util.ArrayDeque<Double> aimPitchDeltasStat = new java.util.ArrayDeque<Double>(50);
public volatile double lastAimYaw, lastAimPitch;
public volatile int statSampleCount;
```

**步骤 2：AimStatisticsCheck 骨架（挂到 KillAuraCheck 的 checkRotation 之后）**

```java
package com.ycbr.anticheat.check.combat.aim;

import java.util.ArrayList;
import java.util.List;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.SensitivityProcessor;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.util.Statistics;

/**
 * 统计层 Aim 检测（借鉴 MX AimStatisticsCheck/AimComplexCheck）。
 * 在启发式 GCD 之上叠加：Shannon 熵、IQR、KS 检验、Jiff 模式、
 * Z-score 离群（随机化缺陷）、机械心跳。
 * 与启发式信号交叉后才 punish（不独立误判）。
 */
public final class AimStatisticsCheck extends Check {

    private static final int MIN_SAMPLES = 25;
    private final SensitivityProcessor sensitivity = new SensitivityProcessor();

    public AimStatisticsCheck(AntiCheatManager manager) {
        super(CheckType.SIMULATION, manager); // 复用 SIMULATION 类型？否——见 CheckType 新增
    }

    // 注意：需在 CheckType 新增 AIMSTAT 枚举项，或复用 KILLAURA 类型（config 路径 aimstat）
    public void onRotation(PlayerData data, float yaw, float pitch) {
        // 1. 灵敏度有效区间外不执行（SensitivityProcessor.inRange）
        // 2. 每次攻击（UseEntity）后的 3.5s 窗口内收集视角增量
        // 3. 样本 < MIN_SAMPLES 不判定（冷启动跳过）
        // 4. 统计判定：
        //    a. Shannon 熵 < 阈值 → 机械（规律）
        //    b. IQR 极小（< 0.0005）→ 常数步长
        //    c. KS 检验：与"均匀随机分布"偏差过大 → 非人
        //    d. Jiff 模式重复 ≥ 2 → 序列重复
        //    e. Z-score 离群：单个角度突变脱离分布（随机化缺陷的刻意修正）
        //    f. 机械心跳：间隔高度规律（Kurtosis < -0.5）
        // 5. 任一统计信号命中 → addSignal(data, "aim-stat")
        //    KillAuraCheck 的启发式 flag 时检查 signalCount → ≥1 才 punish
    }
}
```

**步骤 3：CheckType 新增枚举项**

```java
AIMSTAT("AimStat", "aimstat"),
```

**步骤 4：config.yml 新增段**

```yaml
  aimstat:
    enabled: false
    min-samples: 25
    entropy-max: 1.5
    iqr-min: 0.0005
    jiff-pattern-len: 3
    jiff-max: 2
    zscore-threshold: 4.0
    kurtosis-max: -0.5
    vl-before-flag: 6
    strict:
      entropy-max: 1.0
      iqr-min: 0.001
      zscore-threshold: 3.5
```

**步骤 5：编译确认**

运行：`mvn compile -q`
预期：**BUILD SUCCESS**

**步骤 6：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/combat/aim/AimStatisticsCheck.java src/main/java/com/ycbr/anticheat/check/CheckType.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/resources/config.yml
git commit -m "feat: AimStatisticsCheck skeleton (entropy/IQR/KS/Jiff/z-score/heartbeat signals)"
```

---

### 任务 2：KillAuraCheck 接入统计信号交叉验证（Phase 3.1 集成）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/KillAuraCheck.java`

**步骤 1：在 KillAuraCheck 的 flag 路径加交叉检查**

```java
// 所有现有 flag(...) 调用点（16 子检测）统一改为：
private boolean crossValidated(PlayerData data) {
    return signalCount(data, "aim-stat") >= 1;
}

// flag 前：若 aimstat 启用且玩家有统计样本，则要求交叉命中
// （aimstat 未启用或样本不足时保持原启发式直判，不降级）
private boolean shouldPunish(PlayerData data) {
    if (!isSubEnabled("aimstat") ) return true; // aimstat 关 → 维持现状
    if (data.statSampleCount < 25) return true; // 冷启动 → 维持现状
    return crossValidated(data);
}
```

**步骤 2：每个 flag 调用点包裹**

```java
if (bump(data, sub, 1D, threshold)) {
    if (shouldPunish(data)) {
        flag(data, sub, info);
    } else {
        // 未交叉 → 只记录信号，不 punish（buffer 保留，避免抖动）
        addSignal(data, "heur-" + sub);
    }
}
```

**步骤 3：config.yml killaura 段新增**

```yaml
  killaura:
    ...existing...
    aimstat-cross: true   # 与 AimStatisticsCheck 交叉（aimstat 启用时生效）
```

**步骤 4：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/combat/KillAuraCheck.java src/main/resources/config.yml
git commit -m "feat: KillAura heuristic+stat cross-validation (aimstat signal gating)"
```

---

### 任务 3：FastClickCheck 增强（峰度 + Shannon 熵，Phase 3.2）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/FastClickCheck.java`
- 修改：`src/main/resources/config.yml`
- 测试：`src/test/java/com/ycbr/anticheat/check/combat/FastClickLogicTest.java`

**步骤 1：抽离纯逻辑可测类 FastClickLogic**

```java
package com.ycbr.anticheat.check.combat;

import java.util.ArrayList;
import java.util.List;

import com.ycbr.anticheat.util.Statistics;

/**
 * 自动点击纯逻辑：cps + burst + CV + 峰度 + 熵 五维判定。
 * 无 Bukkit 依赖，可单测。
 */
public final class FastClickLogic {

    private final List<Long> intervals = new ArrayList<Long>();
    private static final int MAX_INTERVALS = 100;

    public void feed(long intervalMs) {
        intervals.add(intervalMs);
        if (intervals.size() > MAX_INTERVALS) intervals.remove(0);
    }

    public boolean mechanicalPattern() {
        if (intervals.size() < 40) return false;
        List<Double> xs = new ArrayList<Double>(intervals.size());
        for (long v : intervals) xs.add((double) v);
        double kurt = Statistics.kurtosis(xs);
        double entropy = Statistics.shannonEntropy(xs);
        // 机械点击：峰度显著为负（间隔高度规律）或熵极低
        return kurt < -0.5 || entropy < 1.0;
    }
}
```

**步骤 2：写失败测试**

```java
package com.ycbr.anticheat.check.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FastClickLogicTest {

    @Test
    void mechanicalPattern_detectsConstantIntervals() {
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 60; i++) logic.feed(95L); // 恒定 95ms 间隔
        assertTrue(logic.mechanicalPattern());
    }

    @Test
    void organicPattern_notFlagged() {
        FastClickLogic logic = new FastClickLogic();
        long[] intervals = {95, 120, 87, 143, 76, 110, 133, 98, 152, 84,
                            105, 127, 91, 138, 82, 116, 145, 89, 102, 134};
        for (int i = 0; i < 60; i++) {
            logic.feed(intervals[i % intervals.length]);
        }
        assertFalse(logic.mechanicalPattern(), "organic clicking should not flag");
    }

    @Test
    void insufficientSamples_notFlagged() {
        FastClickLogic logic = new FastClickLogic();
        for (int i = 0; i < 20; i++) logic.feed(95L);
        assertFalse(logic.mechanicalPattern(), "cold start should not flag");
    }
}
```

**步骤 3：运行确认失败 → 实现 → 确认通过**

运行：`mvn test -q -Dtest=FastClickLogicTest`
预期：先 **FAIL**（类不存在）→ 实现后 **PASS**

**步骤 4：FastClickCheck 接入**

```java
// onAttack 中：
data.fastClickLogic.feed(now - lastClick);
if (data.fastClickLogic.mechanicalPattern()) {
    if (bump(data, "mechanical", 1D, i("mechanical.vl-before-flag", 4))) {
        flag(data, "Mechanical", "kurtosis/entropy pattern");
    }
} else {
    drain(data, "mechanical", 0.02D);
}
```

**步骤 5：config.yml 新增**

```yaml
    mechanical:
      enabled: true
      min-samples: 40
      vl-before-flag: 4
```

**步骤 6：全量测试 + 提交**

运行：`mvn test -q`
预期：**全部通过（PASS）**

提交：
```bash
git add src/main/java/com/ycbr/anticheat/check/combat/FastClickCheck.java src/main/java/com/ycbr/anticheat/check/combat/FastClickLogic.java src/test/java/com/ycbr/anticheat/check/combat/FastClickLogicTest.java src/main/resources/config.yml
git commit -m "feat: FastClick mechanical pattern (kurtosis + Shannon entropy)"
```

---

### 任务 4：ReachCheck 多帧射线求交（Phase 3.5）

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java`

**步骤 1：读现有 ReachCheck 源码，理解当前"上一帧位置 + 两档眼高"逻辑**

运行：`Get-Content src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java`
预期：看到 max-reach=3.1 + 眼高 1.62 + 实体半宽 + ping 补偿 + 外推 cap 逻辑

**步骤 2：增加多帧视角枚举**

```java
// 攻击判定时，不再只用当前 yaw/pitch，枚举：
// - 当前帧视角
// - 上一帧视角（data.prevYaw/prevPitch，已有）
// - 上上帧视角（需要新增 PlayerData.prevPrevYaw/prevPrevPitch，或在 checkRotation 时轮转）
// 任一帧视角的射线命中实体碰撞盒 → 视为合法命中
// 实现：从每帧视角发射射线，求交实体 AABB（含插值位置 ± 半宽）
```

**步骤 3：PlayerData 轮转视角**

```java
// checkRotation 时：
data.prevPrevYaw = data.prevYaw;
data.prevPrevPitch = data.prevPitch;
data.prevYaw = data.lastYaw;
data.lastYaw = yaw;
// 同步 prevPrevPitch/prevPitch
```

**步骤 4：实体插值碰撞盒**

```java
// 实体位置插值：target 当前位置与上帧位置线性插值（攻击时刻 = 服务器当前 tick）
// 碰撞盒：AABB 中心 ± 半宽（EntitySnapshot 已有位置数据）
// 射线求交：分段采样射线（每 0.1 块一步），命中盒内即合法
```

**步骤 5：实时取消非法命中（可选增强）**

```java
// AttackContext 增加 cancel 标志；ReachCheck 判定为非法距离时
// 通过 Verdict 附带 cancel 请求 → MainThreadHandler 取消伤害
// 默认关闭（config: cancel-invalid-attacks: false），观察后开启
```

**步骤 6：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 7：提交**

```bash
git add src/main/java/com/ycbr/anticheat/check/combat/ReachCheck.java src/main/java/com/ycbr/anticheat/data/PlayerData.java src/main/resources/config.yml
git commit -m "feat: ReachCheck multi-frame ray-AABB intersection"
```

---

### 任务 5：DatasetManager 数据集采集管线（Phase 3.3）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/core/DatasetManager.java`
- 修改：`src/main/java/com/ycbr/anticheat/command/YCBRCommand.java`
- 修改：`src/main/resources/config.yml`

**步骤 1：DatasetManager 骨架**

```java
package com.ycbr.anticheat.core;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;

/**
 * 数据集采集管线（借鉴 MX DatasetManager / RECORDING 模式）。
 * 按玩家名记录：视角增量序列、点击间隔序列、标签（legit/cheat）。
 * 样本落盘 plugins/YCBR/dataset/*.csv，供后续 MLP 训练。
 */
public final class DatasetManager {

    private final AntiCheatManager manager;
    private final Path dir;
    private final List<String> recording = new ArrayList<String>();

    public DatasetManager(AntiCheatManager manager) {
        this.manager = manager;
        this.dir = Paths.get(manager.getPlugin().getDataFolder().getPath(), "dataset");
    }

    public boolean isRecording() { return !recording.isEmpty(); }

    public void startRecording(String player) {
        recording.add(player);
    }

    public void stopRecording(String player) {
        recording.remove(player);
    }

    public void record(String player, String label, String dataLine) {
        if (!recording.contains(player)) return;
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(label + "_" + player + ".csv");
            BufferedWriter w = Files.newBufferedWriter(f,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            w.write(dataLine);
            w.newLine();
            w.close();
        } catch (Exception ignored) {
        }
    }
}
```

**步骤 2：YCBRCommand 新增子命令**

```java
// /ycbr record <player> [label]  → startRecording
// /ycbr stoprecord <player>     → stopRecording
// 在 sendHelp() 追加两行中文说明
```

**步骤 3：config.yml 新增**

```yaml
  dataset:
    enabled: false
```

**步骤 4：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/core/DatasetManager.java src/main/java/com/ycbr/anticheat/command/YCBRCommand.java src/main/resources/config.yml
git commit -m "feat: DatasetManager recording pipeline (/ycbr record)"
```

---

### 任务 6：可选 MLP 层（Phase 3.4，验证后上）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/ml/SimpleMLP.java`
- 修改：`src/main/java/com/ycbr/anticheat/check/combat/aim/AimStatisticsCheck.java`

**步骤 1：SimpleMLP 最小实现（不引入外部 ML 库，纯 Java 前向传播）**

```java
package com.ycbr.anticheat.ml;

/**
 * 极简 MLP（1 隐藏层 8 神经元，纯 Java，无依赖）。
 * 输入：统计特征向量（熵/峰度/IQR/KS/Jiff/均值/方差/灵敏度…8 维）。
 * 输出：0~1 作弊概率。
 * 仅在 AimStatisticsCheck 统计+启发式交叉之上做"增强"信号，
 * 不独立误判：ML 输出 > 0.9 且统计信号命中才加成 buffer。
 */
public final class SimpleMLP {

    private final double[][] w1; // [hidden][input]
    private final double[] b1;
    private final double[] w2;
    private double b2;

    public SimpleMLP(int inputSize, int hiddenSize) {
        w1 = new double[hiddenSize][inputSize];
        b1 = new double[hiddenSize];
        w2 = new double[hiddenSize];
        // 随机初始化（种子固定便于复现），后续由训练脚本填充
    }

    public double forward(double[] x) {
        double[] h = new double[w1.length];
        for (int i = 0; i < w1.length; i++) {
            double sum = b1[i];
            for (int j = 0; j < x.length; j++) sum += w1[i][j] * x[j];
            h[i] = Math.max(0, sum); // ReLU
        }
        double out = b2;
        for (int i = 0; i < h.length; i++) out += w2[i] * h[i];
        return 1.0 / (1.0 + Math.exp(-out)); // sigmoid
    }

    // 权重由训练脚本生成 JSON 后加载
    public void loadWeights(double[][] w1, double[] b1, double[] w2, double b2) {
        // 拷贝入内部数组
    }
}
```

**步骤 2：AimStatisticsCheck 集成（增强信号）**

```java
// 统计特征向量构建 → mlp.forward(features)
// 若 output > 0.9 且已有统计信号 → addSignal(data, "aim-ml")（与 aim-stat 相同权重）
// ML 权重未加载（文件缺失）→ 静默跳过，不影响任何检测
```

**步骤 3：训练脚本说明文档（docs/ml/README.md）**

说明：如何用 DatasetManager 采集样本、Python 脚本训练、导出权重 JSON。

**步骤 4：编译 + 全量测试**

运行：`mvn test -q`
预期：**全部通过（PASS）**

**步骤 5：提交**

```bash
git add src/main/java/com/ycbr/anticheat/ml/SimpleMLP.java src/main/java/com/ycbr/anticheat/check/combat/aim/AimStatisticsCheck.java docs/ml/README.md
git commit -m "feat: optional MLP enhancement layer (weight-gated, non-flagging)"
```

---

### 任务 7：战斗类回归验证 + 文档更新

**涉及文件：**
- 修改：`docs/plans/2026-08-13-prediction-engine-impl.md`（追加结果）

**步骤 1：全量构建 + 测试**

运行：`mvn -q -DskipTests package`
预期：**BUILD SUCCESS**

运行：`mvn test -q`
预期：**全部测试通过（PASS）**

**步骤 2：更新计划文档追加"实施结果"章节**

记录：提交哈希、测试结果、config 变更摘要、默认关闭项清单（aimstat/ML）

**步骤 3：提交**

```bash
git add docs/plans/2026-08-13-phase03-combat-statistics.md docs/plans/2026-08-13-prediction-engine-impl.md
git commit -m "docs: Phase 3 combat statistics results"
```

---

## 验证方式

- 每任务 `mvn test -q` / `mvn compile -q`，预期 BUILD SUCCESS + 全部通过
- 最终 `mvn -q -DskipTests package` 构建成功
- 回归：现有 12+ 测试全部通过（新增 FastClickLogic 3 个）
- 默认关闭项：aimstat（统计层）、ML 增强——线上观察 1-2 周误判日志后开启

## 实施结果（2026-08-14 完成）

**提交序列（全部 BUILD SUCCESS，最终 54/54 测试通过，YCBR.jar 242005 字节）：**

| 提交 | 内容 |
|---|---|
| `2d462b0` | 任务 1：AimStatsLogic（6 信号：entropy/IQR/KS/Jiff/zscore/kurtosis）+ AimStatsLogicTest 6 测试 |
| `7be1fd0` | 任务 2：AimStatisticsCheck + CheckType.AIMSTAT + CheckRegistry.onRotation 分发 + config aimstat 段（默认关） |
| `e6e2768` | 任务 3：KillAura 交叉验证（shouldPunish/flagGated/AIM_GATED_SUBS 9 子检测 + config killaura.aimstat-cross） |
| `07bd9b7` | 任务 4：FastClickLogic（峰度/熵机械节奏）+ FastClickLogicTest 3 测试 + **修复 Statistics.kurtosis 尺度依赖 bug** |
| `3407421` | 任务 5：Reach 多帧视角枚举 + 实体插值碰撞盒（MathUtil 增加 maxDistance 射线重载） |
| `fd8409c` | 任务 6：DatasetManager（/ycbr record/stoprecord，文件名消毒防路径穿越）+ AimStatisticsCheck 窗口落盘 |
| `c5a46ca` | 任务 7：SimpleMLP（9 维特征 × 8 隐藏，CSV 权重加载）+ SimpleMLPTest 4 测试 + docs/ml/README.md |

**对计划的偏离（设计决策，均已落实）：**
- **KS 语义反转**：真人瞄准为尖峰分布，KS 判定改为"对均匀分布偏差过小 = 过度均匀 = 随机化修饰"（`ks < ks-min-uniform` 命中），而非计划原文"偏差过大"
- **z-score 需 ≥3 个离群**：单次真人 flick 不算机械（首次测试 10/10 误报后修正），新增 repeatedSnaps 测试
- **Kurtosis 修复**：原实现混合标准化 4 阶矩与未标准化方差（尺度依赖，大数值恒为 -3）；改为原始矩 m4/m2²-3，FastClickLogic/AimStatsLogic 均受益
- **KillAura 门控范围**：只包裹 9 个瞄准模式子检测（AimModulo360/AimStep/GcdStable/GcdGrid/ConstStep/AxisAsym/BigRot/Angle/Switch），非瞄准子检测保持直判——避免统计信号缺失造成假阴性
- **交叉信号带时间戳**：aimStatSignalTime + signal-fresh-ms: 10000 解决 crossSignals 无过期机制问题
- **Reach 射线限距**：公共 rayIntersectsAabb 原为无限射线，新增 maxDistance 参数重载防"远处盒体误放行"

**config 变更摘要：**
- `checks.aimstat`（默认关）：entropy-max/iqr-min/ks-min-uniform/jiff-pattern-len/jiff-max/zscore-threshold/kurtosis-max/signal-fresh-ms/vl-before-flag + strict 变体 + ml-enabled/ml-threshold
- `checks.killaura.aimstat-cross: true`
- `checks.fastclick.mechanical.kurtosis-max: -1.0`（+ vl-before-flag）
- `checks.reach.multi-frame.enabled/window-ticks/expand`
- `settings.dataset.enabled: false`

**默认关闭项：** aimstat 统计层、ML 增强（`ml-enabled: false`）——线上观察 1-2 周误判日志后开启

## 风险与注意事项

- **统计层误判**：熵/KS/IQR 对真人也敏感（高灵敏度玩家、瞄准练习场）。多信号交叉（启发式+统计同时命中才 punish）是核心保护，单一信号永不 punish
- **冷启动**：样本 < min-samples(25) 时统计层静默，避免开局误判
- **灵敏度校准**：SensitivityProcessor 有效区间 [20,150] 外不执行统计层（高/低 DPI 玩家）
- **ML 不独立误判**：ML 输出只做"加成"信号，权重文件缺失时静默降级为纯统计+启发式
- **Reach 多帧枚举**：多帧视角枚举可能放行"擦边命中"，配合实时取消（默认关）逐步验证
- **DatasetManager 落盘安全**：文件名用玩家名，需消毒（去非法字符），防路径穿越
- **任务依赖**：任务 1/2 依赖 Phase 0 的 Statistics/SensitivityProcessor/crossSignals；任务 3 依赖 Statistics；任务 5/6 独立；任务 7 依赖全部
