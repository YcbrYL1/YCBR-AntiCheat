package com.ycbr.anticheat.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.YCBRConfig;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.util.MathUtil;

public final class YCBRCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;
    private final GuiManager gui;

    public YCBRCommand(AntiCheatManager manager, GuiManager gui) {
        this.manager = manager;
        this.cfg = manager.config();
        this.gui = gui;
    }

    public void register() {
        manager.getPlugin().getCommand("ycbr").setExecutor(this);
        manager.getPlugin().getCommand("ycbr").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help":
            case "?":
                sendHelp(sender);
                break;
            case "reload":
                manager.reload();
                sender.sendMessage(cfg.prefix() + "&aconfiguration reloaded.");
                break;
            case "alerts":
                toggleAlerts(sender);
                break;
            case "gui":
                openGui(sender);
                break;
            case "toggle":
                toggleCheck(sender, args);
                break;
            case "list":
                listChecks(sender);
                break;
            case "debug":
                debug(sender, args);
                break;
            case "premium":
                premium(sender, args);
                break;
            case "strict":
                strict(sender, args);
                break;
            case "record":
                record(sender, args);
                break;
            case "stoprecord":
                stopRecord(sender, args);
                break;
            default:
                sendUsage(sender);
                break;
        }
        return true;
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Console can not toggle alerts.");
            return;
        }
        Player player = (Player) sender;
        boolean enabled = manager.getMainHandler().toggleAlert(player.getUniqueId());
        player.sendMessage(cfg.prefix() + (enabled ? "&aAlerts enabled." : "&cAlerts disabled."));
    }

    private void openGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Console can not open GUI.");
            return;
        }
        gui.open((Player) sender);
    }

    private void toggleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(cfg.prefix() + "&e/ycbr toggle &f<check|sub> [on|off]");
            sender.sendMessage(cfg.prefix() + "&7e.g. &e/ycbr toggle killaura.angle off");
            return;
        }
        String path = args[1].toLowerCase();
        CheckType type = findType(path);
        if (type == null) {
            sender.sendMessage(cfg.prefix() + "&cUnknown check: &f" + args[1]);
            return;
        }
        boolean target;
        if (args.length >= 3) {
            if (args[2].equalsIgnoreCase("on")) {
                target = true;
            } else if (args[2].equalsIgnoreCase("off")) {
                target = false;
            } else {
                sender.sendMessage(cfg.prefix() + "&cExpected on|off, got: &f" + args[2]);
                return;
            }
        } else {
            target = !cfg.enabled(path);
        }
        cfg.set("checks." + path + ".enabled", target);
        cfg.save();
        manager.reload();
        sender.sendMessage(cfg.prefix() + "&a" + args[1] + (target ? " enabled." : " disabled."));
    }

    private void listChecks(CommandSender sender) {
        sender.sendMessage(cfg.prefix() + "&eCheck status:");
        for (CheckType type : CheckType.values()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    cfg.prefix() + "&7- &f" + type.getDisplay() + (cfg.enabled(type.getConfigPath())
                            ? " &aON" : " &cOFF")));
        }
    }

    private void debug(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(cfg.prefix() + "&cPlayer not found: &f" + args[1]);
                return;
            }
            dumpDetail(sender, manager.getDataManager().get(target.getUniqueId()));
            return;
        }
        int radius = 0;
        if (args.length >= 2 && isInt(args[1])) {
            radius = Integer.parseInt(args[1]);
        } else if (args.length >= 2 && isInt(args[0])) {
            radius = Integer.parseInt(args[0]);
        }
        double ox = 0D;
        double oz = 0D;
        if (sender instanceof Player) {
            ox = ((Player) sender).getLocation().getX();
            oz = ((Player) sender).getLocation().getZ();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player && player.getUniqueId().equals(((Player) sender).getUniqueId())) {
                continue;
            }
            if (radius > 0) {
                double dx = player.getLocation().getX() - ox;
                double dz = player.getLocation().getZ() - oz;
                if (Math.sqrt(dx * dx + dz * dz) > radius) {
                    continue;
                }
            }
            dumpSummary(sender, player);
        }
        sender.sendMessage(cfg.prefix() + "&7Done.");
    }

    private void dumpSummary(CommandSender sender, Player player) {
        PlayerData data = manager.getDataManager().get(player.getUniqueId());
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&b" + player.getName() + " &7ping=&f" + data.ping
                        + "&7 cps=&f" + data.attackTimes.size()
                        + "&7 vl=&f" + totalVl(data)
                        + "&7 mY=&f" + MathUtil.round(data.movement.motionY, 2)
                        + "&7 air=&f" + data.movement.airTicks));
    }

    private void dumpDetail(CommandSender sender, PlayerData data) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&b" + Bukkit.getPlayer(data.getUuid()).getName() + " &7Ping: &f"
                        + data.ping + "ms &7CPS: &f" + data.attackTimes.size()));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&7Move: &fmY=" + MathUtil.round(data.movement.motionY, 2)
                        + " &7distXZ=&f" + MathUtil.round(data.movement.distanceXZ, 2)
                        + " &7ground=&f" + data.movement.onGround
                        + " &7airTicks=&f" + data.movement.airTicks));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&7State: &fladder=&f" + data.movement.ladderTicks + "t"
                        + " &7liquid=&f" + data.movement.nearLiquidTicks + "t"
                        + " &7web=&f" + data.movement.inWebTicks + "t"
                        + " &7slime=&f" + data.movement.slimeTicks + "t"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&7Rot: &fyaw=&f" + MathUtil.round(data.lastYaw, 1)
                        + " &7pitch=&f" + MathUtil.round(data.lastPitch, 1)
                        + " &7yawDelta=&f" + MathUtil.round(data.lastYawDelta, 1)));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&7VL: &f" + vlLine(data)));
    }

    private String vlLine(PlayerData data) {
        StringBuilder sb = new StringBuilder();
        for (CheckType type : CheckType.values()) {
            long vl = data.getViolations(type);
            if (vl > 0) {
                if (sb.length() > 0) {
                    sb.append(" &7| ");
                }
                sb.append("&f").append(type.getDisplay()).append("=&e").append(vl);
            }
        }
        if (sb.length() == 0) {
            sb.append("&7all zero");
        }
        return sb.toString();
    }

    private long totalVl(PlayerData data) {
        long total = 0L;
        for (CheckType type : CheckType.values()) {
            total += data.getViolations(type);
        }
        return total;
    }

    private CheckType findType(String path) {
        for (CheckType type : CheckType.values()) {
            if (path.equals(type.getConfigPath()) || path.startsWith(type.getConfigPath() + ".")) {
                return type;
            }
        }
        return null;
    }

    private boolean isInt(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void premium(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(cfg.prefix() + "&e/ycbr premium &f<add|remove|list> [name]");
            sender.sendMessage(cfg.prefix() + "&7正版玩家加入列表后可直接进入服务器，无需注册/登录");
            return;
        }
        java.util.List<String> list = cfg.raw().getStringList("settings.auth-premium-players");
        String action = args[1].toLowerCase();
        if (action.equals("list")) {
            if (list.isEmpty()) {
                sender.sendMessage(cfg.prefix() + "&7正版列表为空");
            } else {
                sender.sendMessage(cfg.prefix() + "&e正版玩家: &f" + String.join("&7, &f", list));
            }
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(cfg.prefix() + "&c用法: /ycbr premium add|remove <name>");
            return;
        }
        String name = args[2];
        if (action.equals("add")) {
            if (list.contains(name)) {
                sender.sendMessage(cfg.prefix() + "&c" + name + " 已在列表中");
                return;
            }
            list.add(name);
        } else if (action.equals("remove")) {
            list.remove(name);
        } else {
            sender.sendMessage(cfg.prefix() + "&c未知操作: &f" + action);
            return;
        }
        cfg.set("settings.auth-premium-players", list);
        cfg.save();
        sender.sendMessage(cfg.prefix() + "&a" + name + " 已" + (action.equals("add") ? "加入正版列表" : "移出正版列表"));
    }

    private void strict(CommandSender sender, String[] args) {
        boolean target;
        if (args.length >= 2 && args[1].equalsIgnoreCase("on")) {
            target = true;
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            target = false;
        } else {
            target = !cfg.raw().getBoolean("settings.strict-mode", false);
        }
        cfg.set("settings.strict-mode", target);
        cfg.save();
        manager.reload();
        sender.sendMessage(cfg.prefix() + "&a\u4e25\u683c\u6a21\u5f0f\u5df2" + (target ? "\u5f00\u542f"
                : "\u5173\u95ed") + "\u3002");
    }

    private void record(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(cfg.prefix() + "&e/ycbr record &f<player> [label] &7- 开始录制数据集 (label: legit|cheat)");
            return;
        }
        String name = args[1];
        String label = args.length >= 3 ? args[2] : "legit";
        if (!label.equals("legit") && !label.equals("cheat")) {
            sender.sendMessage(cfg.prefix() + "&clabel 只能是 legit 或 cheat");
            return;
        }
        if (Bukkit.getPlayerExact(name) == null) {
            sender.sendMessage(cfg.prefix() + "&cPlayer not found: &f" + name);
            return;
        }
        manager.getDatasetManager().startRecording(name, label);
        manager.getDatasetManager().writeHeader(name);
        sender.sendMessage(cfg.prefix() + "&a开始录制 &f" + name + " &7(label=" + label + ")，样本写入 plugins/YCBR/dataset/");
    }

    private void stopRecord(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(cfg.prefix() + "&e/ycbr stoprecord &f<player>");
            return;
        }
        manager.getDatasetManager().stopRecording(args[1]);
        sender.sendMessage(cfg.prefix() + "&a已停止录制 &f" + args[1]);
    }

    private void sendHelp(CommandSender sender) {
        String[][] lines = new String[][] {
                { "&e/ycbr help", "&7查看本帮助" },
                { "&e/ycbr reload", "&7重载配置文件" },
                { "&e/ycbr alerts", "&7切换反作弊警报开关" },
                { "&e/ycbr gui", "&7打开管理界面（检测开关/配置/DDoS）" },
                { "&e/ycbr toggle <检测[.子检测]> [on|off]", "&7开关单个检测，如 /ycbr toggle killaura.angle off" },
                { "&e/ycbr list", "&7查看所有检测的开关状态" },
                { "&e/ycbr debug [玩家|半径]", "&7查看玩家实时数据（ping/CPS/移动/违规值）" },
                { "&e/ycbr premium add|remove|list <名字>", "&7正版白名单管理（免注册/登录）" },
                { "&e/ycbr strict [on|off]", "&7严格模式开关（叠 timeTest 不叠违规值）" },
                { "&e/ycbr record <玩家> [legit|cheat]", "&7开始录制数据集样本（MLP 训练用）" },
                { "&e/ycbr stoprecord <玩家>", "&7停止录制数据集样本" },
                { "&e/timeban <玩家>", "&7临时封禁玩家（默认1小时，北京时间显示）" },
                { "&e/untimeban <玩家>", "&7解除封禁" },
                { "&e/register <密码> <密码>", "&7注册账号（密码4-32位）" },
                { "&e/login <密码>", "&7登录账号" },
                { "&e/ycbrop <玩家|remove|list|gui>", "&7反作弊OP管理（免检测/幽灵观战/人工检测）" },
        };
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e========= YCBR 指令帮助 ========="));
        for (String[] line : lines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    cfg.prefix() + line[0] + " " + line[1]));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &fhelp &7- \u67e5\u770b\u6240\u6709\u6307\u4ee4"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &freload &7- reload config"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &falerts &7- toggle alerts"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &fgui &7- open management GUI"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &ftoggle &f<check[.sub]> [on|off] &7- toggle a check"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &flist &7- list check states"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &fdebug &f[player|radius] &7- view live data"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &fpremium &f<add|remove|list> [name] &7- premium whitelist"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&e/ycbr &fstrict &f[on|off] &7- \u4e25\u683c\u6a21\u5f0f (timeTest)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            for (String option : new String[] { "reload", "alerts", "gui", "toggle", "list", "debug", "premium",
                    "strict", "record", "stoprecord", "help" }) {
                if (option.startsWith(args[0].toLowerCase())) {
                    completions.add(option);
                }
            }
        } else if (args.length == 2) {
            String base = args[0].toLowerCase();
            if (base.equals("toggle")) {
                for (CheckType type : CheckType.values()) {
                    if (type.getConfigPath().startsWith(args[1].toLowerCase())) {
                        completions.add(type.getConfigPath());
                    }
                }
            } else if (base.equals("debug")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().startsWith(args[1])) {
                        completions.add(player.getName());
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("toggle")) {
            for (String option : new String[] { "on", "off" }) {
                if (option.startsWith(args[2].toLowerCase())) {
                    completions.add(option);
                }
            }
        }
        return completions;
    }
}
