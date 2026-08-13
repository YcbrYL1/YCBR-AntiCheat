package com.ycbr.anticheat.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.ycbr.anticheat.YCBR;

public final class DDosGuard {

    private static final long LOG_COOLDOWN_MS = 5000L;
    private static final int CODE_HANDSHAKE = 0;
    private static final int CODE_STATUS = 1;
    private static final int CODE_LOGIN = 2;
    private static final int CODE_PLAY = 3;
    private static final int CODE_UNKNOWN = -1;

    private final YCBR plugin;
    private final YCBRConfig cfg;
    private final ProtocolManager protocolManager;
    private PacketAdapter adapter;
    private int scanTaskId;
    private int scheduledInterval;

    private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<String, Deque<Long>>();
    private final Map<Object, long[]> connTrack = new HashMap<Object, long[]>();

    private volatile long violations;
    private volatile long closedConnections;
    private volatile long rateBlocks;
    private volatile long statusPings;
    private volatile int currentConnections;
    private long lastViolationLog;
    private long lastCloseLog;
    private boolean reflectionWarned;

    public DDosGuard(YCBR plugin) {
        this.plugin = plugin;
        this.cfg = new YCBRConfig(plugin);
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public boolean enabled() {
        return cfg.raw().getBoolean("settings.ddos.enabled", true);
    }

    public int maxLogins() {
        return Math.max(1, cfg.raw().getInt("settings.ddos.max-logins-per-minute", 3));
    }

    public int maxUsername() {
        return cfg.raw().getInt("settings.ddos.max-username-length", 16);
    }

    private int maxHostname() {
        return cfg.raw().getInt("settings.ddos.max-hostname-length", 255);
    }

    private int maxEncrypted() {
        return cfg.raw().getInt("settings.ddos.max-encrypted-response-length", 256);
    }

    private int handshakeTimeout() {
        return Math.max(1, cfg.raw().getInt("settings.ddos.handshake-timeout-seconds", 30));
    }

    private int statusTimeout() {
        return Math.max(1, cfg.raw().getInt("settings.ddos.status-timeout-seconds", 10));
    }

    private int loginTimeout() {
        return Math.max(1, cfg.raw().getInt("settings.ddos.login-timeout-seconds", 40));
    }

    private int scanInterval() {
        return Math.max(1, cfg.raw().getInt("settings.ddos.scan-interval-seconds", 5));
    }

    public long getViolations() {
        return violations;
    }

    public long getClosedConnections() {
        return closedConnections;
    }

    public long getRateBlocks() {
        return rateBlocks;
    }

    public long getStatusPings() {
        return statusPings;
    }

    public int getCurrentConnections() {
        return currentConnections;
    }

    public void countRateBlock() {
        rateBlocks++;
    }

    public void start() {
        adapter = new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Handshake.Client.SET_PROTOCOL,
                PacketType.Status.Client.START, PacketType.Status.Client.PING,
                PacketType.Login.Client.START, PacketType.Login.Client.ENCRYPTION_BEGIN) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPacketType() == PacketType.Handshake.Client.SET_PROTOCOL) {
                    onHandshake(event.getPacket().getStrings().read(0));
                } else if (event.getPacketType() == PacketType.Status.Client.START
                        || event.getPacketType() == PacketType.Status.Client.PING) {
                    statusPings++;
                } else if (event.getPacketType() == PacketType.Login.Client.START) {
                    String name = null;
                    try {
                        name = event.getPacket().getGameProfiles().read(0).getName();
                    } catch (Exception ignored) {}
                    if (name != null) {
                        onLoginStart(name);
                    }
                } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_BEGIN) {
                    onEncryptionBegin(event.getPacket());
                }
            }
        };
        protocolManager.addPacketListener(adapter);
        scheduledInterval = scanInterval();
        scanTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled()) {
                return;
            }
            int interval = scanInterval();
            if (interval != scheduledInterval) {
                scheduledInterval = interval;
                Bukkit.getScheduler().cancelTask(scanTaskId);
                scanTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::runScan,
                        interval * 20L, interval * 20L).getTaskId();
                return;
            }
            runScan();
        }, scheduledInterval * 20L, scheduledInterval * 20L).getTaskId();
    }

    private void runScan() {
        try {
            List<Object> managers = new ArrayList<Object>();
            Object mcServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object serverConnection = findServerConnection(mcServer);
            if (serverConnection == null) {
                return;
            }
            for (Field field : serverConnection.getClass().getDeclaredFields()) {
                if (field.getType() != List.class || !isTypedList(field, "NetworkManager")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(serverConnection);
                if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        if (item != null && item.getClass().getName().endsWith("NetworkManager")) {
                            managers.add(item);
                        }
                    }
                }
            }
            long now = System.currentTimeMillis();
            Set<Object> seen = new HashSet<Object>();
            for (Object networkManager : managers) {
                Object channel = findChannel(networkManager);
                if (channel == null) {
                    continue;
                }
                seen.add(networkManager);
                int code = stateCode(listenerState(networkManager));
                if (code == CODE_PLAY || code == CODE_UNKNOWN) {
                    connTrack.remove(networkManager);
                    continue;
                }
                long[] entry = connTrack.get(networkManager);
                if (entry == null || entry[0] != code) {
                    connTrack.put(networkManager, new long[] { code, now });
                    continue;
                }
                long elapsed = now - entry[1];
                long timeout = code == CODE_STATUS ? statusTimeout() * 1000L
                        : code == CODE_LOGIN ? loginTimeout() * 1000L : handshakeTimeout() * 1000L;
                if (elapsed > timeout) {
                    try {
                        channel.getClass().getMethod("close").invoke(channel);
                    } catch (Exception ignored) {
                    }
                    connTrack.remove(networkManager);
                    closedConnections++;
                    if (now - lastCloseLog > LOG_COOLDOWN_MS) {
                        lastCloseLog = now;
                        log("&eclosed " + stateName(code) + " connection from &f" + ipOf(channel)
                                + "&e (idle " + (elapsed / 1000L) + "s)");
                    }
                }
            }
            connTrack.keySet().removeIf(key -> !seen.contains(key));
            currentConnections = managers.size();
        } catch (Exception ex) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                Bukkit.getLogger().warning("YCBR: DDoS connection scan unavailable on this server version: "
                        + ex.getMessage());
            }
        }
    }

    private void onHandshake(String hostname) {
        if (!enabled()) {
            return;
        }
        if (hostname != null && hostname.length() > maxHostname()) {
            countViolation("handshake: hostname length " + hostname.length() + " > " + maxHostname());
        }
    }

    private void onLoginStart(String name) {
        if (!enabled()) {
            return;
        }
        if (name == null) {
            return;
        }
        if (name.length() > maxUsername()) {
            countViolation("login: username length " + name.length() + " > " + maxUsername());
        }
        long now = System.currentTimeMillis();
        Deque<Long> deque = loginAttempts.computeIfAbsent(name.toLowerCase(), k -> new ArrayDeque<Long>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > 60000L) {
                deque.pollFirst();
            }
            deque.addLast(now);
        }
    }

    public boolean isRateBlocked(String name) {
        if (!enabled() || name == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> deque = loginAttempts.get(name.toLowerCase());
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > 60000L) {
                deque.pollFirst();
            }
            return deque.size() > maxLogins();
        }
    }

    private void onEncryptionBegin(PacketContainer packet) {
        if (!enabled()) {
            return;
        }
        for (byte[] bytes : packet.getByteArrays().getValues()) {
            if (bytes != null && bytes.length > maxEncrypted()) {
                countViolation("encryption: response length " + bytes.length + " > " + maxEncrypted());
                return;
            }
        }
    }

    private void countViolation(String detail) {
        violations++;
        long now = System.currentTimeMillis();
        if (now - lastViolationLog > LOG_COOLDOWN_MS) {
            lastViolationLog = now;
            log("&c" + detail + " &7(累计 " + violations + ")");
        }
    }

    private void log(String message) {
        Bukkit.getConsoleSender().sendMessage(cfg.prefix() + "[DDoS] " + message);
    }

    private static Object findServerConnection(Object mcServer) {
        try {
            return mcServer.getClass().getMethod("getServerConnection").invoke(mcServer);
        } catch (Exception e) {
            for (Method method : mcServer.getClass().getMethods()) {
                if (method.getParameterTypes().length == 0
                        && method.getReturnType().getName().endsWith("ServerConnection")) {
                    try {
                        return method.invoke(mcServer);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static boolean isTypedList(Field field, String suffix) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType)) {
            return false;
        }
        Type arg = ((ParameterizedType) generic).getActualTypeArguments()[0];
        return arg instanceof Class && ((Class<?>) arg).getName().endsWith(suffix);
    }

    private static Object findChannel(Object networkManager) {
        try {
            for (Field field : networkManager.getClass().getDeclaredFields()) {
                if ("io.netty.channel.Channel".equals(field.getType().getName())) {
                    field.setAccessible(true);
                    return field.get(networkManager);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String listenerState(Object networkManager) {
        try {
            Method method = networkManager.getClass().getMethod("getPacketListener");
            Object listener = method.invoke(networkManager);
            if (listener != null) {
                return listener.getClass().getSimpleName();
            }
        } catch (Exception e) {
            try {
                for (Field field : networkManager.getClass().getDeclaredFields()) {
                    if (field.getType().getName().endsWith("PacketListener")) {
                        field.setAccessible(true);
                        Object listener = field.get(networkManager);
                        if (listener != null) {
                            return listener.getClass().getSimpleName();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static int stateCode(String listenerName) {
        if (listenerName.contains("Handshake")) {
            return CODE_HANDSHAKE;
        }
        if (listenerName.contains("Status")) {
            return CODE_STATUS;
        }
        if (listenerName.contains("Login")) {
            return CODE_LOGIN;
        }
        if (listenerName.contains("PlayerConnection") || listenerName.equals("ConnectionListener")) {
            return CODE_PLAY;
        }
        return CODE_UNKNOWN;
    }

    private static String stateName(int code) {
        return code == CODE_STATUS ? "status" : code == CODE_LOGIN ? "login" : "handshake";
    }

    private static String ipOf(Object channel) {
        try {
            Object remote = channel.getClass().getMethod("remoteAddress").invoke(channel);
            if (remote instanceof InetSocketAddress) {
                return ((InetSocketAddress) remote).getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    public void stop() {
        if (adapter != null) {
            protocolManager.removePacketListener(adapter);
        }
        if (scanTaskId != 0) {
            Bukkit.getScheduler().cancelTask(scanTaskId);
        }
    }
}
