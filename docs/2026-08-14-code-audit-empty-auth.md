# YCBR-AC 代码体检报告：空包 / 空函数 / 授权缺失

> 检查日期：2026-08-14
> 检查范围：`src/main/java` 全部 62 个源文件
> 方法：静态扫描（空方法体/空 catch/TODO）+ 逐文件授权审计
> 代码基线：今日凌晨已更新（Timer/Blink/Velocity 已接入 TransactionTracker，TimerCheck 已改 tick 间隔版）

---

## 0. 结论速览

| 类别 | 结果 | 风险 |
|------|------|------|
| 空包（无类的包目录） | **无** | — |
| 空函数（纯空方法体） | 8 个钩子方法（正常模板）+ 5 个工具类私有构造器（正常） | 无 |
| 死代码（写了未接线） | **无** | — |
| 空 catch（静默吞异常） | **15 处**（AsyncPacketListener 8 / DDosGuard 6 / BanManager 1） | 中 |
| 授权缺失 | **5 处**（详见 §3） | 中高 |

**总体：无高危后门，但存在 1 个"检测旁路级"授权缺口 + 若干纵深防御缺失，建议优先修复。**

---

## 1. 空包检查

**结论：无空包。** 16 个 package 全部有类文件，无空目录残留。

## 2. 空函数检查

### 2.1 正常模式（不是问题，跳过）

| 文件 | 空方法 | 说明 |
|------|--------|------|
| `check/Check.java` | `onMove/onAttack/onPlace/onClientCommand/onLook/onBlockDigStart/onThrow/onBowRelease`（8 个） | 模板方法钩子，子类覆写，正常设计 |
| `simulation/PredictionEngine.java` | 私有构造器 | 工具类防实例化惯例 |
| `simulation/WorldProbe.java` | 私有构造器 | 同上 |
| `util/MathUtil.java` | 私有构造器 | 同上 |
| `util/NmsUtil.java` | 私有构造器 | 同上 |
| `util/Statistics.java` | 私有构造器 | 同上 |

### 2.2 死代码核查 —— FastClickLogic 已接线（非死代码）

- **文件**：`check/combat/FastClickLogic.java`（约 60 行，完整实现了 cps/burst/CV/峰度/熵 五维机械点击统计）
- **现状（复核修正）**：`FastClickCheck.java:13` 持有 `private final FastClickLogic logic = new FastClickLogic();`，并在 `onAttack` 中调用 `logic.feed(interval)`（L51）与 `logic.mechanicalPattern(...)`（L52）——**已正确接线，不是死代码**。
- **说明**：此前初版体检报告因 grep 输出截断，误判其为死代码，此处更正。同类新增的 `TimerLogic`（TimerCheck 已用）、`AimStatsLogic`（AimStatisticsCheck 已用）也均已接线。
- **结论**：源码树内**未发现真正未接线的死代码类**。

## 3. 空 catch 检查（静默吞异常）

共 **15 处** `catch (Exception ignored) {}`，全部为静默丢弃：

| 文件 | 行号 | 场景 | 风险 |
|------|------|------|------|
| `packet/AsyncPacketListener.java` | 117, 177, 240, 367, 378, 394, 446, 454 | ProtocolLib 反射读包字段（NMS 反射 `getModifier().read()`、实体快照、槽位/物品读取） | 中：读字段失败静默跳过 → 玩家状态字段可能缺失，检测漏判；`blockingSword` 等关键状态可能恒 false |
| `core/DDosGuard.java` | 139, 212, 314, 339, 362, 394 | 反射驱动连接关闭、握手状态机、主机名读取 | 中：DDoS 防护的反射链路吞异常后可能漏关连接 |
| `core/BanManager.java` | 57 | 解析封禁记录 `expiry` 非法值 | 低：单条记录降级，有日志兜底 |

**修复建议**：至少给 AsyncPacketListener 的空 catch 加 debug 级日志（`if (cfg.debug()) Bukkit.getLogger().info(...)`），避免排查困难。

## 4. 授权缺失审计（重点）

### 🔴 4.1 【高危】GUI 会话内操作无权限复核

- **文件**：`command/GuiManager.java` `onClick()`（L912）/ `onChat()`（L1301）
- **问题**：`onClick` 只校验 `states` 存在即放行，**没有在事件回调里复核 `hasPermission("ycbr.admin")` 或 `isYcbrOp`**。GUI 里含敏感操作：
  - `slot==48`：**踢出任意玩家**（`target.kickPlayer`，双次点击确认）
  - `slot==47`：**清空玩家违规值**
  - `slot==9`：**重载配置**；CHECK_SETTINGS/DDOS 分支：**改任意检测阈值、开关检测、改 DDoS 参数**
