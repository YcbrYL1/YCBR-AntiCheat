package com.ycbr.anticheat.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.TransactionTracker;
import com.ycbr.anticheat.pipeline.PlayerActor;
import com.ycbr.anticheat.simulation.ShadowPlayer;

public final class PlayerData {

    private final UUID uuid;
    public final PlayerActor actor = new PlayerActor();
    public final MovementTracker movement = new MovementTracker();
    public final ShadowPlayer shadow = new ShadowPlayer();
    public final VelocityState velocity = new VelocityState();

    /** 击退速度账本（P2-8，学 NCP）：识别"发出但从未消费"的击退（默认关子检测）。 */
    public final com.ycbr.anticheat.simulation.VelocityLedger velocityLedger
            = new com.ycbr.anticheat.simulation.VelocityLedger();

    /** ImproBable 跨检测融合桶（Phase 10，P2-9）：亚阈值小违规按类别喂票。 */
    public final ImprobableTracker improbable = new ImprobableTracker();
    public final Queue<Long> attackTimes = new ConcurrentLinkedQueue<Long>();
    /**
     * FastClick 独立的攻击时刻队列。不能复用 attackTimes：KillAuraCheck.checkCps
     * 也在 onAttack 派发链中向 attackTimes 写入本次攻击时间戳（cps 默认开），
     * 双写会让 burst 计数翻倍（实际 N 次攻击数出 2N），FastClick burst 误判。
     */
    public final Queue<Long> fastClickTimes = new ConcurrentLinkedQueue<Long>();
    public final Queue<Long> placeTimes = new ConcurrentLinkedQueue<Long>();
    public final PlacePoints placePoints = new PlacePoints();
    public final Map<String, Double> buffers = new HashMap<String, Double>();
    private final Map<CheckType, AtomicLong> violations = new ConcurrentHashMap<CheckType, AtomicLong>();
    private final Map<CheckType, Long> lastFlagTimes = new ConcurrentHashMap<CheckType, Long>();

    public volatile double lastYaw;
    public volatile double lastPitch;
    public volatile boolean hasRotation;
    public volatile double prevYaw;
    public volatile double prevPitch;
    public volatile boolean hasPrevRotation;
    public volatile long lastPacketTime;
    public volatile long lastActive;

    // 视角历史环（Reach 多帧枚举专用，独立于 KillAura 的 prevYaw 状态）
    public static final int ROT_HIST_SIZE = 4;
    public final double[] rotHistYaw = new double[ROT_HIST_SIZE];
    public final double[] rotHistPitch = new double[ROT_HIST_SIZE];
    public final int[] rotHistTick = new int[ROT_HIST_SIZE];
    public volatile int rotHistHead;

    public void pushRotation(double yaw, double pitch, int serverTick) {
        rotHistHead = (rotHistHead + 1) % ROT_HIST_SIZE;
        rotHistYaw[rotHistHead] = yaw;
        rotHistPitch[rotHistHead] = pitch;
        rotHistTick[rotHistHead] = serverTick;
    }

