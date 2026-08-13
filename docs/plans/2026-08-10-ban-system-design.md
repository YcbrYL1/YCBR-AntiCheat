# 封禁系统（1小时临时封禁）设计说明

## 背景与目标
- 将 YCBR AC 现有的"作弊踢出"改为"作弊封禁"：VL 达到检测项阈值时封禁玩家（默认 1 小时），到期自动解封。
- OP 玩家完全免疫：不检测、不告警、不可被反作弊封禁。
- 提供指令：`/timeban <player>`（封禁 1 小时）、`/untimeban <player>`（取消封禁），仅限 OP（ycbr.admin）。
- 封禁记录重启服务器后依然生效。

## 现状与约束
- 当前惩罚逻辑位于 `MainThreadHandler.handle()`（MainThreadHandler.java:166-174）：`vl >= kick-at-vl(默认20)` 时 `player.kickPlayer()` 并重置 vl。
- 命令仅 `/ycbr`（reload/alerts），权限 `ycbr.admin`（默认 op）、`ycbr.alerts`。
- 无任何封禁存储；`YCBRConfig` 基于 config.yml，reload 会重建默认配置。
- 运行环境：1.8.9 Paper + ProtocolLib 5.0.0-SNAPSHOT-b608，Java 8 编译（class major 52）。
- 项目不是 git 仓库，设计文档不提交版本控制。

## 方案对比
### 方案一：独立 BanManager 组件（推荐）
- 优点：数据与 config.yml 隔离（reload 不丢）；到期精确到毫秒；代码内聚；重启保留。
- 缺点：新增约 150 行代码。

### 方案二：复用 Bukkit 内置封禁
- 优点：登录拒绝原生支持、零存储代码。
- 缺点：无到期机制；需要额外定时器解封；无法累计叠加时长；AC 直接改服务器封禁列表不干净。

### 方案三：封禁写进 config.yml
- 优点：复用 YCBRConfig。
- 缺点：`/ycbr reload` 会覆盖丢失；打包默认值重建；不可靠。

## 推荐方案
方案一。独立 `BanManager` 组件 + `bans.yml` 持久化。

## 详细设计

### 架构
```
BanManager (核心，所有文件IO在主线程)
  ├─ bans.yml 读写（plugins/YCBR/bans.yml）
  ├─ claim(uuid, name)      → 封禁入口（含叠加规则）
  ├─ pardon(uuid)           → 解封入口
  ├─ isBanned(uuid)         → 到期惰性判断
  └─ 到期时间格式化（北京时间显示）
      │
      ├─ MainThreadHandler.handle()  替换 kickPlayer → claim + kick
      ├─ BukkitListener              新增 AsyncPlayerPreLoginEvent 拒绝登录
      ├─ BanCommand                  /timeban /untimeban
      └─ CheckRegistry               分发时 op 直接跳过（PlayerData.op 快照）
```

### 关键组件
1. **BanManager**（新增 `core/BanManager.java`）
   - 内存：`ConcurrentHashMap<UUID, BanRecord>`；`BanRecord { String name; long expiryEpochMs; }`
   - load()：插件 onEnable 读文件；save()：每次变更后写盘（主线程）
   - `claim(uuid, name)`：已禁 → `expiry += hours×3600_000`（累计叠加）；未禁 → `expiry = now + hours×3600_000`
   - `pardon(uuid)`：删除记录并写盘
   - `isBanned(uuid)`：查内存，未过期返回 true；过期 → 惰性删除并返回 false
   - 文件 IO 异常 → 控制台输出堆栈，内存操作仍生效（下次 save 重试）

2. **PlayerData.op 快照**（`pipeline/MainThreadHandler.java` snapshotPlayers 增加 `data.op = player.isOp()`；PlayerData 增加 `public volatile boolean op;`）
   - `CheckRegistry` 分发 onMove/onAttack/onPlace（或对应入口）时 `if (data.op) return;` 完全跳过检测
   - 同时 `MainThreadHandler.queue()` 或 handle 阶段过滤 op 的 verdict（双保险）

