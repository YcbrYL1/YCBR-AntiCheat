package com.ycbr.anticheat.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.YCBR;
import com.ycbr.anticheat.check.CheckRegistry;
import com.ycbr.anticheat.command.BanCommand;
import com.ycbr.anticheat.command.GuiManager;
import com.ycbr.anticheat.command.YCBRCommand;
import com.ycbr.anticheat.command.YCbrOpCommand;
import com.ycbr.anticheat.data.DataManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.listener.BukkitListener;
import com.ycbr.anticheat.packet.AsyncPacketListener;
import com.ycbr.anticheat.pipeline.MainThreadHandler;
import com.ycbr.anticheat.pipeline.PlayerActor;
import com.ycbr.anticheat.pipeline.Verdict;
import com.ycbr.anticheat.snapshot.EntitySnapshotService;

public final class AntiCheatManager {

    private final YCBR plugin;
    private final YCBRConfig cfg;
    private DataManager dataManager;
    private CheckRegistry registry;
    private MainThreadHandler mainHandler;
    private AsyncPacketListener packetListener;
    private EntitySnapshotService entitySnapshots;
    private ExecutorService executor;
    private BanManager banManager;
    private AuthManager authManager;
    private BotManager botManager;
    private DDosGuard ddosGuard;
    private GhostManager ghostManager;
    private DatasetManager datasetManager;

    public AntiCheatManager(YCBR plugin) {
        this.plugin = plugin;
        this.cfg = new YCBRConfig(plugin);
    }

    public void enable() {
        executor = Executors.newFixedThreadPool(Math.max(1, cfg.checkThreads()), r -> {
            Thread t = new Thread(r, "ycbr-check");
            t.setDaemon(true);
            return t;
        });
        PlayerActor.configure(executor);

        dataManager = new DataManager();
        entitySnapshots = new EntitySnapshotService(this);
        registry = new CheckRegistry(this);
        mainHandler = new MainThreadHandler(this);
        mainHandler.start();

        banManager = new BanManager(plugin);
        banManager.load();

        authManager = new AuthManager(plugin);
        authManager.load();

        botManager = new BotManager(plugin);
        botManager.load();

        ddosGuard = new DDosGuard(plugin);
        ddosGuard.start();

        ghostManager = new GhostManager(this);
        datasetManager = new DatasetManager(this);

        packetListener = new AsyncPacketListener(this);
        packetListener.start();

        new BukkitListener(this).register();
        GuiManager gui = new GuiManager(this);
        gui.register();
        new YCBRCommand(this, gui).register();
        new YCbrOpCommand(this, gui).register();
        new BanCommand(this).register();
        new com.ycbr.anticheat.command.AuthCommand(this).register();

        Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&aYCBR initialized: " + registry.getChecks().size()
                + " checks on " + cfg.checkThreads() + " async thread(s).");
    }

    public void disable() {
        if (packetListener != null) {
            packetListener.stop();
        }
        if (mainHandler != null) {
            mainHandler.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (dataManager != null) {
            dataManager.clear();
        }
        if (banManager != null) {
            banManager.save();
        }
        if (authManager != null) {
            authManager.saveAccounts();
            authManager.saveSessions();
        }
        if (botManager != null) {
            botManager.save();
        }
        if (ddosGuard != null) {
            ddosGuard.stop();
        }
    }

    public void reload() {
        cfg.reload();
        Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&aconfiguration reloaded.");
    }

    public void queueVerdict(Verdict verdict) {
        mainHandler.queue(verdict);
    }

    /**
     * 将玩家 setback 到指定位置（主线程传送）。调用方需传入合法位置，
     * 若位置无效（如全 0 且从未记录）则忽略，避免误传送。
     */
    public void queueSetback(java.util.UUID uuid, double x, double y, double z) {
        if (x == 0.0D && y == 0.0D && z == 0.0D) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(new org.bukkit.Location(p.getWorld(), x, y, z));
            }
        });
    }

    public YCBR getPlugin() {
        return plugin;
    }

    public YCBRConfig config() {
        return cfg;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public CheckRegistry getRegistry() {
        return registry;
    }

    public MainThreadHandler getMainHandler() {
        return mainHandler;
    }

    public EntitySnapshotService getEntitySnapshots() {
        return entitySnapshots;
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public BotManager getBotManager() {
        return botManager;
    }

    public DDosGuard getDdosGuard() {
        return ddosGuard;
    }

    public GhostManager getGhostManager() {
        return ghostManager;
    }

    public DatasetManager getDatasetManager() {
        return datasetManager;
    }

    public boolean isYcbrOp(String name) {
        if (name == null) {
            return false;
        }
        for (String entry : cfg.raw().getStringList("settings.ycbrop-players")) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public void notifyOps(PlayerData data, String message) {
        String name = "?";
        Player target = Bukkit.getPlayer(data.getUuid());
        if (target != null) {
            name = target.getName();
        }
        String text = ChatColor.translateAlternateColorCodes('&', cfg.prefix() + message.replace("%player%", name));
        for (String opName : cfg.raw().getStringList("settings.ycbrop-players")) {
            Player op = Bukkit.getPlayerExact(opName);
            if (op != null && op.isOnline()) {
                op.sendMessage(text);
            }
        }
    }
}