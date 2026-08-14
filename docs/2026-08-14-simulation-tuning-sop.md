# Simulation 实机调参 SOP（Phase 4 配套）

> 依据：《YCBR-AC_vs_Grim_误判程度对比.md》P0 建议
> 目标读者：服务器管理员（非开发者）

---

## 1. 为什么需要分步调参

Simulation 是 YCBR-AC 的严格 1.8.8 参数化移动引擎（Phase 3 已完成），但它**没有碰撞盒模拟**：引擎按 1.8.8 NMS 数学公式预测合法位移范围，而方块碰撞（台阶、墙边、楼梯等复杂地形）导致的位移偏差无法被精确预测，只能靠**容差兜底**。

容差的取舍关系：

- **容差越宽 → 漏判越多**（作弊位移被兜住）
- **容差越窄 → 误判越多**（合法玩家在复杂地形被 flag）

因此容差不能拍脑袋定死，必须在**低负载服务器先观察误判日志**，确认某个容差档位在真实玩家环境无新增误判后，再往下一档收紧。默认 `checks.simulation.enabled: false`（子项 sim-speed/sim-fly 也默认关闭），就是为了保证调参完成前不会误杀正常玩家。

## 2. 开启前置条件

开启前请先确认以下条件，不满足不要开：

| 条件 | 说明 |
|------|------|
| `settings.debug-packets: true` | 审计修复新增的调试日志开关，用于观察误判细节；**调参完成后可关回** `false` |
| 低负载 / 白名单服务器 | 建议先在低负载或白名单服务器运行 1-2 天，避免复杂地形和高对抗玩家干扰判断 |
| 玩家基数 ≥ 20 人/日 | 若每日活跃玩家不足 20 人，误判样本不足，观察结论不可靠 |

## 3. 分周开启步骤（核心表格）

> 配置路径统一使用 `checks.simulation.*` 前缀。`checks.simulation.enabled` 是**总开关**，子开关（sim-speed / sim-fly 各自的 `enabled`）依赖总开关——总开关关闭时子开关不生效。

| 阶段 | 动作 | 配置键 | 预期观察 |
|------|------|--------|----------|
| 第 1 周 | 开启 sim-speed，容差从 **0.02** 起步 | `checks.simulation.sim-speed.enabled: true`、`checks.simulation.sim-speed.horizontal-tolerance: 0.02` | 无误判 flag |
| 第 2 周 | 收紧 sim-speed 容差 | `checks.simulation.sim-speed.horizontal-tolerance: 0.01` | 无新增误判 |
| 第 3 周 | 开启 sim-fly | `checks.simulation.sim-fly.enabled: true`、`checks.simulation.sim-fly.vertical-tolerance: 0.02` | 无飞行误判 |
| 第 4 周 | 收紧 sim-fly | `checks.simulation.sim-fly.vertical-tolerance: 0.01` | 无新增误判 |
| 第 5 周 | 稳定期（可选 strict） | `settings.strict-mode: true` 时启用 strict 子段 | 高对抗服务器 |

注意：

- **`checks.simulation.sim-speed.liquid-tolerance-multiplier: 2.0` 全程保持不变**（对比文档明确"液体容差 ×2 保持不变"），液体/网/梯子场景预测精度天然偏低，容差放大档位不要动。
- 每一周步进前，先回看上一周的误判记录（见第 4 节），有回退条件则不继续收紧。
- strict 子段（`sim-speed.strict.horizontal-tolerance: 0.005`、`sim-fly.strict.vertical-tolerance: 0.01`）仅在 `settings.strict-mode: true` 时生效，且应留给对抗强度高的服务器，普通服务器不建议开启。

## 4. 误判判定与回退标准

**观察渠道：**

- 游戏内 `/ycbr alerts` 实时警报；
- `settings.debug-packets: true` 开启后的调试日志（含判定细节，如实际位移、预测上限、容差、tick 数）。

**回退条件：**

1. 任一子检测（sim-speed / sim-fly）**单日误判 flag > 5 次** → 回退到上一档容差（例如 0.01 误判 → 退回 0.02）；
2. 若 **0.02 仍误判** → 关闭该子检测（`enabled: false`），并记录玩家行为特征（地形、场景），作为后续引擎改进的素材。

**误判记录表模板**（建议管理员用表格或 CSV 维护，每周汇总一次）：

| 日期 | 玩家 | 检测 | 容差 | 场景描述 |
|------|------|------|------|----------|
| 2026-08-14 | Steve | sim-speed | 0.02 | 贴墙爬楼梯连跳被 flag |
| 2026-08-14 | Alex | sim-fly | 0.02 | 出水面瞬间垂直位移偏差 |
| ... | ... | ... | ... | ... |

## 5. 与 aimstat / ML 的关系

- `checks.aimstat.enabled` 与 `aimstat.ml-enabled` **保持默认关（false）**，本次调参不涉及。
- aimstat 交叉验证门控依赖 aim-stat 信号，且当前其数据采集与判定口径尚未经过实机检验——**待 simulation 稳定后再评估**是否开启。
- 若未来开启 aimstat，建议同样先低负载观察，再按独立 SOP 收紧。

## 6. 常见问题

**Q：高 TPS 抖动是否影响 simulation 判定？**
A：sim-speed 水平判定与 tick 计数解耦（按时间差换算 tick 数，见引擎实现），对 TPS 抖动有一定容忍度；但建议 **TPS ≥ 18** 的服务器再开启，低 TPS 服务器预测误差会明显放大。

**Q：传送 / 击退并发是否误判？**
A：引擎有 setback 自愈机制，且 bump/drain 有 VL 缓冲（`vl-before-flag` 档位：sim-speed 8、sim-fly 10），偶发的单次偏差会先计 VL 后随时间衰减，不会立刻 flag；只有持续超差才会触发 flag。