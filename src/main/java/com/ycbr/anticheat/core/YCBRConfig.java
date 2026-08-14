package com.ycbr.anticheat.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.ycbr.anticheat.YCBR;

public final class YCBRConfig {

    private final YCBR plugin;
    private FileConfiguration config;

    public YCBRConfig(YCBR plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            if (!file.exists()) {
                plugin.saveDefaultConfig();
            }
            config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load config.yml with UTF-8, falling back to default: " + e.getMessage());
            plugin.reloadConfig();
            config = plugin.getConfig();
        }
    }

    public FileConfiguration raw() {
        if (config == null) {
            reload();
        }
        return config;
    }

    public boolean enabled(String check) {
        return raw().getBoolean("checks." + check + ".enabled", true);
    }

    public double d(String path, double def) {
        return raw().getDouble(path, def);
    }

    public boolean b(String path, boolean def) {
        return raw().getBoolean(path, def);
    }

    public int i(String path, int def) {
        return raw().getInt(path, def);
    }

    public String s(String path, String def) {
        return raw().getString(path, def);
    }

    public void set(String path, Object value) {
        raw().set(path, value);
    }

    public void save() {
        try {
            raw().save(new File(plugin.getDataFolder(), "config.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save config.yml: " + e.getMessage());
        }
    }

    public String prefix() {
        return ChatColor.translateAlternateColorCodes('&', s("settings.alerts-prefix", "&8[&cYCBR&8] &7"));
    }

    public int notifyEvery() {
        return i("settings.notify-every-vl", 1);
    }

    public int tickInterval() {
        return i("settings.tick-interval", 1);
    }

    public int playerSnapshotInterval() {
        return i("settings.player-snapshot-interval-ticks", 2);
    }

    public int entitySnapshotInterval() {
        return i("settings.entity-snapshot-interval-ticks", 10);
    }

    public int checkThreads() {
        return i("settings.check-threads", 2);
    }

    public int maxPing() {
        return i("settings.max-ping-for-checks", 400);
    }

    public int dataSweepMinutes() {
        return i("settings.data-sweep-minutes", 30);
    }

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

    public java.util.List<String> subs(String check) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        org.bukkit.configuration.ConfigurationSection section = raw().getConfigurationSection("checks." + check);
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            if (raw().isSet("checks." + check + "." + key + ".enabled")) {
                result.add(key);
            }
        }
        return result;
    }
}