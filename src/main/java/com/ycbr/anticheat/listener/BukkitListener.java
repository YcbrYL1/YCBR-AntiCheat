package com.ycbr.anticheat.listener;

import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;

public final class BukkitListener implements Listener {

    private final AntiCheatManager manager;

    public BukkitListener(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void register() {
        manager.getPlugin().getServer().getPluginManager().registerEvents(this, manager.getPlugin());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        com.ycbr.anticheat.core.DDosGuard guard = manager.getDdosGuard();
        if (guard != null && guard.enabled()) {
            String name = event.getName();
            if (name != null && name.length() > guard.maxUsername()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                manager.config().s("settings.ddos.kick-invalid", "&c连接参数异常")));
                return;
            }
            if (guard.isRateBlocked(name)) {
                guard.countRateBlock();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                manager.config().s("settings.ddos.kick-rate-limit",
                                        "&c登录尝试过于频繁，请等待一分钟后再试")));
                return;
            }
        }
        com.ycbr.anticheat.core.BanManager.BanRecord record =
                manager.getBanManager().get(event.getUniqueId());
        if (record == null) {
            return;
        }
        if (manager.getBanManager().isBanned(event.getUniqueId())) {
            String message = manager.config().s("settings.ban.login-denied-message",
                    "&c你已被 YCBR 反作弊封禁，剩余 %remaining%，到期时间：%time%（北京时间）");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    manager.getBanManager().applyPlaceholders(message, record.expiry));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String botKick = manager.getBotManager().check(player);
        if (botKick != null) {
            player.kickPlayer(botKick);
            return;
        }
        PlayerData data = manager.getDataManager().get(player.getUniqueId());
        manager.getGhostManager().onJoin(player);
        data.joinedMillis = System.currentTimeMillis();
        data.shadow.reset(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
        data.shadow.lastSyncTime = System.currentTimeMillis();
        com.ycbr.anticheat.core.AuthManager auth = manager.getAuthManager();
        boolean ok = !auth.enabled() || auth.isPremium(player.getName()) || manager.isYcbrOp(player.getName())
                || player.isOp();
        if (!ok) {
            String ip = player.getAddress() == null ? ""
                    : player.getAddress().getAddress().getHostAddress();
            ok = auth.hasValidSession(player.getName(), ip);
        }
        data.authenticated = ok;
        if (!ok) {
            player.sendMessage(manager.config().prefix() + "&c您尚未登录");
            if (auth.isRegistered(player.getName())) {
                player.sendMessage(manager.config().prefix() + "&c请使用 &e/login [密码] &c登录");
            } else {
                player.sendMessage(manager.config().prefix() + "&c请使用 &e/register [密码] [密码] &c注册");
            }
        }
        if (player.hasPermission("ycbr.alerts")) {
            manager.getMainHandler().addAlert(player.getUniqueId());
        }
        manager.getBanManager().isBanned(player.getUniqueId()); // 利用其惰性删除过期记录并落盘
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        PlayerData data = manager.getDataManager().get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.shadow.reset(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());
            data.shadow.lastSyncTime = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PlayerData data = manager.getDataManager().get(player.getUniqueId());
        if (data != null && !data.authenticated && manager.getAuthManager().enabled()) {
            event.setCancelled(true);
            player.sendMessage(manager.config().prefix() + "&c请先登录后再发言");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        PlayerData data = manager.getDataManager().get(event.getEntity().getUniqueId());
        if (data != null) {
            data.lastFallDamageTime = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player)) {
            return;
        }
        if (!(event.getEntity() instanceof Snowball)
                && !(event.getEntity() instanceof Egg)
                && !(event.getEntity() instanceof EnderPearl)) {
            return;
        }
        PlayerData data = manager.getDataManager().get(((Player) event.getEntity().getShooter()).getUniqueId());
        if (data != null) {
            manager.getRegistry().onThrow(data, System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBowPull(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getItemInHand() == null || player.getItemInHand().getType() != Material.BOW) {
            return;
        }
        PlayerData data = manager.getDataManager().get(player.getUniqueId());
        if (data != null) {
            data.bowPullTime = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Arrow)) {
            return;
        }
        Arrow arrow = (Arrow) event.getEntity();
        if (!(arrow.getShooter() instanceof Player)) {
            return;
        }
        PlayerData data = manager.getDataManager().get(((Player) arrow.getShooter()).getUniqueId());
        if (data != null) {
            manager.getRegistry().onBowRelease(data, (float) event.getForce(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer());
    }

    private void cleanup(Player player) {
        manager.getMainHandler().removeAlert(player.getUniqueId());
        manager.getGhostManager().onQuit(player);
        manager.getDataManager().remove(player.getUniqueId());
    }
}