    public volatile int ping;
    public volatile int speedLevel;
    public volatile int jumpLevel;
    public volatile int rotationAwayStreak;
    public volatile int cpsStreak;
    public volatile long lastAttackTime;
    public volatile long lastSwingTime;
    public volatile int lastAttackTargetId;
    public volatile long positionCount;
    public volatile long lastAttackPositionCount;
    public volatile int throughWallsBurst;
    public volatile long lastThroughWallsTime;
    public volatile boolean usingItem;
    public volatile boolean digging;
    public volatile long lastItemUseTime;
    public volatile int useEntityCount;
    public volatile int switchAttackCount;
    public volatile long lastTargetSwitchTime;
    public volatile boolean attackTight;
    public volatile long attackTightTime;
    public volatile long monoClock;
    public volatile long lastSwingPositionCount;
    public volatile double noFallMaxY;
    public volatile double noFallMinY;
    public volatile long lastFallDamageTime;
    public volatile int lastAirTicks;
    public final java.util.ArrayDeque<Long> burstTimes = new java.util.ArrayDeque<Long>();
    public volatile long lastBigTurnTime;
    public volatile double lastYawDelta;
    public volatile long pendingReversalTime;
    public volatile int gcdStreak;
    public volatile int kbLowTicks;
    public volatile int kbHLowTicks;
    public volatile int kbHPartialTicks;
    public volatile int kbPreciseTicks;
    /** 击退中等削减连续计数（ratio ∈ [mid-reduce-min, precise-band-min) 检测带）。 */
    public volatile int kbMidReduceTicks;
    public volatile double kbPreSpeed;
    public volatile long lastSprintStartTime;
    public volatile long lastSprintStopTime;
    public volatile boolean kbSprintResetCounted;
    public volatile int kbSprintResetStreak;
    public volatile boolean kbJumpedThisKb;
    public volatile int kbJumpResetStreak;
    public volatile boolean blockingSword;
    public volatile long lastKbTime;
    public volatile boolean authenticated;
    public volatile int timeTest;
    public volatile long timeTestNotifyAt;
    public volatile int kbDirectionTicks;
    public volatile int kbNoRiseTicks;
    public volatile int fallKbMissTicks;
    public volatile int speedSpikeTicks;
    public volatile int nofallClaims;
    public volatile int nofallWindowTicks;
    public final java.util.List<Double> aimDeltas = new java.util.ArrayList<Double>();
    public final java.util.List<Double> aimPitchDeltas = new java.util.ArrayList<Double>();
    public final java.util.List<Long> gcdBucket = new java.util.ArrayList<Long>();
    public final java.util.List<Long> attackIntervals = new java.util.ArrayList<Long>();
    public final java.util.List<Long> bigRotQueue = new java.util.ArrayList<Long>();
    public final java.util.List<Long[]> targetHistory = new java.util.ArrayList<Long[]>();
    public final java.util.List<Long[]> pendingAngleTargets = new java.util.ArrayList<Long[]>();
    public final java.util.ArrayDeque<Long> moveTimes = new java.util.ArrayDeque<Long>();
    public int samePosStreak;
    public volatile int samePosPeak;
    public final java.util.ArrayDeque<Double> speedLimits = new java.util.ArrayDeque<Double>();
    public final java.util.ArrayDeque<Double> speedSamples = new java.util.ArrayDeque<Double>();
    public final java.util.List<Double> placeYawDeltas = new java.util.ArrayList<Double>();
    public final java.util.List<Double> placeYaws = new java.util.ArrayList<Double>();
    public final java.util.List<Double> placePitches = new java.util.ArrayList<Double>();
    public volatile boolean creative;
    public volatile boolean op;
    public volatile boolean flying;
    public volatile boolean inVehicle;
    public volatile boolean dead;

    public volatile long lastTeleportTime;
    public volatile double lastTeleportX;
    public volatile double lastTeleportY;
    public volatile double lastTeleportZ;

    public volatile boolean blockOnIce;
    public volatile boolean blockOnSlime;
    public volatile boolean blockOnSoulSand;
    public volatile boolean blockNearLiquid;
    public volatile boolean blockBoxedIn;
    public volatile boolean blockInWeb;
    public volatile boolean blockOnLadder;
    public volatile boolean blockBelowUnstandable;

    /** 脚下/脚所在方块是台阶或楼梯（允许 ≤0.6 的自动步进垂直位移）。 */
    public volatile boolean blockOnStairsOrSlab;

    /**
     * 碰撞重演体素快照（主线程快照周期采集，包线程只读）。
     * null = 不可用（模拟关闭/未初始化），检测回退旧（墙距+豁免）路径。
     */
    public volatile com.ycbr.anticheat.simulation.VoxelGrid voxelGrid;

    /** 脚下/脚所在方块是活塞臂实体（PISTON_MOVING_PIECE）：位移由活塞外部驱动。 */
    public volatile boolean blockOnPiston;

    /** 前方墙距（yaw 方向，米；0 = 未探测/无墙）。 */
    public volatile double wallFwdDist;
    /** 左侧墙距（yaw-90°，米；0 = 未探测/无墙）。 */
    public volatile double wallLeftDist;
    /** 右侧墙距（yaw+90°，米；0 = 未探测/无墙）。 */
    public volatile double wallRightDist;

    public volatile boolean clientOnGround;
    public volatile boolean prevClientOnGround;

    public volatile int hoverTicks;
    public volatile boolean lastRiseOver;
    public volatile int speedSpikeGraceTicks;
    public volatile int aimStepStreak;
    public volatile int angleViolationStreak;
    public volatile int targetSwitchStreak;
    public volatile long lastAttackTargetTime;
    public volatile long lastRotationTime;
    public volatile long lastPositionMillis;
    public volatile long lastMoveIntervalMs;
    public volatile long joinedMillis;
    public volatile long lastBlinkFlagTime;
    public volatile int lastSprintAction;
    public volatile long lastSprintActionTime;
    public volatile int sprintFlipCount;
    public volatile boolean lookOnGround;
    public volatile int steerVehicleStreak;
    public volatile long lastRidingJumpTime;
    public volatile long lastVehicleTime;
    public volatile int noSlowStreak;
    public volatile long lastKeepAliveId = -1L;
    public volatile long lastSlotChangeTime;
    public volatile long lastAutoToolFlagTime;
    public volatile long lastBurstExceedMs;
    public volatile long lastDigStartTime;
    public volatile int modulo360Streak;
    public volatile long lastThrowTime;
    public volatile int fastThrowCount;
    public volatile long lastFastThrowFlagTime;
    public volatile long bowPullTime;
    public volatile long lastBowFlagTime;
    public volatile long lastFastClickFlagTime;

