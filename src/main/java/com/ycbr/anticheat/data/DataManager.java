package com.ycbr.anticheat.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<UUID, PlayerData>();

    public PlayerData get(UUID uuid) {
        return data.computeIfAbsent(uuid, PlayerData::new);
    }

    public void remove(UUID uuid) {
        data.remove(uuid);
    }

    public Iterable<PlayerData> all() {
        return data.values();
    }

    public void sweep(int maxAgeMillis) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
            if (now - entry.getValue().lastActive > maxAgeMillis) {
                data.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public void clear() {
        data.clear();
    }
}