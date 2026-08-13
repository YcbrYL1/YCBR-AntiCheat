package com.ycbr.anticheat.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.pipeline.PlayerActor;

public final class PlayerData {

    private final UUID uuid;
    public final PlayerActor actor = new PlayerActor();
    public final MovementTracker movement = new MovementTracker();
    public final VelocityState velocity = new VelocityState();
    public final Queue<Long> attackTimes = new ConcurrentLinkedQueue<Long>();
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
    public volatile boolean blockNearLiquid;
    public volatile boolean blockBoxedIn;
    public volatile boolean blockInWeb;
    public volatile boolean blockOnLadder;
    public volatile boolean blockBelowUnstandable;

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

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        this.lastActive = System.currentTimeMillis();
    }

    public UUID getUuid() {
        return uuid;
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