- **利用路径**：玩家打开 GUI 后，若其 `ycbr.admin` 权限被权限插件移除（或 ycbrop 名单被改），会话仍持有 `states`，**所有敏感操作继续可用**。更实际的风险：`/ycbrop gui` 仅校验 `isYcbrOp || hasPermission` 于**打开瞬间**，会话期间无二次校验。
- **修复**：在 `onClick`/`onChat` 顶部加 `if (!viewer.hasPermission("ycbr.admin") && !manager.isYcbrOp(viewer.getName())) { close(viewer); return; }`。

### 🟠 4.2 【中高危】MainThreadHandler 封禁只豁免 OP，不豁免 ycbrop

- **文件**：`pipeline/MainThreadHandler.java` L309
- **问题**：检测路径（CheckRegistry 全部 20 处）用 `data.op = player.isOp() || manager.isYcbrOp(...)` 豁免，**但封禁/踢出路径 L309 只判断 `player.isOp()`**。若 ycbrop 名单玩家因某种原因被累计 VL 达到 `kick-at-vl`，会直接被踢出，无视其豁免身份。
- **修复**：`if (player.isOp()) return;` → `if (data.op) return;`（与检测豁免口径一致）。

### 🟠 4.3 【中】`/ycbr` 子命令无细分权限

- **文件**：`command/YCBRCommand.java`
- **问题**：`onCommand` 内**没有一次** `hasPermission` 检查，完全依赖 plugin.yml 的 `permission: ycbr.admin`（default: op）。其中 `premium add <name>`（免登录白名单）、`toggle`（开关检测）、`reload` 均无代码级守卫。
- **风险**：plugin.yml 声明 `ycbr.admin default: op`，非 OP 玩家在 Bukkit 层面会被拦截——**当前实际安全**。但这是单点防护：若未来改命令注册方式（如移除 plugin.yml permission 字段）、或与其他权限插件交互异常，代码内无兜底。
- **修复**：`onCommand` 开头加 `if (!(sender.hasPermission("ycbr.admin") || manager.isYcbrOp(sender.getName()))) { sender.sendMessage("No permission."); return true; }`（`alerts` 子命令可放行 `ycbr.alerts`）。

### 🟡 4.4 【低】BanCommand 权限白名单略宽

- **文件**：`command/BanCommand.java` L38-39
- **现状**：`allowed = hasPermission("ycbr.admin") || !(sender instanceof Player) || isYcbrOp(name)` —— 控制台 + admin + ycbrop 都能 `/timeban`，合理。
- **备注**：与 4.2 同一处 `isYcbrOp` 口径，无新增风险，仅记录。

### 🟡 4.5 【低】登录认证豁免不含 OP

- **文件**：`listener/BukkitListener.java` L85
- **问题**：`ok = !auth.enabled() || auth.isPremium(name) || isYcbrOp(name)` —— **OP 玩家不在豁免列表**。若服务器开认证且该 OP 未注册，会要求 `/register`。通常 OP 应豁免（或至少提示"认证关闭时正常"）。
- **修复**：视需求加 `|| player.isOp()`（与 4.2 一致口径）。

## 5. 已确认无问题的部分（扫过，未发现）

- `CheckRegistry` 20 处 `data.op` 豁免检查齐全（onMove/onAttack/onPlace/onClientCommand/onRotation/onLook/onBlockDigStart/onThrow/onBowRelease/onHeldItemSlot 全有）。
- `BanManager` 封禁有 `target.isOp()` 保护（L49）。
- `plugin.yml` 声明了 `ycbr.admin`（default: op）、`ycbr.alerts`（default: op），`ycbr`/`timeban`/`untimeban` 命令都挂了权限节点。
- 新增统计检测（AimStatisticsCheck/AimStatsLogic/TimerLogic/FastClickLogic）均已正确接线，非死代码。

## 6. 修复优先级建议

| 优先级 | 事项 | 预计改动 | 状态 |
|--------|------|----------|------|
| P0 | GuiManager `onClick`/`onChat` 加权限复核（4.1） | 每个事件方法顶部 +3 行 | ✅ 已修（commit 见下） |
| P0 | MainThreadHandler 封禁改 `data.op` 豁免（4.2） | 1 行 | ✅ 已修 |
| P1 | FastClickCheck 接入 FastClickLogic 或删类（2.2） | 1 个文件 | ✅ 已接线（此前 Phase 3 完成） |
| P1 | AsyncPacketListener 空 catch 加 debug 日志（3） | 8 处各 +1 行 | ✅ 已修（`logReadFailure` + `settings.debug-packets`） |
| P1 | DDosGuard 空 catch 加 debug 日志（3） | 6 处各 +1 行 | ✅ 已修（`fine()` 级） |
| P1 | BanManager 空 catch 加日志（3） | 1 处 | ✅ 已修（warning 级） |
| P2 | YCBRCommand 加代码级权限守卫（4.3） | onCommand 顶部 +4 行 | ✅ 已修（`ycbr.admin`/`ycbr.op`/`ycbr.alerts`） |
| P2 | 登录豁免 OP（4.5） | 1 行 | ✅ 已修 |

附加：`DatasetManager` 写盘空 catch 补 warning 日志。全部改动待提交。
