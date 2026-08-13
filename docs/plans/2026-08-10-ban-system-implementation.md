# 1小时临时封禁系统 实施计划

> **给 Claude：** 必须按任务逐项执行本计划（本项目无 git 仓库、无单元测试框架，原计划的"提交/测试"步骤替换为"构建验证 + 服务器手动验证"，已按任务内注明）。设计依据见 `docs/plans/2026-08-10-ban-system-design.md`。

**目标：** 将 YCBR AC 的作弊踢出改为 1 小时临时封禁（bans.yml 持久化、到期自动解封、重新封禁累计叠加）；OP 完全免疫检测；新增 `/timeban` `/untimeban` 指令（权限 ycbr.admin = OP）。

**架构方案：** 新增独立 `BanManager` 组件负责封禁记录的内存缓存 + bans.yml 落盘（全部在主线程 IO）；`MainThreadHandler.handle()` 达标后调用 claim 并踢出；`AsyncPlayerPreLoginEvent` 拒绝已封禁玩家登录；`CheckRegistry` 分发时 op 直接跳过；新建 `BanCommand` 实现两条指令。

**技术栈：** Java 8、Bukkit/Paper 1.8.9 API、YamlConfiguration、ProtocolLib（不改动 packet 层）。

---

## 任务 1：BanManager 核心类

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/core/BanManager.java`

**步骤 1：编写类骨架（完整代码见下）**

```java
package com.ycbr.anticheat.core;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import com.ycbr.anticheat.YCBR;

public final class BanManager {

    private final YCBR plugin;
    private final YCBRConfig cfg;
    private final Map<UUID, BanRecord> bans = new ConcurrentHashMap<UUID, BanRecord>();
    private final File file;

    public BanManager(YCBR plugin) {
        this.plugin = plugin;
        this.cfg = new YCBRConfig(plugin);
        this.file = new File(plugin.getDataFolder(), "bans.yml");
    }

    public static final class BanRecord {
        public final String name;
        public final long expiry;
        public BanRecord(String name, long expiry) {
            this.name = name;
            this.expiry = expiry;
        }
    }

    public void load() {
        bans.clear();
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("bans");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        String name = section.getString(key + ".name", "unknown");
                        long expiry = section.getLong(key + ".expiry", 0L);
                        bans.put(uuid, new BanRecord(name, expiry));
                    } catch (IllegalArgumentException ignore) {
                    }
                }
            }
        } catch (Exception ex) {
            Bukkit.getLogger().severe("YCBR: failed to load bans.yml: " + ex.getMessage());
        }
    }

    public void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, BanRecord> entry : bans.entrySet()) {
                String path = "bans." + entry.getKey().toString();
                yaml.set(path + ".name", entry.getValue().name);
                yaml.set(path + ".expiry", entry.getValue().expiry);
            }
            yaml.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().severe("YCBR: failed to save bans.yml: " + ex.getMessage());
        }
    }

    /** 封禁：未封禁 -> now+hours；已封禁 -> 原到期+hours（累计叠加）。返回新到期时间。 */
    public long claim(UUID uuid, String name) {
        long hours = cfg.banHours();
        long now = System.currentTimeMillis();
        BanRecord record = bans.get(uuid);
        long expiry;
        if (record == null || record.expiry <= now) {
            expiry = now + hours * 3600_000L;
        } else {
            expiry = record.expiry + hours * 3600_000L;
        }
        bans.put(uuid, new BanRecord(name, expiry));
        save();
        return expiry;
    }

    /** 解封并落盘。返回是否确有记录被删除。 */
    public boolean pardon(UUID uuid) {
        if (bans.remove(uuid) != null) {
            save();
            return true;
        }
        return false;
    }

    /** 已封禁（未过期）-> true；过期记录惰性删除。 */
    public boolean isBanned(UUID uuid) {
        BanRecord record = bans.get(uuid);
        if (record == null) {
            return false;
        }
        if (record.expiry <= System.currentTimeMillis()) {
            bans.remove(uuid);
            save();
            return false;
        }
        return true;
    }

    public BanRecord get(UUID uuid) {
        return bans.get(uuid);
    }

    public List<BanRecord> snapshot() {
        return new ArrayList<BanRecord>(bans.values());
    }

    /** 剩余时间，格式 "1小时2分30秒"。 */
    public static String remaining(long expiry) {
        long ms = expiry - System.currentTimeMillis();
        if (ms <= 0) {
            return "0秒";
        }
        long s = ms / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append("小时");
        }
        if (m > 0) {
            sb.append(m).append("分");
        }
        sb.append(sec).append("秒");
        return sb.toString();
    }

    /** 到期时间，北京时间 "2026-08-10 02:10:00"。 */
    public static String formatExpiry(long expiry) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new Date(expiry));
    }

    /** 替换 %remaining% / %time% 占位符并转译颜色码。 */
    public String applyPlaceholders(String raw, long expiry) {
        String text = raw.replace("%remaining%", remaining(expiry)).replace("%time%", formatExpiry(expiry));
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }
}
```

**步骤 2：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`（workdir=YCBR-AC）
预期：**构建成功**，`target/YCBR.jar` 更新，无编译错误。

