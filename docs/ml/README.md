# MLP 训练指南（可选增强层）

YCBR-AC 内置 `com.ycbr.anticheat.ml.SimpleMLP`（1 隐藏层 8 神经元，纯 Java，无外部依赖）。
它**不是独立检测**：仅在 `AimStatisticsCheck` 统计信号命中且 ML 输出 > 阈值时追加
`aim-ml` 交叉信号，与启发式（KillAura）交叉后才 punish。权重缺失/未训练时静默跳过，
不影响任何检测。

## 1. 采集样本

```bash
/ycbr record <玩家> legit    # 让可信玩家正常 PvP 3~5 分钟
/ycbr record <作弊者> cheat  # 让（受控）作弊者使用 aimbot/randomizer 3~5 分钟
/ycbr stoprecord <玩家>
```

- 每个攻击窗口（3500ms）结束时写一行特征到 `plugins/YCBR/dataset/<label>_<玩家>.csv`。
- CSV 列（9 维特征）：
  `entropy,iqr,ks,jiff,zscore_count,kurtosis,samples,mean,std`
- 文件名已消毒（仅 `[A-Za-z0-9_]`），防路径穿越。

## 2. 训练（Python，无第三方库依赖）

```python
import csv, glob, math, random

def load(path):
    rows = []
    for f in glob.glob(path):
        label = 1.0 if f.split("\\")[-1].startswith("cheat") else 0.0
        with open(f, newline="") as fh:
            for r in csv.reader(fh):
                if r and r[0] != "entropy" and len(r) == 9:
                    rows.append(([float(x) for x in r[:9]], label))
    return rows

data = load("plugins/YCBR/dataset/*.csv")
random.seed(42); random.shuffle(data)
split = int(len(data) * 0.8)
train, test = data[:split], data[split:]

IN, HID = 9, 8
w1 = [[(random.random() - 0.5) * 0.3 for _ in range(IN)] for _ in range(HID)]
b1 = [(random.random() - 0.5) * 0.1 for _ in range(HID)]
w2 = [(random.random() - 0.5) * 0.3 for _ in range(HID)]
b2 = 0.0

def forward(x):
    h = [max(0.0, b1[i] + sum(w1[i][j] * x[j] for j in range(IN))) for i in range(HID)]
    z = b2 + sum(w2[i] * h[i] for i in range(HID))
    return 1.0 / (1.0 + math.exp(-z)), h

for epoch in range(300):
    for x, y in test_train := (train if epoch < 250 else train + test):
        yhat, h = forward(x)
        dz = yhat - y
        for i in range(HID):
            w2[i] -= 0.01 * dz * h[i]
            b1[i] -= 0.01 * dz * w2[i] * (1.0 if h[i] > 0 else 0.0)
            for j in range(IN):
                w1[i][j] -= 0.01 * dz * w2[i] * (1.0 if h[i] > 0 else 0.0) * x[j]
        b2 -= 0.01 * dz

acc = sum(1 for x, y in test if (forward(x)[0] > 0.9) == (y == 1.0)) / len(test)
print(f"test acc={acc:.2f}  n={len(data)}")
```

## 3. 导出权重到 `plugins/YCBR/ml/weights.txt`

文件格式（每行逗号分隔，顺序必须与 `SimpleMLP.loadFromFile` 一致）：

```
# 前 HID 行：w1[i][0..IN-1]（每行 IN 个）
# 第 HID+1 行：b1[0..HID-1]
# 第 HID+2 行：w2[0..HID-1]
# 第 HID+3 行：b2（单个数字）
```

```python
with open("plugins/YCBR/ml/weights.txt", "w") as f:
    for row in w1: f.write(",".join(f"{v:.6f}" for v in row) + "\n")
    f.write(",".join(f"{v:.6f}" for v in b1) + "\n")
    f.write(",".join(f"{v:.6f}" for v in w2) + "\n")
    f.write(f"{b2:.6f}\n")
```

## 4. 启用

