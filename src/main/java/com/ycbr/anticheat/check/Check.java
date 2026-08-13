package com.ycbr.anticheat.check;

import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.YCBRConfig;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.data.context.PlaceContext;
import com.ycbr.anticheat.pipeline.Verdict;

public abstract class Check {

    protected final CheckType type;
    protected final AntiCheatManager manager;
    protected final YCBRConfig cfg;

    public Check(CheckType type, AntiCheatManager manager) {
        this.type = type;
        this.manager = manager;
        this.cfg = manager.config();
    }

    public CheckType getType() {
        return type;
    }

    public boolean isEnabled() {
        return cfg.enabled(type.getConfigPath());
    }

    protected final boolean isSubEnabled(String sub) {
        return cfg.raw().getBoolean("checks." + type.getConfigPath() + "." + sub + ".enabled", true);
    }

    protected final boolean isStrict() {
        return cfg.raw().getBoolean("settings.strict-mode", false);
    }

    protected final double sd(String sub, double normal, double strict) {
        return isStrict() ? strict : d(sub, normal);
    }

    protected final int si(String sub, int normal, int strict) {
        return isStrict() ? strict : i(sub, normal);
    }

    protected void onMove(MoveContext ctx) {
    }

    protected void onAttack(AttackContext ctx) {
    }

    protected void onPlace(PlaceContext ctx) {
    }

    protected void onClientCommand(PlayerData data, int action) {
    }

    protected void onLook(PlayerData data, boolean onGround) {
    }

    protected void onBlockDigStart(PlayerData data) {
    }

    protected void onThrow(PlayerData data, long now) {
    }

    protected void onBowRelease(PlayerData data, float force, long now) {
    }

    protected final boolean flag(PlayerData data, String sub, String info) {
        if (isStrict()) {
            data.timeTest++;
            long now = System.currentTimeMillis();
            if (data.timeTest >= 50 && now - data.timeTestNotifyAt > 30000L) {
                data.timeTestNotifyAt = now;
                manager.notifyOps(data, "&e%player% &7可能作弊 (timeTest=" + data.timeTest
                        + ")，&f/ycbrop gui &7\u4eba\u5de5\u68c0\u6d4b");
            }
            return true;
        }
        long now = System.currentTimeMillis();
        long last = data.getLastFlagTime(type);
        long window = cfg.i("settings.vl-reset-window-ms", 60000);
        if (last > 0L && now - last > window) {
            data.resetViolations(type);
        }
        data.setLastFlagTime(type, now);
        data.addViolation(type);
        manager.queueVerdict(new Verdict(data.getUuid(), type, sub, info));
        return true;
    }

    protected final boolean bump(PlayerData data, String sub, double amount, double threshold) {
        if (isStrict()) {
            return true;
        }
        String key = key(sub);
        double value = data.buffers.getOrDefault(key, 0D) + amount;
        if (value >= threshold) {
            data.buffers.put(key, 0D);
            return true;
        }
        data.buffers.put(key, value);
        return false;
    }

    protected final void drain(PlayerData data, String sub, double amount) {
        String key = key(sub);
        double value = data.buffers.getOrDefault(key, 0D);
        data.buffers.put(key, Math.max(0D, value - amount));
    }

    private String key(String sub) {
        return type.getConfigPath() + "." + sub;
    }

    protected final double d(String sub, double def) {
        return cfg.d("checks." + type.getConfigPath() + "." + sub, def);
    }

    protected final int i(String sub, int def) {
        return cfg.i("checks." + type.getConfigPath() + "." + sub, def);
    }
}