    // ---- FastClick 独立攻击间隔基准 ----
    // 不能复用 data.lastAttackTime：KillAuraCheck 在 onAttack 派发链中先执行并把它
    // 更新为"本次攻击"时间戳，FastClickCheck 后执行读到的间隔恒为 ~0ms（样本污染 →
    // 机械判定对任何点击者必然命中）。用独立字段记录本检测自己的上次攻击时刻。
    public volatile long lastFastClickAttackTime;

    // ---- AutoBlock（attack while digging）连续计数 ----
    /** 连续"攻击时仍在挖掘"次数（间隔 &lt; streak-gap-ms 才累计）。 */
    public volatile int autoBlockStreak;
    /** 最近一次 AutoBlock 判定时刻（ms）。 */
    public volatile long lastAutoBlockAttackTime;

    /** 每玩家事务往返追踪器（见 TransactionTracker）。由 {@link #transaction(AntiCheatManager)} 懒初始化。 */
    public volatile TransactionTracker transaction;

    // ---- Aim 统计层（Phase 3.1）----
    /** 攻击窗口内的 yaw/pitch 增量样本（最近 50 个）。 */
    public final java.util.ArrayDeque<Double> aimDeltasStat = new java.util.ArrayDeque<Double>();
    public final java.util.ArrayDeque<Double> aimPitchDeltasStat = new java.util.ArrayDeque<Double>();
    public volatile double lastAimYaw;
    public volatile double lastAimPitch;
    /** 已收集的统计样本数（KillAura 冷启动门控用）。 */
    public volatile int statSampleCount;
    /** 最近一次 aim-stat 交叉信号产生时刻（新鲜度校验用）。 */
    public volatile long aimStatSignalTime;

    /** 最近一次移动包到达时的服务器 tick 计数（TimerCheck 间隔测量用）。 */
    public volatile int lastMoveServerTick;

    /** 击退包发送时刻的服务器 tick 计数（VelocityCheck 事务到达窗口用）。 */
    public volatile int kbIssuedServerTick;
    /** 击退包预计到达客户端的服务器 tick（发送 tick + ceil(RTT/50)）。 */
    public volatile int kbArrivalServerTick;

    public volatile int kbLedgerStreak;

    // ---- 惩罚框架（Phase 0.4）----
    /** 攻击阻断截止时间（ms），此前 onAttack 不派发到检测。 */
    public volatile long attackBlockedUntil;
    /**
     * 硬检测突发取消截止时间（ms）：MultiInteract 命中后在此窗口内，
     * 监听线程会直接取消后续攻击包（Grim cancelBuffer 语义）。
     */
    public volatile long hardCancelUntil;
    /** 交叉信号集合（多检测协同投票，如 killaura+reach+aim 同时命中）。 */
    public final java.util.Set<String> crossSignals =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** 最近一次 setback 时间与目标位置。 */
    public volatile long lastSetbackTime;
    public volatile double setbackX;
    public volatile double setbackY;
    public volatile double setbackZ;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        this.lastActive = System.currentTimeMillis();
    }

    public UUID getUuid() {
        return uuid;
    }

    /**
     * 懒初始化并返回本玩家的事务追踪器。下游检测（Timer/Blink/Velocity）应统一通过此
     * 方法获取，确保 tracker 一定已创建（且只创建一次）。
     */
    public TransactionTracker transaction(AntiCheatManager manager) {
        if (transaction == null) {
            synchronized (this) {
                if (transaction == null) {
                    transaction = new TransactionTracker(manager, uuid);
                }
            }
        }
        return transaction;
    }

    public void addViolation(CheckType type) {
        AtomicLong value = violations.get(type);
        if (value == null) {
            value = new AtomicLong();
            AtomicLong old = violations.putIfAbsent(type, value);
            if (old != null) {
                value = old;
            }
        }
        value.incrementAndGet();
    }

public long getViolations(CheckType type) {
        AtomicLong value = violations.get(type);
        return value == null ? 0L : value.get();
    }

    public void setViolations(CheckType type, long value) {
        AtomicLong current = violations.get(type);
        if (current == null) {
            current = new AtomicLong();
            AtomicLong old = violations.putIfAbsent(type, current);
            if (old != null) {
                current = old;
            }
        }
        current.set(value);
    }

    public void resetViolations(CheckType type) {
        violations.remove(type);
    }

    public long getLastFlagTime(CheckType type) {
        Long time = lastFlagTimes.get(type);
        return time == null ? 0L : time;
    }

    public void setLastFlagTime(CheckType type, long time) {
        lastFlagTimes.put(type, time);
    }
}