---

## 任务 2：PlayerData.op 快照 + 检测层 OP 豁免

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/data/PlayerData.java`（新增字段）
- 修改：`src/main/java/com/ycbr/anticheat/pipeline/MainThreadHandler.java:110-122`（snapshotPlayers 增加 op）
- 修改：`src/main/java/com/ycbr/anticheat/check/CheckRegistry.java:46-63`（分发入口 op 跳过）

**步骤 1：PlayerData 新增字段**

在 `public volatile boolean creative;` 附近（flying/inVehicle/dead 区块）加：

```java
public volatile boolean op;
```

**步骤 2：snapshotPlayers 填充**

`MainThreadHandler.snapshotPlayers()` 内紧邻 `data.creative = ...` 后加：

```java
data.op = player.isOp();
```

**步骤 3：CheckRegistry 分发跳过**

`onMove`、`onAttack`、`onPlace` 三个方法开头各加一行：

```java
if (ctx.data.op) {
    return;
}
```

**步骤 4：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 3：MainThreadHandler 封禁替换踢出

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/pipeline/MainThreadHandler.java:153-175`（handle 方法）

**步骤 1：改写 handle() 的惩罚分支**

原逻辑（`vl >= kickAt` -> kickPlayer + 重置 vl）改为：

```java
int kickAt = cfg.i("checks." + verdict.type.getConfigPath() + ".kick-at-vl", 20);
if (vl >= kickAt) {
    data.resetViolations(verdict.type);
    if (player.isOp()) {
        return;
    }
    long expiry = manager.getBanManager().claim(player.getUniqueId(), player.getName());
    String message = cfg.s("settings.ban.kick-message",
            "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
    player.kickPlayer(manager.getBanManager().applyPlaceholders(message, expiry));
    Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&c" + player.getName()
            + " banned by " + verdict.type.getDisplay() + ", expires "
            + com.ycbr.anticheat.core.BanManager.formatExpiry(expiry) + " (Beijing time)");
}
```

需要 import：`com.ycbr.anticheat.core.BanManager`。

**步骤 2：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 4：BukkitListener 登录拒绝

**涉及文件：**
- 修改：`src/main/java/com/ycbr/anticheat/listener/BukkitListener.java`（新增监听方法 + import）

**步骤 1：新增 AsyncPlayerPreLoginEvent 处理**

```java
@EventHandler(priority = EventPriority.LOWEST)
public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    com.ycbr.anticheat.core.BanManager.BanRecord record =
            manager.getBanManager().get(event.getUniqueId());
    if (record == null) {
        return;
    }
    if (manager.getBanManager().isBanned(event.getUniqueId())) {
        String message = manager.config().s("settings.ban.login-denied-message",
                "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                manager.getBanManager().applyPlaceholders(message, record.expiry));
    }
}
```

