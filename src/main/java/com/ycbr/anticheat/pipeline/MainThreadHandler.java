package com.ycbr.anticheat.pipeline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.ycbr.anticheat.simulation.WorldProbe;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.YCBRConfig;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.util.NmsUtil;

public final class MainThreadHandler implements Runnable {

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;
    private final Queue<Verdict> queue = new ConcurrentLinkedQueue<Verdict>();
    private final Set<UUID> alerts = new HashSet<UUID>();

    private int taskId = -1;
    private int sweepTaskId = -1;
    private volatile int tickCounter;
    private int decayTickCounter;
    private long lastTickNanos;
    private volatile double tps = 20.0D;

    private static final int FUSE_WINDOW_TICKS = 300;
    private final int[] fusePerTick = new int[FUSE_WINDOW_TICKS];
    private int fuseIndex;
    private long fuseWindowSum;
    private final Set<UUID> fusePlayers = new HashSet<UUID>();
    private int fusePlayerTick;
    private volatile long fusedUntil;
    private boolean wasFused;

    private static final int LOG_CAPACITY = 200;
    private final List<String> violationLog = new ArrayList<String>();
    private final SimpleDateFormat logFormat = new SimpleDateFormat("HH:mm:ss");

    public MainThreadHandler(AntiCheatManager manager) {
        this.manager = manager;
        this.cfg = manager.config();
    }

    public double getTps() {
        return tps;
    }

    /** 当前服务器 tick 计数（主线程维护，volatile 供异步检测读取）。 */
    public int currentServerTick() {
        return tickCounter;
    }

    public boolean isFused() {
        return System.currentTimeMillis() < fusedUntil;
    }

