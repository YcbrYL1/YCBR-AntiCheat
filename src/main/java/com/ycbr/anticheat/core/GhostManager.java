package com.ycbr.anticheat.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class GhostManager {

    private static final float DEFAULT_FLY_SPEED = 0.1F;

    private final AntiCheatManager manager;
    private final Map<UUID, GhostState> ghosts = new ConcurrentHashMap<UUID, GhostState>();

    public static final class GhostState {
        public final Location location;
        public final boolean allowFlight;
        public final boolean flying;
        public final float flySpeed;
        public final PotionEffect invisibility;

        public GhostState(Location location, boolean allowFlight, boolean flying, float flySpeed,
                PotionEffect invisibility) {
            this.location = location;
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.flySpeed = flySpeed;
            this.invisibility = invisibility;
        }
    }

    public GhostManager(AntiCheatManager manager) {
        this.manager = manager;
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.containsKey(uuid);
    }

    /**
     * 进入幽灵模式：hidePlayer 使服务器不再向任何客户端发送该玩家的实体包
     * （客户端根本不知道实体存在，ESP/Chams/反隐身/antistaff 全部失效），
     * 再叠加隐形（1.8.9 无 setInvisible API，用无粒子隐形药水实现），
     * 传送至目标身边。保留原游戏模式，可交互。
     */
    public void activate(Player op, Player target) {
        if (!ghosts.containsKey(op.getUniqueId())) {
            PotionEffect existing = null;
            for (PotionEffect effect : op.getActivePotionEffects()) {
                if (effect.getType() == PotionEffectType.INVISIBILITY) {
                    existing = effect;
                    break;
                }
            }
            ghosts.put(op.getUniqueId(), new GhostState(op.getLocation(), op.getAllowFlight(),
                    op.isFlying(), op.getFlySpeed(), existing));
        }
        hideFromAll(op);
        op.removePotionEffect(PotionEffectType.INVISIBILITY);
        op.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, true));
        op.setAllowFlight(true);
        op.setFlying(true);
        op.setFlySpeed(0.4F);
        op.teleport(target.getLocation());
        op.sendMessage(manager.config().prefix() + "&a\u5df2\u4f20\u9001\u81f3 &f" + target.getName()
                + " &a(\u9690\u8eab\u6a21\u5f0f)\uff0c\u518d\u6b21\u70b9\u51fb\u53ef\u5207\u6362\u4f4d\u7f6e\uff0c"
                + "\u9000\u51fa\u8bf7\u70b9\u51fb \u9000\u51fa\u9690\u8eab");
    }

    public void deactivate(Player op) {
        GhostState state = ghosts.remove(op.getUniqueId());
        if (state == null) {
            return;
        }
        restoreVisuals(op, state);
        op.setAllowFlight(state.allowFlight);
        op.setFlying(state.flying);
        op.setFlySpeed(state.flySpeed);
        op.teleport(state.location);
        op.sendMessage(manager.config().prefix() + "&a\u5df2\u9000\u51fa\u9690\u8eab\u6a21\u5f0f\u3002");
    }

    public void onJoin(Player joiner) {
        for (UUID uuid : ghosts.keySet()) {
            Player ghost = Bukkit.getPlayer(uuid);
            if (ghost != null && !ghost.equals(joiner)) {
                joiner.hidePlayer(ghost);
            }
        }
    }

    public void onQuit(Player op) {
        GhostState state = ghosts.remove(op.getUniqueId());
        if (state == null) {
            return;
        }
        showToAll(op);
        restoreVisuals(op, state);
        op.setAllowFlight(state.allowFlight);
        op.setFlying(state.flying);
        op.setFlySpeed(state.flySpeed);
    }

    private static void restoreVisuals(Player op, GhostState state) {
        showToAll(op);
        op.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (state.invisibility != null) {
            op.addPotionEffect(state.invisibility);
        }
    }

    private static void hideFromAll(Player op) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(op)) {
                p.hidePlayer(op);
            }
        }
    }

    private static void showToAll(Player op) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(op)) {
                p.showPlayer(op);
            }
        }
    }
}