package com.ycbr.anticheat.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.YCBRConfig;

public final class YCbrOpCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;
    private final GuiManager gui;

    public YCbrOpCommand(AntiCheatManager manager, GuiManager gui) {
        this.manager = manager;
        this.cfg = manager.config();
        this.gui = gui;
    }

    public void register() {
        manager.getPlugin().getCommand("ycbrop").setExecutor(this);
        manager.getPlugin().getCommand("ycbrop").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Console can not open GUI.");
                return true;
            }
            if (!manager.isYcbrOp(sender.getName()) && !sender.hasPermission("ycbr.admin")) {
                sender.sendMessage(cfg.prefix() + "&c\u4f60\u4e0d\u662f\u53cd\u4f5c\u5f0aOP\u3002");
                return true;
            }
            gui.openOp((Player) sender);
            return true;
        }
        if (!sender.hasPermission("ycbr.admin")) {
            sender.sendMessage(cfg.prefix() + "&c\u6ca1\u6709\u6743\u9650\u3002");
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            List<String> list = cfg.raw().getStringList("settings.ycbrop-players");
            if (list.isEmpty()) {
                sender.sendMessage(cfg.prefix() + "&7\u53cd\u4f5c\u5f0aOP\u5217\u8868\u4e3a\u7a7a");
            } else {
                sender.sendMessage(cfg.prefix() + "&e\u53cd\u4f5c\u5f0aOP: &f" + String.join("&7, &f", list));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            if (args.length < 2) {
                sender.sendMessage(cfg.prefix() + "&c\u7528\u6cd5: /ycbrop remove <\u73a9\u5bb6>");
                return true;
            }
            List<String> list = new ArrayList<String>(cfg.raw().getStringList("settings.ycbrop-players"));
            list.remove(args[1]);
            cfg.set("settings.ycbrop-players", list);
            cfg.save();
            sender.sendMessage(cfg.prefix() + "&a" + args[1] + " \u5df2\u79fb\u51fa\u53cd\u4f5c\u5f0aOP\u5217\u8868\u3002");
            return true;
        }
        List<String> list = new ArrayList<String>(cfg.raw().getStringList("settings.ycbrop-players"));
        if (list.contains(args[0])) {
            sender.sendMessage(cfg.prefix() + "&c" + args[0] + " \u5df2\u5728\u53cd\u4f5c\u5f0aOP\u5217\u8868\u4e2d\u3002");
            return true;
        }
        list.add(args[0]);
        cfg.set("settings.ycbrop-players", list);
        cfg.save();
        sender.sendMessage(cfg.prefix() + "&a" + args[0]
                + " \u5df2\u8bbe\u4e3a\u53cd\u4f5c\u5f0aOP\uff08\u4e0d\u53d7\u68c0\u6d4b\uff09\u3002");
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(cfg.prefix() + "&e/ycbrop &f<\u73a9\u5bb6> &7- \u8d4b\u4e88\u53cd\u4f5c\u5f0aOP");
        sender.sendMessage(cfg.prefix() + "&e/ycbrop &fremove <\u73a9\u5bb6> &7- \u79fb\u9664\u53cd\u4f5c\u5f0aOP");
        sender.sendMessage(cfg.prefix() + "&e/ycbrop &flist &7- \u67e5\u770b\u53cd\u4f5c\u5f0aOP\u5217\u8868");
        sender.sendMessage(cfg.prefix() + "&e/ycbrop &fgui &7- \u6253\u5f00\u53cd\u4f5c\u5f0aOP\u754c\u9762");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            for (String option : new String[] { "remove", "list", "gui" }) {
                if (option.startsWith(args[0].toLowerCase())) {
                    completions.add(option);
                }
            }
        }
        return completions;
    }
}