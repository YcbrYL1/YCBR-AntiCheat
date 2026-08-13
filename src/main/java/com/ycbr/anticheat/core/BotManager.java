package com.ycbr.anticheat.core;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.YCBR;

public final class BotManager {

    private final YCBR plugin;
    private final YCBRConfig cfg;
    private final Map<String, Record> names = new ConcurrentHashMap<String, Record>();
    private final File file;

    public static final class Record {
        public final String ip;
        public final long lastJoin;

        public Record(String ip, long lastJoin) {
            this.ip = ip;
            this.lastJoin = lastJoin;
        }
    }

    public BotManager(YCBR plugin) {
        this.plugin = plugin;
        this.cfg = new YCBRConfig(plugin);
        this.file = new File(plugin.getDataFolder(), "botchecks.yml");
    }

    private boolean enabled() {
        return cfg.raw().getBoolean("settings.bot-verification.enabled", true);
    }

    private int maxAccounts() {
        return cfg.raw().getInt("settings.bot-verification.max-accounts-per-ip", 5);
    }

    private long retentionDays() {
        return cfg.raw().getLong("settings.bot-verification.account-retention-days", 30);
    }

    /**
     * 进入时调用。返回需要踢出的消息（已转译颜色），null 表示放行。
     * 首次进入（或换 IP 后）必踢一次登记信息；重进时若该 IP 已登记超过
     * maxAccounts 个名字，则拒绝并提示大量账号。
     */
    public String check(Player player) {
        if (!enabled()) {
            return null;
        }
        String ip = player.getAddress() == null ? ""
                : player.getAddress().getAddress().getHostAddress();
        String key = player.getName().toLowerCase();
        long now = System.currentTimeMillis();
        Record record = names.get(key);
        if (record == null || !record.ip.equals(ip)) {
            names.put(key, new Record(ip, now));
            save();
            Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&e" + player.getName()
                    + " &7bot-check: first join from &f" + ip + "&7, kicked for re-verify");
            return kickFirst();
        }
        int count = countNames(ip);
        if (count > maxAccounts()) {
            Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&e" + player.getName()
                    + " &7bot-check: &f" + count + " &7accounts on &f" + ip + "&7, rejected");
            return kickMany();
        }
        return null;
    }

    public int countNames(String ip) {
        if (ip.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Record record : names.values()) {
            if (record.ip.equals(ip)) {
                count++;
            }
        }
        return count;
    }

    private String kickFirst() {
        return ChatColor.translateAlternateColorCodes('&',
                cfg.s("settings.bot-verification.kick-first-join", "&c请重新进入服务器以完成验证"));
    }

    private String kickMany() {
        return ChatColor.translateAlternateColorCodes('&',
                cfg.s("settings.bot-verification.kick-many-accounts", "&c你已经有大量账号进入服务器，请你先退出再进入"));
    }

    public void load() {
        names.clear();
        if (!file.exists()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays() * 86400_000L;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("names");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String ip = section.getString(key + ".ip", "");
                    long lastJoin = section.getLong(key + ".lastJoin", 0L);
                    if (lastJoin < cutoff) {
                        continue;
                    }
                    names.put(key, new Record(ip, lastJoin));
                }
            }
        } catch (Exception ex) {
            Bukkit.getLogger().severe("YCBR: failed to load botchecks.yml: " + ex.getMessage());
        }
    }

    public void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Record> entry : names.entrySet()) {
                String path = "names." + entry.getKey();
                yaml.set(path + ".ip", entry.getValue().ip);
                yaml.set(path + ".lastJoin", entry.getValue().lastJoin);
            }
            yaml.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().severe("YCBR: failed to save botchecks.yml: " + ex.getMessage());
        }
    }
}
