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
        boolean allowed = sender.hasPermission("ycbr.admin") || !(sender instanceof Player)
                || manager.isYcbrOp(sender.getName());
        if (!allowed) {
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