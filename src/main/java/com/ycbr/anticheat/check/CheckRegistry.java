package com.ycbr.anticheat.check;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.ycbr.anticheat.check.combat.AutoToolCheck;
import com.ycbr.anticheat.check.combat.BowCheck;
import com.ycbr.anticheat.check.combat.CriticalsCheck;
import com.ycbr.anticheat.check.combat.FastClickCheck;
import com.ycbr.anticheat.check.combat.KillAuraCheck;
import com.ycbr.anticheat.check.combat.ReachCheck;
import com.ycbr.anticheat.check.combat.ScaffoldCheck;
import com.ycbr.anticheat.check.combat.aim.AimStatisticsCheck;
import com.ycbr.anticheat.check.movement.FlyCheck;
import com.ycbr.anticheat.check.movement.NoFallCheck;
import com.ycbr.anticheat.check.movement.NoSlowCheck;
import com.ycbr.anticheat.check.movement.SimulationCheck;
import com.ycbr.anticheat.check.movement.SpeedCheck;
import com.ycbr.anticheat.check.movement.VelocityCheck;
import com.ycbr.anticheat.check.protocol.BlinkCheck;
import com.ycbr.anticheat.check.protocol.FastThrowCheck;
import com.ycbr.anticheat.check.protocol.ProtocolCheck;
import com.ycbr.anticheat.check.protocol.SprintCheck;
import com.ycbr.anticheat.check.protocol.TimerCheck;
import com.ycbr.anticheat.check.protocol.WrongTurnCheck;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.data.context.PlaceContext;
import com.ycbr.anticheat.pipeline.MainThreadHandler;

public final class CheckRegistry {

    private final List<Check> checks = new ArrayList<Check>();
    private final Map<CheckType, Check> byType = new EnumMap<CheckType, Check>(CheckType.class);
    private final WrongTurnCheck wrongTurn;
    private final SprintCheck sprint;
    private final BlinkCheck blink;
    private final KillAuraCheck killAura;
    private final ProtocolCheck protocol;
    private final AutoToolCheck autoTool;
    private final AimStatisticsCheck aimStat;
    private final AntiCheatManager manager;

    public CheckRegistry(AntiCheatManager manager) {
        this.manager = manager;
        wrongTurn = new WrongTurnCheck(manager);
        sprint = new SprintCheck(manager);
        blink = new BlinkCheck(manager);
        killAura = new KillAuraCheck(manager);
        protocol = new ProtocolCheck(manager);
        autoTool = new AutoToolCheck(manager);
        aimStat = new AimStatisticsCheck(manager);
        add(killAura);
        add(new ScaffoldCheck(manager));
        add(new SpeedCheck(manager));
        add(new VelocityCheck(manager));
        add(new FlyCheck(manager));
        add(new CriticalsCheck(manager));
        add(new TimerCheck(manager));
        add(new NoFallCheck(manager));
        add(new NoSlowCheck(manager));
        add(autoTool);
        add(new FastThrowCheck(manager));
        add(new BowCheck(manager));
        add(new FastClickCheck(manager));
        add(new ReachCheck(manager));
        add(new SimulationCheck(manager));
        add(aimStat);
        add(protocol);
        add(wrongTurn);
        add(blink);
        add(sprint);
    }

    private void add(Check check) {
        checks.add(check);
        byType.put(check.getType(), check);
    }

    public List<Check> getChecks() {
        return checks;
    }

    public Check get(CheckType type) {
        return byType.get(type);
    }

    public void onMove(MoveContext ctx) {
        if (ctx.data.op) {
            return;
        }
        ctx.data.velocity.tickAge();
        for (Check check : checks) {
            check.onMove(ctx);
        }
    }

    public void onAttack(AttackContext ctx) {
        if (ctx.data.op) {
            return;
        }
        if (System.currentTimeMillis() < ctx.data.attackBlockedUntil) {
            return; // 攻击阻断（软惩罚）：不派发战斗检测
        }
        for (Check check : checks) {
            check.onAttack(ctx);
        }
    }

    public void onPlace(PlaceContext ctx) {
        if (ctx.data.op) {
            return;
        }
        for (Check check : checks) {
            check.onPlace(ctx);
        }
    }

    public void onClientCommand(PlayerData data, int action) {
        if (data.op) {
            return;
        }
        for (Check check : checks) {
            check.onClientCommand(data, action);
        }
    }

    public void onRotation(PlayerData data, float yaw, float pitch) {
        if (data.op) {
            return;
        }
        data.pushRotation(yaw, pitch, manager.getMainHandler().currentServerTick());
        wrongTurn.checkRotation(data, yaw, pitch);
        killAura.checkPendingAngles(data, yaw, pitch);
        aimStat.onRotation(data, yaw, pitch);
    }

    public void onLook(PlayerData data, boolean onGround) {
        if (data.op) {
            return;
        }
        for (Check check : checks) {
            check.onLook(data, onGround);
        }
    }

    public void onBlockDigStart(PlayerData data) {
        if (data.op) {
            return;
        }
        for (Check check : checks) {
            check.onBlockDigStart(data);
        }
    }

    public void onThrow(PlayerData data, long now) {
        if (data.op) {
            return;
        }
        for (Check check : checks) {
            check.onThrow(data, now);
        }
    }

    public void onBowRelease(PlayerData data, float force, long now) {
        if (data.op) {
            return;
        }
        for (Check check : checks) {
            check.onBowRelease(data, force, now);
        }
    }

    public void onHeldItemSlot(PlayerData data, int slot) {
        if (data.op) {
            return;
        }
        protocol.onHeldItemSlot(data, slot);
        autoTool.onSlotChange(data);
    }

    public void onKeepAlive(PlayerData data, long id) {
        if (data.op) {
            return;
        }
        protocol.onKeepAlive(data, id);
    }

    public void onSteerVehicle(PlayerData data, float forward, float sideways, boolean jump, boolean unmount) {
        if (data.op) {
            return;
        }
        protocol.onSteerVehicle(data, forward, sideways, jump, unmount);
    }

    public void onRidingJump(PlayerData data, long now) {
        if (data.op) {
            return;
        }
        protocol.onRidingJump(data, now);
    }

    public void onDigFace(PlayerData data, int status, int face) {
        if (data.op) {
            return;
        }
        protocol.onDigFace(data, status, face);
    }

    public void onBadPacket(PlayerData data, double x, double y, double z) {
        if (data.op) {
            return;
        }
        protocol.reportInvalidPosition(data,
                Double.toString(x), Double.toString(y), Double.toString(z));
    }

    public void onSprintAction(PlayerData data, int action, int blockedStates) {
        if (data.op) {
            return;
        }
        sprint.checkAction(data, action, blockedStates);
    }

    public void onMainTick(PlayerData data, long now) {
        if (data.op) {
            return;
        }
        blink.onTick(data, now);
    }
}