新增 import：`org.bukkit.event.player.AsyncPlayerPreLoginEvent`。

**步骤 2：onJoin 兜底清理过期记录**

在现有 `onJoin` 方法末尾加：

```java
if (!manager.getBanManager().isBanned(player.getUniqueId())) {
    // 过期或未封禁：isBanned 内部已惰性删除过期记录并落盘
}
```

（如需更显式的语义可改为上面注释说明；isBanned 内部已有惰性删除，此兜底确保玩家成功进入后过期记录被清。）

**步骤 3：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 5：BanCommand（/timeban /untimeban）

**涉及文件：**
- 新建：`src/main/java/com/ycbr/anticheat/command/BanCommand.java`
- 修改：`src/main/java/com/ycbr/anticheat/core/AntiCheatManager.java`（enable 接线）
- 修改：`src/main/resources/plugin.yml`（commands 注册）

**步骤 1：命令实现（完整代码见下）**

```java
package com.ycbr.anticheat.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.BanManager;
import com.ycbr.anticheat.core.YCBRConfig;

public final class BanCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;

    public BanCommand(AntiCheatManager manager) {
        this.manager = manager;
        this.cfg = manager.config();
    }

    public void register() {
        manager.getPlugin().getCommand("timeban").setExecutor(this);
        manager.getPlugin().getCommand("timeban").setTabCompleter(this);
        manager.getPlugin().getCommand("untimeban").setExecutor(this);
        manager.getPlugin().getCommand("untimeban").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ycbr.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.isOp()) {
            sender.sendMessage(ChatColor.RED + "Cannot " + label + " an operator.");
            return true;
        }
        BanManager banManager = manager.getBanManager();
        if (label.equalsIgnoreCase("timeban")) {
            long expiry = banManager.claim(target.getUniqueId(), target.getName());
            Player online = target.getPlayer();
            if (online != null && online.isOnline()) {
                online.kickPlayer(banManager.applyPlaceholders(
                        cfg.s("settings.ban.kick-message",
                                "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）"),
                        expiry));
            }
            sender.sendMessage(ChatColor.GREEN + args[0] + " banned until "
                    + BanManager.formatExpiry(expiry) + " (Beijing time)");
        } else {
            boolean removed = banManager.pardon(target.getUniqueId());
            sender.sendMessage(removed ? ChatColor.GREEN + args[0] + " unbanned."
                    : ChatColor.YELLOW + args[0] + " is not banned.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
            if (alias.equalsIgnoreCase("untimeban")) {
                for (BanManager.BanRecord record : manager.getBanManager().snapshot()) {
                    if (record.name.toLowerCase().startsWith(args[0].toLowerCase())
                            && !completions.contains(record.name)) {
                        completions.add(record.name);
                    }
                }
            }
        }
        return completions;
    }
}
```

**步骤 2：AntiCheatManager 接线**

- 字段：`private BanManager banManager;`
- `enable()` 中：`banManager = new BanManager(plugin);` + `banManager.load();`，并在 `new YCBRCommand(this).register();` 后加 `new BanCommand(this).register();`
- `disable()` 中：`if (banManager != null) { banManager.save(); }`
- 新增 getter：`public BanManager getBanManager() { return banManager; }`
- 新增 import：`com.ycbr.anticheat.command.BanCommand;`、`com.ycbr.anticheat.core.BanManager;`

**步骤 3：plugin.yml 注册命令**

在 commands 区 `ycbr` 节点下并列追加：

```yaml
  timeban:
    description: Ban a player for 1 hour
    usage: /timeban <player>
    permission: ycbr.admin
  untimeban:
    description: Unban a player
    usage: /untimeban <player>
    permission: ycbr.admin
```

**步骤 4：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 6：config.yml 新键 + YCBRConfig 读取

**涉及文件：**
- 修改：`src/main/resources/config.yml`（settings 区追加 ban 配置）
- 修改：`src/main/java/com/ycbr/anticheat/core/YCBRConfig.java`（新增 3 个读取方法）

