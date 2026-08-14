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