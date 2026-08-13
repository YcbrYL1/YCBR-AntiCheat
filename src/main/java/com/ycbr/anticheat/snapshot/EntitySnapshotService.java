package com.ycbr.anticheat.snapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.util.NmsUtil;

public final class EntitySnapshotService {

    private final AntiCheatManager manager;
    private final Map<Integer, EntitySnapshot> snapshots = new ConcurrentHashMap<Integer, EntitySnapshot>();
    private int tickCounter;

    public EntitySnapshotService(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void tick() {
        if (++tickCounter < manager.config().entitySnapshotInterval()) {
            return;
        }
        tickCounter = 0;

        Map<Integer, EntitySnapshot> fresh = new ConcurrentHashMap<Integer, EntitySnapshot>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity)) {
                    continue;
                }
                Location loc = entity.getLocation();
                EntitySnapshot old = snapshots.get(entity.getEntityId());
                double vx = 0D;
                double vy = 0D;
                double vz = 0D;
                long now = System.currentTimeMillis();
                if (old != null && now > old.createdMillis) {
                    double perTick = 50.0D / (now - old.createdMillis);
                    vx = (loc.getX() - old.x) * perTick;
                    vy = (loc.getY() - old.y) * perTick;
                    vz = (loc.getZ() - old.z) * perTick;
                }
                fresh.put(entity.getEntityId(), new EntitySnapshot(entity.getEntityId(),
                        entity instanceof Player ? entity.getUniqueId() : null, entity.getType().name(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        NmsUtil.getWidth(entity), NmsUtil.getHeight(entity), now,
                        vx, vy, vz));
            }
        }
        snapshots.clear();
        snapshots.putAll(fresh);
    }

    public EntitySnapshot get(int entityId) {
        return snapshots.get(entityId);
    }
}