    public void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(manager.getPlugin(), this, 0L, cfg.tickInterval()).getTaskId();
        sweepTaskId = Bukkit.getScheduler().runTaskTimer(manager.getPlugin(), this::sweep,
                20L * 60L * cfg.dataSweepMinutes(), 20L * 60L * cfg.dataSweepMinutes()).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        if (sweepTaskId != -1) {
            Bukkit.getScheduler().cancelTask(sweepTaskId);
        }
    }

    public void queue(Verdict verdict) {
        queue.add(verdict);
    }

    public boolean toggleAlert(UUID uuid) {
        if (!alerts.remove(uuid)) {
            alerts.add(uuid);
            return true;
        }
        return false;
    }

    public boolean hasAlert(UUID uuid) {
        return alerts.contains(uuid);
    }

    public void addAlert(UUID uuid) {
        alerts.add(uuid);
    }

    public void removeAlert(UUID uuid) {
        alerts.remove(uuid);
    }

    @Override
    public void run() {
        long nowNanos = System.nanoTime();
        if (lastTickNanos != 0L) {
            double elapsedMs = (nowNanos - lastTickNanos) / 1000000.0D;
            double instant = Math.min(20.0D, 1000.0D / Math.max(elapsedMs, 1.0D));
            tps = tps * 0.95D + 0.05D * instant;
        }
        lastTickNanos = nowNanos;

        long now = System.currentTimeMillis();
        tickCounter++;
        fuseIndex = tickCounter % FUSE_WINDOW_TICKS;
        fuseWindowSum -= fusePerTick[fuseIndex];
        fusePerTick[fuseIndex] = 0;

        for (PlayerData data : manager.getDataManager().all()) {
            manager.getRegistry().onMainTick(data, now);
        }

        if (tickCounter % cfg.playerSnapshotInterval() == 0) {
            snapshotPlayers();
        }
        if (tickCounter % 20 == 0) {
            checkFuse(now);
        }
        if (++fusePlayerTick >= 100) {
            fusePlayerTick = 0;
            fusePlayers.clear();
        }
        int decaySeconds = cfg.i("settings.violation-decay-seconds", 60);
        if (decaySeconds > 0 && ++decayTickCounter >= 20 * decaySeconds) {
            decayTickCounter = 0;
            decayViolations();
        }
        manager.getEntitySnapshots().tick();

        Verdict verdict;
        while ((verdict = queue.poll()) != null) {
            handle(verdict);
        }
    }

    private void checkFuse(long now) {
        if (!cfg.raw().getBoolean("settings.improbable.enabled", true)) {
            return;
        }
        int threshold = fuseThreshold();
        if (now < fusedUntil) {
            if (fuseWindowSum < threshold / 2) {
                fusedUntil = 0L;
            }
            return;
        }
        if (fuseWindowSum >= threshold
                && fusePlayers.size() >= cfg.i("settings.improbable.min-players", 3)) {
            long triggeredSum = fuseWindowSum;
            int triggeredPlayers = fusePlayers.size();
            fusedUntil = now + cfg.i("settings.improbable.fuse-seconds", 60) * 1000L;
            fuseWindowSum = 0L;
            fusePlayers.clear();
            wasFused = true;
            Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&cImprobable fuse activated: " + triggeredSum
                    + " violations by " + triggeredPlayers + " players in "
                    + cfg.i("settings.improbable.window-seconds", 15) + "s (threshold " + threshold
                    + "). Kick actions muted " + cfg.i("settings.improbable.fuse-seconds", 60) + "s.");
        } else if (wasFused) {
            wasFused = false;
            resetAllViolations();
            Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&afuse cleared, all violation levels reset.");
        }
    }

    private int fuseThreshold() {
        int online = Bukkit.getOnlinePlayers().size();
        int perPlayer = cfg.i("settings.improbable.threshold-per-player", 3);
        int min = cfg.i("settings.improbable.min-threshold", 12);
        return Math.max(min, online * perPlayer);
    }

    private void resetAllViolations() {
        for (PlayerData data : manager.getDataManager().all()) {
            for (CheckType type : CheckType.values()) {
                if (data.getViolations(type) > 0L) {
                    data.setViolations(type, 0L);
                }
            }
            data.buffers.clear();
        }
    }

    private void decayViolations() {
        long now = System.currentTimeMillis();
        for (PlayerData data : manager.getDataManager().all()) {
            if (now - data.lastActive > 20L * 60L * 1000L) {
                continue;
            }
            for (CheckType type : CheckType.values()) {
                long vl = data.getViolations(type);
                if (vl > 0) {
                    data.setViolations(type, Math.max(0L, vl - Math.max(1L, vl / 20L)));
                }
            }
        }
    }

    private void snapshotPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = manager.getDataManager().get(player.getUniqueId());
            data.ping = NmsUtil.getPing(player);
            data.speedLevel = potionLevel(player, PotionEffectType.SPEED);
            data.jumpLevel = potionLevel(player, PotionEffectType.JUMP);
            data.creative = player.getGameMode() == org.bukkit.GameMode.CREATIVE;
            data.op = player.isOp() || manager.isYcbrOp(player.getName());
            data.flying = player.isFlying();
            boolean inVehicle = player.isInsideVehicle();
            if (inVehicle) {
                data.lastVehicleTime = System.currentTimeMillis();
            }
            data.inVehicle = inVehicle;
            data.dead = player.isDead();
            snapshotBlockContext(player, data);
        }
    }

    private void snapshotBlockContext(Player player, PlayerData data) {
        double px = data.movement.lastX;
        double py = data.movement.lastY;
        double pz = data.movement.lastZ;
        Block feet = player.getWorld().getBlockAt((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
        Block below = feet.getRelative(BlockFace.DOWN);
        Material belowMat = below.getType();
        Material feetMat = feet.getType();
        data.blockOnIce = belowMat == Material.ICE || belowMat == Material.PACKED_ICE;
        data.blockOnSlime = belowMat == Material.SLIME_BLOCK;
        data.blockOnSoulSand = belowMat == Material.SOUL_SAND;
        data.blockNearLiquid = liquid(belowMat) || liquid(feetMat);
        Block above = feet.getRelative(BlockFace.UP);
        data.blockInWeb = feetMat == Material.WEB || above.getType() == Material.WEB;
        data.blockOnLadder = feetMat == Material.LADDER || feetMat == Material.VINE
                || belowMat == Material.LADDER || belowMat == Material.VINE;
        data.blockBelowUnstandable = unstandable(belowMat);
        data.blockOnStairsOrSlab = WorldProbe.isStepMaterial(belowMat) || WorldProbe.isStepMaterial(feetMat);
        data.blockOnPiston = feetMat == Material.PISTON_MOVING_PIECE || belowMat == Material.PISTON_MOVING_PIECE;
        Block top = feet.getRelative(BlockFace.UP, 2);
        data.blockBoxedIn = belowMat.isSolid() && top.getType().isSolid() && !data.blockOnSlime;
    }

    private boolean unstandable(Material material) {
        switch (material) {
        case AIR:
        case WATER:
        case STATIONARY_WATER:
        case LAVA:
        case STATIONARY_LAVA:
            return true;
        default:
            return false;
        }
    }

    private boolean liquid(Material material) {
        return material == Material.WATER || material == Material.STATIONARY_WATER || material == Material.LAVA
                || material == Material.STATIONARY_LAVA;
    }

    private int potionLevel(Player player, PotionEffectType type) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(type)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 0;
    }

    private void handle(Verdict verdict) {
        fusePerTick[fuseIndex]++;
        fuseWindowSum++;
        fusePlayers.add(verdict.uuid);
        Player player = Bukkit.getPlayer(verdict.uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerData data = manager.getDataManager().get(verdict.uuid);
        long now = System.currentTimeMillis();
        long vl = data.getViolations(verdict.type);
        logViolation(now, player.getName(), verdict, vl);

        int notifyEvery = cfg.notifyEvery();
        if (notifyEvery <= 0 || vl % notifyEvery == 0) {
            sendAlerts(player, verdict, vl);
        }

        if (now < fusedUntil) {
            return;
        }

        int kickAt = cfg.i("checks." + verdict.type.getConfigPath() + ".kick-at-vl", 20);
        if (vl >= kickAt) {
            data.resetViolations(verdict.type);
            if (data.op) {
                return;
            }
            long expiry = manager.getBanManager().claim(player.getUniqueId(), player.getName());
            String message = cfg.s("settings.ban.kick-message",
                    "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
            player.kickPlayer(manager.getBanManager().applyPlaceholders(message, expiry));
            Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "&c" + player.getName()
                    + " banned by " + verdict.type.getDisplay() + ", expires "
                    + com.ycbr.anticheat.core.BanManager.formatExpiry(expiry) + " (Beijing time)");
        }
    }

    private void sendAlerts(Player failed, Verdict verdict, long vl) {
        String message = cfg.prefix() + failed.getName() + " failed " + verdict.type.getDisplay() + " ("
                + verdict.sub + ") " + verdict.info + " &8[vl=" + vl + "]";
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        for (UUID uuid : alerts) {
            Player viewer = Bukkit.getPlayer(uuid);
            if (viewer != null && viewer.isOnline() && !viewer.getUniqueId().equals(failed.getUniqueId())) {
                viewer.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    private void logViolation(long now, String name, Verdict verdict, long vl) {
        String line = logFormat.format(now) + " " + name + " " + verdict.type.getDisplay() + " ("
                + verdict.sub + ") " + verdict.info + " vl=" + vl;
        violationLog.add(line);
        while (violationLog.size() > LOG_CAPACITY) {
            violationLog.remove(0);
        }
    }

    public List<String> getViolationLog() {
        List<String> copy = new ArrayList<String>(violationLog);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private void sweep() {
        manager.getDataManager().sweep(cfg.dataSweepMinutes() * 60 * 1000);
    }
}