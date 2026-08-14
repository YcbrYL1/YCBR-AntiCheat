package com.ycbr.anticheat.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.ycbr.anticheat.YCBR;

/**
 * 客户端-服务器事务往返追踪（借鉴 Grim LatencyHandler）。
 *
 * <p>原理：服务器定时（每 tick，节流）向客户端发送一个 {@code Transaction(id)} 包，
 * 客户端必须原样回包；通过测量"发送 → 收到回包"的耗时得到精确的客户端 RTT，
 * 从而取代 {@code ping}/wall-clock 估算。Timer、Blink、Velocity 三个协议类检测
 * 将改用本追踪器判断"击退/移动包是否已到达客户端"，消除高 ping 与网络抖动导致的误判。</p>
 *
 * <p>每个玩家一个实例，挂在 {@link com.ycbr.anticheat.data.PlayerData#transaction}。
 * 发送在主线程（Bukkit scheduler）进行，回包在 Netty 网络线程由
 * {@code AsyncPacketListener} 调用 {@link #onReceive(short)} 处理；两者只通过
 * 线程安全的 {@code ConcurrentHashMap} 与 volatile 字段交互。</p>
 */
public final class TransactionTracker {

    /** 每 tick 至少间隔（ms）才发送下一个事务包，与 tick 间隔(~50ms)双保险。 */
    private static final long SEND_INTERVAL_MS = 45L;
    /** 玩家离线超过该 tick 数（≈10s @20tps）后自停调度任务，避免任务泄漏。 */
    private static final int OFFLINE_STOP_TICKS = 200;
    /** RTT 异常上限（ms），超出视为无效样本丢弃。 */
    private static final long MAX_RTT_MS = 30000L;

    private final YCBR plugin;
    private final java.util.UUID uuid;
    private final ProtocolManager protocolManager;

    private final AtomicInteger nextId = new AtomicInteger(0);
    private final Map<Short, Long> sent = new ConcurrentHashMap<Short, Long>();

    private volatile double lastRttMs = 50.0D;
    private volatile long lastPong = System.currentTimeMillis();
    private volatile long lastSend = 0L;

    private int taskId = -1;
    private int offlineTicks = 0;

    public TransactionTracker(AntiCheatManager manager, java.util.UUID uuid) {
        this.plugin = manager.getPlugin();
        this.uuid = uuid;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        start();
    }

    private void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                send();
            }
        }, 1L, 1L).getTaskId();
    }

    /**
     * 主线程调用：向客户端发送一个事务包并记录发送时刻。
     * 节流：每 tick 至多 1 个；玩家离线超过 {@link #OFFLINE_STOP_TICKS} 后自停。
     */
    public void send() {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            if (++offlineTicks > OFFLINE_STOP_TICKS) {
                stop();
            }
            return;
        }
        offlineTicks = 0;
        long now = System.currentTimeMillis();
        if (now - lastSend < SEND_INTERVAL_MS) {
            return;
        }
        lastSend = now;

        short id = (short) nextId.getAndIncrement();
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.TRANSACTION);
        packet.getIntegers().write(0, 0);    // windowId（玩家背包窗口）
        packet.getShorts().write(0, id);      // action（自增序号，用作回包匹配键）
        packet.getBooleans().write(0, true);  // accepted
        sent.put(id, now);
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception ignored) {
            // 发送失败（如玩家正在登出）：丢弃该样本，等待下一 tick 重试
            sent.remove(id);
        }
    }

    /**
     * 客户端回包时由 {@code AsyncPacketListener} 调用，计算并平滑 RTT。
     */
    public void onReceive(short id) {
        Long t0 = sent.remove(id);
        if (t0 != null) {
            long rtt = System.currentTimeMillis() - t0;
            if (rtt >= 0L && rtt < MAX_RTT_MS) {
                // 指数移动平均：抑制单次抖动，给下游检测一个稳定的 RTT 估计
                lastRttMs = lastRttMs * 0.7D + (double) rtt * 0.3D;
            }
            lastPong = System.currentTimeMillis();
        }
    }

    /** 最近一次往返延迟（ms，EMA 平滑）。在未收到任何回包前返回默认 50ms。 */
    public double rttMs() {
        return lastRttMs;
    }

    /** 最近一次收到客户端回包的时刻（System.currentTimeMillis()）。 */
    public long lastPongTime() {
        return lastPong;
    }

    /**
     * 以事务往返为准估算"客户端已处理的 tick 进度"：
     * 距上次 pong 已过的毫秒数 / 50ms ≈ 客户端侧已推进的 tick 数（上限 10）。
     * 供 Timer/Blink 在"客户端时间轴"上判定，而非服务器 wall-clock。
     */
    public int clientTicksAhead() {
        return (int) Math.min(10L, (System.currentTimeMillis() - lastPong) / 50L);
    }

    /** 停止调度任务并清理待确认样本。 */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        sent.clear();
    }
}
