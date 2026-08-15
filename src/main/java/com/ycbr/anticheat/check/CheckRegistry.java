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
import com.ycbr.anticheat.snapshot.EntitySnapshot;

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

    /** 监听线程同步预检：Reach 判定超距且射线未命中 → 建议取消本次攻击。 */
    public boolean cancelImpossibleAttack(PlayerData data, int targetId) {
        if (data.op || data.creative) {
            return false;
        }
        ReachCheck reach = (ReachCheck) byType.get(CheckType.REACH);
        if (reach == null || !reach.isEnabled()) {
            return false;
        }
        EntitySnapshot target = manager.getEntitySnapshots().get(targetId);
        if (target == null) {
            return false;
        }
        double maxReach = manager.config().raw().getDouble("checks.reach.max-reach", 3.1D);
        double leniency = manager.config().raw().getDouble("checks.reach.leniency", 0.03D);
        int window = manager.config().raw().getInt("checks.reach.multi-frame.window-ticks", 2);
        double expand = manager.config().raw().getDouble("checks.reach.multi-frame.expand", 0.05D);
        boolean cancelEnabled = manager.config().raw().getBoolean("checks.reach.cancel-impossible", true);
        return cancelEnabled && ReachCheck.shouldCancelAttack(data, target, maxReach, leniency, window,
                manager.getMainHandler().currentServerTick(), expand);
    }

    /**
     * 监听线程同步预检：战斗硬检测（SelfInteract / MultiInteract 突发取消）。
     * <ul>
     *   <li>MultiInteract 命中后开出的突发取消窗口内 → 取消攻击包（Grim cancelBuffer 语义）；</li>
     *   <li>攻击自己（KA 经典痕迹）→ 取消攻击包，并在 actor 线程即时 flag + 攻击阻断。</li>
     * </ul>
     */
    public boolean cancelHardCombatAttack(PlayerData data, int targetId, int playerEntityId) {
        if (data.op || data.creative) {
            return false;
        }
        KillAuraCheck ka = (KillAuraCheck) byType.get(CheckType.KILLAURA);
        boolean selfEnabled = ka != null && ka.isEnabled() && ka.isSubEnabled("selfinteract");
        boolean hardCancelSelf = manager.config().raw().getBoolean(
                "checks.killaura.selfinteract.hard-cancel", true);
        if (!KillAuraCheck.shouldHardCancel(data, targetId, playerEntityId,
                selfEnabled, hardCancelSelf)) {
            return false;
        }
        if (ka != null && targetId == playerEntityId) {
            data.actor.submit(() -> ka.onSelfInteractHard(data));
        }
        return true;
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
