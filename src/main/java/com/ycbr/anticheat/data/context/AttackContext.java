package com.ycbr.anticheat.data.context;

import com.ycbr.anticheat.data.PlayerData;

public final class AttackContext {

    public final PlayerData data;
    public final int targetId;
    public final long time;
    public final int playerEntityId;

    public AttackContext(PlayerData data, int targetId, long time, int playerEntityId) {
        this.data = data;
        this.targetId = targetId;
        this.time = time;
        this.playerEntityId = playerEntityId;
    }
}