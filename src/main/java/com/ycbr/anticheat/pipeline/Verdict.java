package com.ycbr.anticheat.pipeline;

import java.util.UUID;

import com.ycbr.anticheat.check.CheckType;

public final class Verdict {

    public final UUID uuid;
    public final CheckType type;
    public final String sub;
    public final String info;

    public Verdict(UUID uuid, CheckType type, String sub, String info) {
        this.uuid = uuid;
        this.type = type;
        this.sub = sub;
        this.info = info;
    }
}