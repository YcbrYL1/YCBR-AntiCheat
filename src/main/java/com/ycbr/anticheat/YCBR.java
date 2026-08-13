package com.ycbr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.ycbr.anticheat.core.AntiCheatManager;

public final class YCBR extends JavaPlugin {

    private AntiCheatManager manager;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found, YCBR disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        manager = new AntiCheatManager(this);
        manager.enable();
        getLogger().info("YCBR AntiCheat v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.disable();
        }
        getLogger().info("YCBR AntiCheat disabled.");
    }

    public AntiCheatManager getAntiCheatManager() {
        return manager;
    }
}