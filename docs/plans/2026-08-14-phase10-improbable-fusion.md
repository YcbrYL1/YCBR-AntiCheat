# Phase 10：ImproBable 跨检测融合（P2-9 升级，学 NCP Improbable）

> 日期：2026-08-14
> 依据：`docs/2026-08-14-quad-analysis-grim-mx-ncp.md` §P2-9（NCP Improbable 融合）
> 目标：把各检测的**亚阈值小违规**（bump 未达 vl-before-flag）喂入每玩家/每类别频率桶，
> 短窗+长窗同时超阈且覆盖多类别才升级 VL——识别"每类检测都只犯一点错"的持续作弊器。

## 1. 现状核对

- 现有 `settings.improbable`（MainThreadHandler.checkFuse，`settings.improbable.enabled: true`）是
  **服务器全局熔断**：全服所有 flag 进一个 300-tick 全局滚动桶，超阈值 → 静音 kick 60s + 全服 VL 归零。
  语义 = 误报风暴保险，**不是**每玩家融合。
- `Check.bump(data, sub, amount, threshold)`：每检测亚阈值累计桶（`data.buffers`），达阈值返回 true →
  调用方 `flag()`；未达阈值返回 false。**亚阈值事件目前不可见、不跨检测**。
- `flag()` 完整违规已走正常 VL/惩罚链，不进融合。
- CheckType 无类别字段；类别 = 按包划分（combat/movement/protocol）。
- `MainThreadHandler.run()` L129-131 `onMainTick(data, now)` 是每 tick 玩家挂点；`currentServerTick()` 可跨线程读。
- 线程模型：`data.buffers` 仅 actor 线程写；主线程在 `resetAllViolations` 里 `clear()`（既有跨线程访问）。
  新桶读写需同步（同玩家 feed/query 跨线程）。

## 2. 设计（`data/ImprobableTracker.java`，纯逻辑可单测）

### 数据结构
- `enum Category { COMBAT, MOVEMENT, PROTOCOL }` + 静态 `CheckType → Category` 映射表：
  - COMBAT：KILLAURA / SCAFFOLD / CRITICALS / NOSWING / AUTOTOOL / INSTANTBOW / FASTCLICK / REACH / AIMSTAT
  - MOVEMENT：SPEED / VELOCITY / FLY / NOFALL / NOSLOW / SIMULATION
  - PROTOCOL：TIMER / WRONGTURN / BLINK / BADPACKET / FASTTHROW / SPRINT
- 每玩家每类别 2 个环形桶：短窗 `SHORT_SIZE=20` tick + 长窗 `LONG_SIZE=200` tick（`tick % SIZE` 槽位，天然滑动）。
- `feed(CheckType, tick)`：映射类别 → 短桶/长桶当前槽 ++（先 advance 到 tick，delta 过大直接全清）。
- `hotAndReset(nowTick, shortTicks, shortThreshold, longTicks, longThreshold, minCategories)`：
  统计每类别短窗计数与长窗计数，**两者都超阈** 且 **≥ minCategories 个类别命中** → 返回 true 并把命中类别的
  短桶清零（触发即重置，突发被拦截；长桶保留），防止每 tick 重复 flag。
- 线程安全：`feed`（actor 线程写）与 `hotAndReset`（主线程读）均 `synchronized`。

### 接入
- `Check.bump`：bump **未达阈值**（累计中）→ 若 `checks.improbable.enabled` 则
  `data.improbable.feed(type, manager.getMainHandler().currentServerTick())`。一处改动，全部子检测的
  亚阈值信号统一进桶；drain/合法行为不喂（只有违规 bump 喂票）。
- `CheckType` 新增 `IMPROBABLE("improbable")`（无检测类，不注册 CheckRegistry）。
- `MainThreadHandler.run()` 每 tick：对每玩家 `data.improbable.hotAndReset(...)` 命中 →
  `data.addViolation(CheckType.IMPROBABLE)` + `queueVerdict`（走正常惩罚链，被现有全局 fuse 统计）。
- config `checks.improbable`（**默认关**，项目哲学）：
  `enabled: false`、`short-ticks: 20`、`short-threshold: 6`、`long-ticks: 200`、`long-threshold: 30`、
  `min-categories: 2`、`vl-before-flag: 3`、`kick-at-vl: 15`、`kick-message`。

### 测试（`ImprobableTrackerTest`）
1. `feed_countsInWindow`：同类别 5 票 → 短窗 5
2. `windowSlides_oldTicketsExpire`：窗口滑出短窗但仍计长窗
3. `singleCategory_notHot`：min-categories=2 时单类别双超不触发
4. `twoCategories_hot`：两个类别双超 → 触发
5. `longOnly_notHot`：长窗超阈短窗未超不触发
6. `shortOnly_notHot`：反向
7. `resetClearsShortWindow`：触发后命中类别短桶清零、长桶保留
8. `categoryMapping_correct`：映射表抽查

### 验收
- 新增测试全绿；默认关 → 生产零行为变化；回归 138 不破坏。
- 与现有全局 fuse 共存：融合升级的 Verdict 照常进 fuse 桶（不改变 checkFuse 逻辑）。