**步骤 1：config.yml settings 区追加**

在 `settings:` 下 `violation-decay-seconds: 60` 之后、同级缩进追加：

```yaml
  ban:
    hours: 1
    kick-message: "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）"
    login-denied-message: "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）"
```

（config.yml 使用 2 空格缩进，`ban:` 与 `violation-decay-seconds` 同级。）

**步骤 2：YCBRConfig 新增方法**

```java
public int banHours() {
    return i("settings.ban.hours", 1);
}

public String banKickMessage() {
    return s("settings.ban.kick-message",
            "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
}

public String banLoginDeniedMessage() {
    return s("settings.ban.login-denied-message",
            "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
}
```

**步骤 3：合并任务 3/4 中的硬编码默认串**

任务 3/4 中 `cfg.s("settings.ban.kick-message", ...)` 的默认参数可替换为 `cfg.banKickMessage()` / `cfg.banLoginDeniedMessage()`（可选优化，不强制；若替换需同步 import 不新增依赖）。

**步骤 4：构建验证**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

---

## 任务 7：最终构建与服务器验证

**步骤 1：完整构建 + 类版本检查**

运行：`& "C:/Users/WIN10/AppData/Local/Temp/opencode/maven/apache-maven-3.9.16/bin/mvn.cmd" -q -DskipTests package`
预期：**构建成功**。

类版本检查（沿用本项目惯例）：`jar tf target/YCBR.jar` 任取一个 class，读取 class 文件头 8 字节，第 7-8 字节应显示 `00 34`（major 52 = Java 8）。

**步骤 2：部署到测试服务器并手动验证清单**

1. 覆盖 `plugins/YCBR.jar`，重启服务器，控制台无异常，`plugins/YCBR/bans.yml` 由保存逻辑生成或加载。
2. 反作弊封禁：普通玩家飞行/疾跑超阈值 -> 被踢且控制台出现 "banned by Fly/Speed ... expires ...(Beijing time)"，重进被拒且消息显示剩余约 59 分钟 + 到期时间（北京时间）。
3. 到期解封：把 bans.yml 的 expiry 手动改小（或等 1 小时）-> 重进成功，bans.yml 中该条记录被自动清除。
4. 指令：`/timeban <未检测玩家>` -> 提示封禁成功；该玩家在线则被踢；重进被拒。
5. 累计叠加：对同一玩家再次 `/timeban` -> 到期时间 = 上次 + 1 小时。
6. `/untimeban <玩家>` -> 显示已解封，重进成功；再 `/untimeban` -> 提示 "is not banned"。
7. OP 豁免：OP 开飞行 >= 3 分钟 -> 无告警、无封禁；OP 被 `/timeban` -> 提示 "Cannot timeban an operator"。
8. 重启服务器 -> 封禁记录仍在；到期后进入成功。

**步骤 3：边界案例复验**

- 控制台执行 `/timeban <name>`（非玩家发送者）-> 正常工作。
- bans.yml 被手动损坏（写入乱码）-> 插件启动不崩溃，控制台输出加载失败日志，不影响反作弊主流程。
- 封禁玩家改 ID -> 无法绕过（uuid 维度匹配）。

---

## 验证方式

任务内已内联：`mvn -q -DskipTests package` 每次通过 + 任务 7 手动清单全项通过。

## 风险与注意事项

- `AsyncPlayerPreLoginEvent` 是异步事件，BanManager 的 map 用 ConcurrentHashMap，isBanned 只读安全；claim/pardon/save 均在主线程（命令回调、handle()）调用，无写竞争。
- `Bukkit.getOfflinePlayer(name)` 在 1.8 正版服上对从未登录过的名字拿不到真实 uuid；本插件面向离线/小型服场景，可接受（设计与备注中已注明）。
- OP 豁免双保险：CheckRegistry 跳过 + handle() isOp 检查 + BanCommand isOp 检查。
- 到期时间一律存 epoch 毫秒（与时区无关），展示时统一转北京时间（Asia/Shanghai）。