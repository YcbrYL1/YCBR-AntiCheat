package com.ycbr.anticheat.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.AuthManager;
import com.ycbr.anticheat.core.YCBRConfig;
import com.ycbr.anticheat.data.PlayerData;

public final class AuthCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;

    public AuthCommand(AntiCheatManager manager) {
        this.manager = manager;
        this.cfg = manager.config();
    }

    public void register() {
        manager.getPlugin().getCommand("register").setExecutor(this);
        manager.getPlugin().getCommand("register").setTabCompleter(this);
        manager.getPlugin().getCommand("login").setExecutor(this);
        manager.getPlugin().getCommand("login").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Console can not use this command.");
            return true;
        }
        Player player = (Player) sender;
        PlayerData data = manager.getDataManager().get(player.getUniqueId());
        AuthManager auth = manager.getAuthManager();
        if (!auth.enabled()) {
            player.sendMessage(cfg.prefix() + "&c认证功能已关闭");
            return true;
        }
        if (auth.isPremium(player.getName())) {
            player.sendMessage(cfg.prefix() + "&c正版账号无需注册/登录");
            return true;
        }
        if (label.equalsIgnoreCase("register")) {
            if (args.length != 2) {
                player.sendMessage(cfg.prefix() + "&c用法: /register [密码] [密码]");
                return true;
            }
            if (data.authenticated) {
                player.sendMessage(cfg.prefix() + "&c您已登录，无需注册");
                return true;
            }
            if (auth.isRegistered(player.getName())) {
                player.sendMessage(cfg.prefix() + "&c该账号已注册，请使用 /login [密码] 登录");
                return true;
            }
            if (!args[0].equals(args[1])) {
                player.sendMessage(cfg.prefix() + "&c两次输入的密码不一致");
                return true;
            }
            if (args[0].length() < 4 || args[0].length() > 32) {
                player.sendMessage(cfg.prefix() + "&c密码长度需在 4-32 位之间");
                return true;
            }
            auth.register(player.getName(), args[0]);
            data.authenticated = true;
            auth.recordSession(player.getName(), ipOf(player));
            player.sendMessage(cfg.prefix() + "&a注册成功，欢迎进入服务器！");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(cfg.prefix() + "&c用法: /login [密码]");
            return true;
        }
        if (data.authenticated) {
            player.sendMessage(cfg.prefix() + "&c您已登录，无需重复登录");
            return true;
        }
        if (!auth.isRegistered(player.getName())) {
            player.sendMessage(cfg.prefix() + "&c该账号未注册，请使用 /register [密码] [密码] 注册");
            return true;
        }
        if (!auth.verify(player.getName(), args[0])) {
            player.sendMessage(cfg.prefix() + "&c密码错误，请重试");
            return true;
        }
        data.authenticated = true;
        auth.recordSession(player.getName(), ipOf(player));
        player.sendMessage(cfg.prefix() + "&a登录成功！");
        return true;
    }

    private static String ipOf(Player player) {
        if (player.getAddress() == null) {
            return "";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<String>();
    }
}
