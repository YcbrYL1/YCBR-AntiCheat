# Simulation 开服参数模板（P0 落地版）

> 配套：《2026-08-14-simulation-tuning-sop.md》（调参 SOP）+ `config.yml` 实际默认值
> 用途：把 5 周调参计划翻译成**可直接粘贴的 YAML 片段**，管理员照抄即可，不用读引擎源码。

---

## 0. 先读这三点（决定成败）

1. **`config.yml` 当前 `sim-speed.horizontal-tolerance` 默认已是 0.01**（比 SOP 第一周建议的 0.02 更紧）。
   按 SOP 从 0.02 起步 → 第一周必须**显式写 `0.02`**，否则等于直接跳档，容易误判。
2. `checks.simulation.enabled` 是总开关，子项（sim-speed / sim-fly）依赖它，总开关关着子开关无效。
3. 液体容差 `liquid-tolerance-multiplier: 2.0` **全程不动**；`strict` 子段只给高对抗服务器，普通服别碰。

---

## 1. 前置检查清单（不满足不开）

- [ ] `settings.debug-packets: true`（调参期间开，结束关回 `false`）
- [ ] 低负载 / 白名单服务器（建议 1-2 天）
- [ ] 每日活跃玩家 ≥ 20 人（样本不足结论不可靠）
- [ ] 服务器 TPS ≥ 18（低 TPS 预测误差放大，勿开）
- [ ] 已备份 `config.yml`

---

## 2. 分周 YAML 片段（照抄）

### 第 1 周：开 sim-speed，容差 0.02 起步

```yaml
checks:
  simulation:
    enabled: true          # 总开关
    sim-speed:
      enabled: true        # 子开关
      horizontal-tolerance: 0.02   # 注意：显式 0.02，覆盖默认 0.01
      liquid-tolerance-multiplier: 2.0   # 不动
      direction-match:
        enabled: false     # 方向匹配本轮不开
    sim-fly:
      enabled: false       # 第 3 周再开
```

**预期**：一周无误判 flag（`/ycbr alerts` 无 sim-speed 警报）。

### 第 2 周：收紧 sim-speed 到 0.01

```yaml
    sim-speed:
      horizontal-tolerance: 0.01
```

**预期**：无新增误判。若单日误判 > 5 次 → 回退 0.02。

### 第 3 周：开 sim-fly

```yaml
    sim-fly:
      enabled: true
      vertical-tolerance: 0.02
```

**预期**：无飞行误判（重点观察：出水面瞬间、贴墙下落、下楼梯）。

### 第 4 周：收紧 sim-fly 到 0.01

```yaml
    sim-fly:
      vertical-tolerance: 0.01
```

**预期**：无新增误判。若误判 → 回退 0.02。

### 第 5 周（可选）：高对抗服务器开 strict

```yaml
settings:
  strict-mode: true        # 总严格模式开关（前置）
checks:
  simulation:
    sim-speed:
      strict:
        horizontal-tolerance: 0.005
    sim-fly:
      strict:
        vertical-tolerance: 0.01
```

> 普通服务器跳过第 5 周，`strict-mode` 保持 `false`。

---

## 3. 回退标准（贴墙）

| 情况 | 动作 |
|------|------|
| 任一子检测单日误判 flag > 5 次 | 回退上一档容差（0.01 → 0.02） |
| 0.02 仍误判 | 关该子检测（`enabled: false`），记录场景反馈给开发者 |

---

## 4. 误判记录表模板（每周汇总）

| 日期 | 玩家 | 检测 | 容差 | 场景描述 |
|------|------|------|------|----------|
| 2026-08-14 | Steve | sim-speed | 0.02 | 贴墙爬楼梯连跳被 flag |
| 2026-08-14 | Alex | sim-fly | 0.02 | 出水面瞬间垂直位移偏差 |

---

## 5. 调参完成后收尾

- [ ] `settings.debug-packets` 关回 `false`
- [ ] 确认 aimstat / ml-enabled 仍保持 `false`（本轮不涉及）
- [ ] 把最终容差写回 `config.yml` 默认值（当前默认 sim-speed 0.01 / sim-fly 0.02 即为保守稳定档）
- [ ] 观察 1 周无回归后，可将 `vl-before-flag`（sim-speed 8 / sim-fly 10）视情况下调 1-2 档提速 flag，但不建议低于 5

---

## 6. 与 P1 的关系（已闭环）

`settings.using-item-timeout-ms: 1500`（Sprint usingItem 超时复位，ItemUseLogicTest 7/7 + SprintLogicTest 9/9 通过）——
吃/喝/拉弓被打断不再卡死 usingItem，Sprint/NoSlow 状态误判已消除，可放心进入本轮 simulation 调参。