3. **封禁替换踢出**（`pipeline/MainThreadHandler.java` handle()）
   - `vl >= kick-at-vl` 时：`banManager.claim(uuid, name)` → `banManager.isBanned` 成立后 `player.kickPlayer(封禁消息含剩余时间)` → 控制台记录 "banned by ..."
   - `kick-message` 语义变为封禁踢出消息；重置 vl 逻辑保留

4. **登录拒绝**（`listener/BukkitListener.java` 新增）
   - `AsyncPlayerPreLoginEvent`：`banManager.isBanned(event.getUniqueId())` 为真 → `event.disallow(Result.KICK_BANNED, "剩余 X 分钟，到期 北京时间 ...")`；过期记录自动清
   - `PlayerJoinEvent`：兜底清除该 uuid 过期记录

5. **BanCommand**（新增 `command/BanCommand.java`，在 plugin.yml 注册 `timeban`/`untimeban`，权限 `ycbr.admin`）
   - `/timeban <player>`：目标为 OP → 拒绝；`Bukkit.getOfflinePlayer(name).getUniqueId()`；在线 → 一并踢出；回显到期时间（北京时间）
   - `/untimeban <player>`：删除记录；在线玩家不踢，仅解封
   - TabCompleter：补全在线玩家名 + 封禁列表中的名字

6. **配置**（config.yml 新增，YCBRConfig 增加读取）
   ```yaml
   settings:
     ban:
       hours: 1                     # 封禁时长（小时）
       kick-message: "&c你已被 YCBR 反作弊封禁 %remaining%，到期时间：%time%（北京时间）"
       login-denied-message: "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）"
   ```
   - %remaining% = "59分30秒"；%time% = "2026-08-10 02:10:00"（北京时间）
   - 消息经 `ChatColor.translateAlternateColorCodes` 处理

### 数据流
- 检测作弊 → Verdict 入队 → 主线程 handle() → vl 达标 → BanManager.claim（bans.yml 写盘）→ kickPlayer（消息含剩余时间）
- 玩家重进 → AsyncPlayerPreLoginEvent → isBanned true → disallow 拒绝（消息含剩余时间）
- 管理员 /timeban → BanCommand → BanManager.claim（叠加）→ 在线则踢出 → 回显到期时间
- 管理员 /untimeban → BanManager.pardon → bans.yml 写盘 → 回显解除

### 异常与边界处理
- bans.yml 不存在 → 视为空；损坏 → 备份并重建空文件
- 文件 IO 失败 → 控制台堆栈 + 内存仍生效
- OP 封禁拒绝：检测跳过 + 命令拒绝 + claim 前检查（三重防护）
- 封禁中玩家再次被检测 → 无法发生（已离线），但 claim 叠加规则已覆盖该情况
- 玩家改名 → 按 uuid 匹配，封禁不受影响；bans.yml 中 name 字段仅用于展示与命令补全
- 服务器时区不同 → 存储用 epoch 毫秒（UTC），显示时转北京时间（Asia/Shanghai）

### 测试策略
- `mvn -q -DskipTests package` 构建成功，class major 52
- 上 1.8.9 服验证：
  1. 玩家飞行超阈值触发封禁 → 踢出 → 重进被拒（剩余 59 分钟显示）→ 到期后进入成功
  2. `/timeban` 未检测玩家 → 封禁生效；再 `/timeban` 同一人 → 到期 +1h 累计；`/untimeban` → 解除可进
  3. OP 开飞行 → 零告警、不被封禁；OP 被 `/timeban` → 提示拒绝
  4. 重启服务器 → 封禁记录仍在

## 风险与待确认项
- 无（需求已通过用户确认：bans.yml 持久化、kick-at-vl 即封禁阈值、累计叠加、北京时间显示、OP 完全免疫、时长可配置默认 1h）