```yaml
checks:
  aimstat:
    ml-enabled: true
    ml-threshold: 0.9
```

注意：样本量 < 200 时 ML 收益有限；先以统计信号为主，ML 作为锦上添花。

## 误判样本回灌工作流（Phase 4 配套）

> 依据：《YCBR-AC_vs_Grim_误判程度对比.md》持续项建议

### 1. 什么是误判样本

真人玩家被 `aimstat` 统计信号（或与 KillAura 交叉）flag 的**攻击窗口**视角增量数据。
这类窗口本应属于 `legit` 类，却被启发式判成作弊信号——把它们回灌进训练集，
让 MLP 学会"这类窗口其实合法"，可逐步压低 `aim-stat` / KillAura 的误报。

### 2. 采集

- `/ycbr record <玩家> [legit|cheat]` 开始采集，label 省略时默认 `legit`
  （仅合法在线玩家可录；label 只能是 `legit` 或 `cheat`）。
- `/ycbr stoprecord <玩家>` 停止采集。
- 录制期间每结束一个攻击窗口（3500ms），`AimStatisticsCheck` 调用
  `DatasetManager.recordAimWindow` 写一行特征到
  `plugins/YCBR/dataset/<label>_<玩家>.csv`（即插件数据目录下的 `dataset/`，
  玩家名与 label 均已消毒 `[A-Za-z0-9_]`，防路径穿越）。
- 采集只对正在 `record` 的玩家生效，与 `settings.dataset.enabled` 无关
  （该配置项代码中未引用）。

### 3. 筛选

- 误判样本必须**人工确认为误判**后才入训练集：核对报警记录/回放，
  确认窗口内是正常操作（如被攻击拖动视角、正常转身等）。
- 同一玩家同一攻击窗口只保留 1 条（每个窗口本就只落 1 行，若重复采集需去重），
  避免个别玩家的重复样本主导训练分布、引入样本偏置。

### 4. 回灌

1. 把筛选好的误判样本 CSV（`legit_<玩家>.csv`）复制进 `plugins/YCBR/dataset/`。
2. 按上文[第 2 节](#2-训练python-无第三方库依赖)训练脚本合并全部 CSV 重训
   （`data = load("plugins/YCBR/dataset/*.csv")` 会自动纳入新增文件）。
3. 新权重按[第 3 节](#3-导出权重到-pluginsycbermlweightstxt)导出，
   覆盖写入 `plugins/YCBR/ml/weights.txt`（`SimpleMLP.loadFromFile` 的加载路径）。
4. **生效方式**：MLP 权重在进程内只加载一次（`AimStatisticsCheck` 静态惰性加载），
   `/ycbr reload` 只重载配置文件、**不重载权重** → 需**重启服务器**才能加载新权重。

### 5. 数据卫生

- 9 特征列顺序必须与训练脚本一致（`DatasetManager` 落盘表头）：
  `entropy,iqr,ks,jiff,zscore_count,kurtosis,samples,mean,std`。
- label 语义：文件名以 `cheat` 开头 → 1（作弊），否则 → 0（合法）
  （训练脚本按文件名前缀判定；误判样本一律存为 `legit_*`）。
- **已知注意**：`AimStatisticsCheck.features()` 推理时的特征顺序与 CSV 列顺序不一致
  （且末位用 sensitivity 而非 samples），Phase 4 对齐前 ML 输出仅作参考，
  不要依赖其绝对精度。

### 6. 预期效果与限制

- 误判样本越多，MLP 越能区分"合法高仿"窗口，`aim-ml` 交叉信号的置信度判定越稳；
  ML 仅在统计信号命中后才追加 `aim-ml` 信号，不会独立误判。
- `checks.aimstat.ml-enabled` 默认 `false`（且 `aimstat` 检查本身默认关闭），
  需手动开启；建议等 `simulation` 检查稳定后，再用留出集交叉验证
  ML 对合法样本的误报率达标，再正式开启（交叉验证门控）。