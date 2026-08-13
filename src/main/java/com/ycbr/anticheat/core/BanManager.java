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