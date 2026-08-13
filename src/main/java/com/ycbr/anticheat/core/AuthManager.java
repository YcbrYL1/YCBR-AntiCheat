package com.ycbr.anticheat.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import com.ycbr.anticheat.YCBR;

public final class AuthManager {

    private final YCBR plugin;
    private final YCBRConfig cfg;
    private final Map<String, Account> accounts = new ConcurrentHashMap<String, Account>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final File accountFile;
    private final File sessionFile;

    public static final class Account {
        public final String salt;
        public final String hash;
        public final long registeredAt;

        public Account(String salt, String hash, long registeredAt) {
            this.salt = salt;
            this.hash = hash;
            this.registeredAt = registeredAt;
        }
    }

    public static final class Session {
        public final String ip;
        public final long lastLogin;

        public Session(String ip, long lastLogin) {
            this.ip = ip;
            this.lastLogin = lastLogin;
        }
    }

    public AuthManager(YCBR plugin) {
        this.plugin = plugin;
        this.cfg = new YCBRConfig(plugin);
        this.accountFile = new File(plugin.getDataFolder(), "accounts.yml");
        this.sessionFile = new File(plugin.getDataFolder(), "sessions.yml");
    }

    public boolean enabled() {
        return cfg.raw().getBoolean("settings.auth-enabled", true);
    }

    public long sessionHours() {
        return cfg.raw().getLong("settings.auth-session-hours", 24);
    }

    public boolean isPremium(String name) {
        for (String entry : cfg.raw().getStringList("settings.auth-premium-players")) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRegistered(String name) {
        return accounts.containsKey(key(name));
    }

    private static String key(String name) {
        return name.toLowerCase();
    }

    public boolean register(String name, String password) {
        String k = key(name);
        if (accounts.containsKey(k)) {
            return false;
        }
        String salt = UUID.randomUUID().toString().replace("-", "");
        accounts.put(k, new Account(salt, hash(salt, name, password), System.currentTimeMillis()));
        saveAccounts();
        return true;
    }

    public boolean verify(String name, String password) {
        Account account = accounts.get(key(name));
        if (account == null) {
            return false;
        }
        return constantTimeEquals(account.hash, hash(account.salt, name, password));
    }

    public boolean hasValidSession(String name, String ip) {
        Session session = sessions.get(key(name));
        if (session == null || ip.isEmpty()) {
            return false;
        }
        if (!session.ip.equals(ip)) {
            return false;
        }
        return System.currentTimeMillis() - session.lastLogin < sessionHours() * 3600_000L;
    }

    public void recordSession(String name, String ip) {
        if (ip.isEmpty()) {
            return;
        }
        sessions.put(key(name), new Session(ip, System.currentTimeMillis()));
        saveSessions();
    }

    private static String hash(String salt, String name, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((name + ":" + salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public void load() {
        accounts.clear();
        sessions.clear();
        if (accountFile.exists()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(accountFile);
                ConfigurationSection section = yaml.getConfigurationSection("accounts");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        accounts.put(key, new Account(section.getString(key + ".salt", ""),
                                section.getString(key + ".hash", ""), section.getLong(key + ".registeredAt", 0L)));
                    }
                }
            } catch (Exception ex) {
                Bukkit.getLogger().severe("YCBR: failed to load accounts.yml: " + ex.getMessage());
            }
        }
        if (sessionFile.exists()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);
                ConfigurationSection section = yaml.getConfigurationSection("sessions");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        sessions.put(key, new Session(section.getString(key + ".ip", ""),
                                section.getLong(key + ".lastLogin", 0L)));
                    }
                }
            } catch (Exception ex) {
                Bukkit.getLogger().severe("YCBR: failed to load sessions.yml: " + ex.getMessage());
            }
        }
    }

    public void saveAccounts() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Account> entry : accounts.entrySet()) {
                String path = "accounts." + entry.getKey();
                yaml.set(path + ".salt", entry.getValue().salt);
                yaml.set(path + ".hash", entry.getValue().hash);
                yaml.set(path + ".registeredAt", entry.getValue().registeredAt);
            }
            yaml.save(accountFile);
        } catch (IOException ex) {
            Bukkit.getLogger().severe("YCBR: failed to save accounts.yml: " + ex.getMessage());
        }
    }

    public void saveSessions() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                String path = "sessions." + entry.getKey();
                yaml.set(path + ".ip", entry.getValue().ip);
                yaml.set(path + ".lastLogin", entry.getValue().lastLogin);
            }
            yaml.save(sessionFile);
        } catch (IOException ex) {
            Bukkit.getLogger().severe("YCBR: failed to save sessions.yml: " + ex.getMessage());
        }
    }
}
