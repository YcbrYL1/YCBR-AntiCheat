package com.ycbr.anticheat.command;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.YCBRConfig;
import com.ycbr.anticheat.data.ImprobableTracker;
import com.ycbr.anticheat.data.PlayerData;

/**
 * YCBR 管理 GUI（优化版）。
 *
 * <p>优化点：
 * <ol>
 *   <li><b>性能</b>：1s TTL 快照缓存（玩家列表按 VL 排序 + 全服分检测 VL），
 *       替代每次打开 O(玩家×检测) 的实时遍历；融合分析页独立缓存。</li>
 *   <li><b>视觉</b>：占位空格不再塞黑玻璃（留空气更清爽）；颜色规范统一
 *       （黄=功能、绿=开、红=关/危险、灰=信息）；详情页检测项加 VL 进度条。</li>
 *   <li><b>功能</b>：玩家详情补事务 RTT / usingItem / 灵魂沙字段；
 *       日志页加"清空日志"；主菜单新增<b>融合分析</b>页（展示 ImproBable
 *       P2-9 每玩家三类短窗/长窗计数与全局熔断状态）。</li>
 *   <li><b>导航</b>：统一 fromPage 返回链；清空违规/踢人共用二次确认；
 *       修复子检测页标题精确匹配导致的点击失效 bug。</li>
 * </ol>
 */
public final class GuiManager implements Listener {

    private static final String TITLE_MENU = "\u00a78YCBR \u00a77\u2503 \u00a7f\u4e3b\u83dc\u5355";
    private static final String TITLE_PLAYERS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u73a9\u5bb6\u5217\u8868";
    private static final String TITLE_DETAIL = "\u00a78YCBR \u00a77\u2503 \u00a7f\u73a9\u5bb6\u8be6\u60c5";
    private static final String TITLE_CHECKS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u68c0\u6d4b\u9762\u677f";
    private static final String TITLE_SUBS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u5b50\u68c0\u6d4b";
    private static final String TITLE_SETTINGS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u8bbe\u7f6e";
    private static final String TITLE_LOGS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u8fdd\u89c4\u65e5\u5fd7";
    private static final String TITLE_DDOS = "\u00a78YCBR \u00a77\u2503 \u00a7fDDOS \u9632\u62a4";
    private static final String TITLE_FUSION = "\u00a78YCBR \u00a77\u2503 \u00a7f\u878d\u5408\u5206\u6790";
    private static final String TITLE_OP = "\u00a78YCBR \u00a77\u2503 \u00a7f\u53cd\u4f5c\u5f0aOP";
    private static final String TITLE_OP_MANUAL = "\u00a78YCBR \u00a77\u2503 \u00a7f\u4eba\u5de5\u68c0\u6d4b";
    private static final String TITLE_OP_VL = "\u00a78YCBR \u00a77\u2503 \u00a7f\u67e5\u770b\u73a9\u5bb6VL";

    private static final String[] DDOS_PATHS = {
            "settings.ddos.max-logins-per-minute",
            "settings.ddos.max-hostname-length",
            "settings.ddos.max-username-length",
            "settings.ddos.max-encrypted-response-length",
            "settings.ddos.handshake-timeout-seconds",
            "settings.ddos.status-timeout-seconds",
            "settings.ddos.login-timeout-seconds",
            "settings.ddos.scan-interval-seconds"
    };
    private static final String[] DDOS_LABELS = {
            "\u7528\u6237\u540d\u6bcf\u5206\u949f\u6700\u5927\u767b\u5f55",
            "\u4e3b\u673a\u540d\u6700\u5927\u957f\u5ea6",
            "\u7528\u6237\u540d\u6700\u5927\u957f\u5ea6",
            "EncryptedResponse \u6700\u5927\u957f\u5ea6",
            "Handshake \u8d85\u65f6(\u79d2)",
            "Status \u8d85\u65f6(\u79d2)",
            "Login \u8d85\u65f6(\u79d2)",
            "\u72b6\u6001\u673a\u626b\u63cf\u95f4\u9694(\u79d2)"
    };
    private static final int[] DDOS_DEFAULTS = { 3, 255, 16, 256, 30, 10, 40, 5 };
    private static final int PLAYERS_PER_PAGE = 27;
    private static final short GRAY = 7;
    private static final short GREEN = 10;
    private static final short YELLOW = 11;
    private static final short RED = 14;
    private static final long CONFIRM_MS = 5000L;
    /** 快照缓存有效期：1s 内重复打开/翻页不重建（玩家数×检测数遍历只做一次）。 */
    private static final long SNAPSHOT_TTL_MS = 1000L;

    // 颜色规范：黄=功能 绿=开/正常 红=关/危险 灰=信息 白=数值
    private static final ChatColor C_TITLE = ChatColor.GOLD;
    private static final ChatColor C_ACTION = ChatColor.YELLOW;
    private static final ChatColor C_ON = ChatColor.GREEN;
    private static final ChatColor C_OFF = ChatColor.RED;
    private static final ChatColor C_INFO = ChatColor.GRAY;
    private static final ChatColor C_VAL = ChatColor.WHITE;
    private static final ChatColor C_DIM = ChatColor.DARK_GRAY;

    private enum Page {
        MENU, PLAYERS, DETAIL, CHECKS, CHECK_SUBS, CHECK_SETTINGS, LOGS, DDOS, FUSION,
        OP_MENU, OP_MANUAL, OP_VL
    }

    private static final class GuiState {
        Page page;
        Page from;
        UUID target;
        int playerPage;
        CheckType type;
        long confirmAt;
        String confirmAction; // "kick" | "clear"
        int logPage;
        String editKey;
        String editLabel;
        long editAt;
    }

    /** 玩家行快照（构建时一次性算出，页面渲染不再遍历检测枚举）。 */
    private static final class PlayerSnapshot {
        final UUID uuid;
        final String name;
        final long totalVl;
        final int ping;
        final int attackSize;
        final int timeTest;
        final String lastFlagDisplay; // null 表示无最近 flag

        PlayerSnapshot(UUID uuid, String name, long totalVl, int ping, int attackSize,
                int timeTest, String lastFlagDisplay) {
            this.uuid = uuid;
            this.name = name;
            this.totalVl = totalVl;
            this.ping = ping;
            this.attackSize = attackSize;
            this.timeTest = timeTest;
            this.lastFlagDisplay = lastFlagDisplay;
        }
    }

    /** 全服快照：排序后的玩家行 + 每检测全服累计 VL。 */
    private static final class Snapshot {
        final long at;
        final List<PlayerSnapshot> players;
        final Map<CheckType, Long> serverVl;

        Snapshot(long at, List<PlayerSnapshot> players, Map<CheckType, Long> serverVl) {
            this.at = at;
            this.players = players;
            this.serverVl = serverVl;
        }
    }

    /** 融合分析行：单玩家三类别短/长窗计数。 */
    private static final class FusionEntry {
        final String name;
        final int combatS, combatL, moveS, moveL, protoS, protoL;

        FusionEntry(String name, int combatS, int combatL, int moveS, int moveL,
                int protoS, int protoL) {
            this.name = name;
            this.combatS = combatS;
            this.combatL = combatL;
            this.moveS = moveS;
            this.moveL = moveL;
            this.protoS = protoS;
            this.protoL = protoL;
        }

        int shortTotal() {
            return combatS + moveS + protoS;
        }
    }

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;
    private final Map<UUID, GuiState> states = new ConcurrentHashMap<UUID, GuiState>();

    private volatile Snapshot snapshot;
    private long fusionAt;
    private List<FusionEntry> fusionCache;

    public GuiManager(AntiCheatManager manager) {
        this.manager = manager;
        this.cfg = manager.config();
    }

    public void register() {
        manager.getPlugin().getServer().getPluginManager().registerEvents(this, manager.getPlugin());
    }

    public void open(Player viewer) {
        openMenu(viewer);
    }

    public void openOp(Player viewer) {
        openOpMenu(viewer);
    }

    // =====================================================================
    // 快照缓存（性能）
    // =====================================================================

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        long now = System.currentTimeMillis();
        if (s != null && now - s.at < SNAPSHOT_TTL_MS) {
            return s;
        }
        List<PlayerSnapshot> players = new ArrayList<PlayerSnapshot>();
        Map<CheckType, Long> serverVl = new EnumMap<CheckType, Long>(CheckType.class);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = manager.getDataManager().get(p.getUniqueId());
            if (data == null) {
                continue;
            }
            long total = 0L;
            for (CheckType type : CheckType.values()) {
                long vl = data.getViolations(type);
                total += vl;
                Long acc = serverVl.get(type);
                serverVl.put(type, Long.valueOf((acc == null ? 0L : acc.longValue()) + vl));
            }
            players.add(new PlayerSnapshot(p.getUniqueId(), p.getName(), total, data.ping,
                    data.attackTimes.size(), data.timeTest, lastFlag(data)));
        }
        players.sort((a, b) -> {
            if (a.totalVl != b.totalVl) {
                return Long.compare(b.totalVl, a.totalVl);
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        s = new Snapshot(now, players, serverVl);
        snapshot = s;
        return s;
    }

    private List<FusionEntry> fusionData() {
        long now = System.currentTimeMillis();
        if (fusionCache != null && now - fusionAt < SNAPSHOT_TTL_MS) {
            return fusionCache;
        }
        int tick = manager.getMainHandler().currentServerTick();
        List<FusionEntry> list = new ArrayList<FusionEntry>();
        for (PlayerData data : manager.getDataManager().all()) {
            Player p = Bukkit.getPlayer(data.getUuid());
            if (p == null) {
                continue;
            }
            int cs = data.improbable.shortCount(ImprobableTracker.Category.COMBAT, tick);
            int cl = data.improbable.longCount(ImprobableTracker.Category.COMBAT, tick);
            int ms = data.improbable.shortCount(ImprobableTracker.Category.MOVEMENT, tick);
            int ml = data.improbable.longCount(ImprobableTracker.Category.MOVEMENT, tick);
            int ps = data.improbable.shortCount(ImprobableTracker.Category.PROTOCOL, tick);
            int pl = data.improbable.longCount(ImprobableTracker.Category.PROTOCOL, tick);
            if (cs + cl + ms + ml + ps + pl == 0) {
                continue; // 无小违规记录，不占行
            }
            list.add(new FusionEntry(p.getName(), cs, cl, ms, ml, ps, pl));
        }
        list.sort((a, b) -> Integer.compare(b.shortTotal(), a.shortTotal()));
        fusionCache = list;
        fusionAt = now;
        return list;
    }

    // =====================================================================
    // 页面
    // =====================================================================

    private void openMenu(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_MENU);
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, glass());
        }
        List<String> playersLore = new ArrayList<String>();
        playersLore.add(C_INFO + "\u5728\u7ebf\uff1a" + C_VAL + Bukkit.getOnlinePlayers().size()
                + C_INFO + "\u4eba");
        playersLore.add("");
        playersLore.add(C_DIM + "\u70b9\u51fb\u6253\u5f00\u73a9\u5bb6\u5217\u8868");
        inv.setItem(9, infoItem(Material.SKULL_ITEM, C_TITLE + "\u73a9\u5bb6\u5217\u8868", playersLore
                .toArray(new String[0])));
        List<String> checksLore = new ArrayList<String>();
        int enabled = 0;
        for (CheckType type : CheckType.values()) {
            if (cfg.enabled(type.getConfigPath())) {
                enabled++;
            }
        }
        checksLore.add(C_INFO + "\u542f\u7528\u68c0\u6d4b\uff1a" + C_VAL + enabled + " / "
                + CheckType.values().length);
        checksLore.add("");
        checksLore.add(C_DIM + "\u70b9\u51fb\u6253\u5f00\u68c0\u6d4b\u9762\u677f");
        inv.setItem(11, infoItem(Material.BOOK, C_TITLE + "\u68c0\u6d4b\u914d\u7f6e", checksLore
                .toArray(new String[0])));
        List<String> logsLore = new ArrayList<String>();
        logsLore.add(C_INFO + "\u6700\u8fd1\u89e6\u53d1\u7684\u8fdd\u89c4\u8bb0\u5f55");
        logsLore.add("");
        logsLore.add(C_DIM + "\u70b9\u51fb\u6253\u5f00\u65e5\u5fd7");
        inv.setItem(13, infoItem(Material.PAPER, C_TITLE + "\u8fdd\u89c4\u65e5\u5fd7", logsLore
                .toArray(new String[0])));
        List<String> ddosLore = new ArrayList<String>();
        ddosLore.add(C_INFO + "\u9632\u62a4\uff1a" + (cfg.raw().getBoolean("settings.ddos.enabled", true)
                ? C_ON + "\u5f00\u542f" : C_OFF + "\u5173\u95ed"));
        ddosLore.add(C_INFO + "\u8d85\u65f6\u5173\u95ed\u8fde\u63a5\uff1a" + C_VAL
                + manager.getDdosGuard().getClosedConnections());
        ddosLore.add("");
        ddosLore.add(C_DIM + "\u70b9\u51fb\u6253\u5f00 DDOS \u9632\u62a4\u8bbe\u7f6e");
        inv.setItem(15, infoItem(Material.DIAMOND_CHESTPLATE, C_TITLE + "DDoS \u9632\u62a4", ddosLore
                .toArray(new String[0])));
        List<String> fusionLore = new ArrayList<String>();
        fusionLore.add(C_INFO + "\u878d\u5408\u68c0\u6d4b\uff1a"
                + (cfg.raw().getBoolean("checks.improbable.enabled", false)
                        ? C_ON + "\u5f00\u542f" : C_OFF + "\u5173\u95ed"));
        fusionLore.add(C_INFO + "\u5168\u5c40\u878d\u65ad\uff1a"
                + (manager.getMainHandler().isFused() ? C_OFF + "\u6fc0\u6d3b\u4e2d"
                        : C_ON + "\u6b63\u5e38"));
        fusionLore.add("");
        fusionLore.add(C_DIM + "\u5404\u68c0\u6d4b\u5c0f\u8fdd\u89c4\u878d\u5408\u9884\u8b66");
        inv.setItem(17, infoItem(Material.WATCH, C_TITLE + "\u878d\u5408\u5206\u6790", fusionLore
                .toArray(new String[0])));
        List<String> statusLore = new ArrayList<String>();
        statusLore.add(C_INFO + "TPS\uff1a" + (manager.getMainHandler().getTps() >= 18.0D
                ? C_ON : manager.getMainHandler().getTps() >= 14.0D
                        ? C_ACTION : C_OFF)
                + String.format("%.1f", manager.getMainHandler().getTps()));
        statusLore.add(C_INFO + "\u878d\u65ad\uff1a"
                + (manager.getMainHandler().isFused() ? C_OFF + "\u6fc0\u6d3b\u4e2d"
                        : C_ON + "\u6b63\u5e38"));
        statusLore.add("");
        statusLore.add(C_DIM + "\u4fe1\u606f\u4ec5\u89c2\u770b");
        inv.setItem(4, infoItem(Material.NETHER_STAR, C_TITLE + "\u670d\u52a1\u5668\u72b6\u6001",
                statusLore.toArray(new String[0])));

        GuiState state = new GuiState();
        state.page = Page.MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openPlayers(Player viewer, int pageIdx) {
        Snapshot snap = snapshot();
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_PLAYERS);
        fillBorder(inv, new int[] { 9, 10, 11, 12, 45, 46, 47, 52 });
        inv.setItem(9, named(Material.ANVIL, C_ACTION + "\u91cd\u8f7d\u914d\u7f6e"));
        inv.setItem(10, named(Material.BOOK, C_ACTION + "\u68c0\u6d4b\u9762\u677f"));
        inv.setItem(11, named(Material.EYE_OF_ENDER, C_ACTION + "\u6211\u7684\u544a\u8b66\uff1a"
                + (manager.getMainHandler().hasAlert(viewer.getUniqueId())
                        ? C_ON + "\u5f00" : C_OFF + "\u5173")));
        inv.setItem(12, named(Material.EMERALD, C_ACTION + "\u5237\u65b0"));
        inv.setItem(13, named(Material.STAINED_GLASS_PANE, C_ACTION + "\u878d\u65ad\u72b6\u6001\uff1a"
                + (manager.getMainHandler().isFused()
                        ? C_OFF + "\u6fc0\u6d3b\u4e2d" : C_ON + "\u6b63\u5e38")));
        List<PlayerSnapshot> players = snap.players;
        int totalPages = Math.max(1, (players.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        if (pageIdx >= totalPages) {
            pageIdx = totalPages - 1;
        }
        int start = pageIdx * PLAYERS_PER_PAGE;
        for (int k = 0; k < PLAYERS_PER_PAGE; k++) {
            int index = start + k;
            if (index >= players.size()) {
                continue; // 留空气，不再塞黑玻璃
            }
            PlayerSnapshot ps = players.get(index);
            ItemStack item = head(ps.name);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((ps.totalVl >= 30L ? C_OFF : ps.totalVl > 0L ? C_ACTION : ChatColor.AQUA)
                    + ps.name + (ps.uuid.equals(viewer.getUniqueId())
                            ? C_DIM + " \u00a77(\u81ea\u5df1)" : ""));
            List<String> lore = new ArrayList<String>();
            lore.add(C_INFO + "\u5ef6\u8fdf\uff1a" + pingColor(ps.ping));
            lore.add(C_INFO + "\u8fde\u70b9\uff1a" + C_VAL + ps.attackSize);
            lore.add(C_INFO + "timeTest\uff1a" + (ps.timeTest >= 50 ? C_OFF : C_VAL) + ps.timeTest);
            lore.add(C_INFO + "\u8fdd\u89c4\uff1a" + violationColor(ps.totalVl));
            if (ps.lastFlagDisplay != null) {
                lore.add(C_INFO + "\u6700\u8fd1\uff1a" + C_VAL + ps.lastFlagDisplay);
            }
            lore.add("");
            lore.add(C_DIM + "\u70b9\u51fb\u67e5\u770b\u8be6\u60c5");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(18 + k, item);
        }
        inv.setItem(46, named(Material.PAPER, C_VAL + "\u7b2c" + (pageIdx + 1) + "/" + totalPages
                + "\u9875"));
        if (pageIdx > 0) {
            inv.setItem(45, named(Material.ARROW, C_ACTION + "\u2190 \u4e0a\u4e00\u9875"));
        }
        if (pageIdx < totalPages - 1) {
            inv.setItem(47, named(Material.ARROW, C_ACTION + "\u4e0b\u4e00\u9875 \u2192"));
        }
        inv.setItem(52, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.PLAYERS;
        state.from = Page.MENU;
        state.playerPage = pageIdx;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openDetail(Player viewer, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            viewer.sendMessage(cfg.prefix() + "&c\u73a9\u5bb6\u5df2\u4e0d\u5728\u7ebf\u3002");
            openPlayers(viewer, 0);
            return;
        }
        PlayerData data = manager.getDataManager().get(targetId);
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_DETAIL);
        fillBorder(inv, new int[] { 48 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        long total = totalViolations(data);
        ItemStack headItem = head(target.getName());
        ItemMeta headMeta = headItem.getItemMeta();
        headMeta.setDisplayName((total >= 30L ? C_OFF : total > 0L ? C_ACTION : ChatColor.AQUA)
                + target.getName());
        List<String> headLore = new ArrayList<String>();
        headLore.add(C_INFO + "\u8fdd\u89c4\uff1a" + violationColor(total));
        String recent = recentFlag(data);
        if (recent != null) {
            headLore.add(C_INFO + "\u6700\u8fd1\uff1a" + C_VAL + recent);
        }
        headMeta.setLore(headLore);
        headItem.setItemMeta(headMeta);
        inv.setItem(4, headItem);
        double rtt = data.transaction(manager).rttMs();
        inv.setItem(10, infoItem(Material.PAPER, C_TITLE + "\u4fe1\u606f",
                C_INFO + "\u5ef6\u8fdf\uff1a" + pingColor(data.ping),
                C_INFO + "\u4e8b\u52a1RTT\uff1a" + (rtt > 0.0D
                        ? C_VAL + String.format("%.0fms", rtt) : C_INFO + "\u672a\u6fc0\u6d3b"),
                C_INFO + "\u8fde\u70b9(1s)\uff1a" + C_VAL + data.attackTimes.size(),
                C_INFO + "\u901f\u5ea6\u7b49\u7ea7\uff1a" + C_VAL + data.speedLevel,
                C_INFO + "\u8df3\u8dc3\u7b49\u7ea7\uff1a" + C_VAL + data.jumpLevel,
                C_INFO + "\u521b\u9020\uff1a" + C_VAL + data.creative));
        inv.setItem(11, infoItem(Material.MAP, C_TITLE + "\u79fb\u52a8",
                C_INFO + "\u7ad6\u5411\u901f\u5ea6\uff1a" + C_VAL + MathUtilRound(data.movement.motionY),
                C_INFO + "\u6c34\u5e73\u8ddd\u79bb\uff1a" + C_VAL + MathUtilRound(data.movement.distanceXZ),
                C_INFO + "\u7a7a\u4e2dtick\uff1a" + C_VAL + data.movement.airTicks,
                C_INFO + "\u5730\u9762tick\uff1a" + C_VAL + data.movement.groundTicks,
                C_INFO + "\u8df3\u8d77\uff1a" + C_VAL + data.movement.jumpedThisTick));
        inv.setItem(12, infoItem(Material.BOOK, C_TITLE + "\u72b6\u6001",
                C_INFO + "\u68af\u5b50\uff1a" + C_VAL + data.movement.ladderTicks + "t",
                C_INFO + "\u6db2\u4f53\uff1a" + C_VAL + data.movement.nearLiquidTicks + "t",
                C_INFO + "\u86db\u7f51\uff1a" + C_VAL + data.movement.inWebTicks + "t",
                C_INFO + "\u53f2\u83b1\u59c6\uff1a" + C_VAL + data.movement.slimeTicks + "t",
                C_INFO + "\u88ab\u56f0\uff1a" + C_VAL + data.movement.boxedIn,
                C_INFO + "\u7075\u9b42\u6c99\uff1a" + C_VAL + data.blockOnSoulSand,
                C_INFO + "\u7528\u7269\u54c1\uff1a" + C_VAL + data.usingItem));
        inv.setItem(13, infoItem(Material.COMPASS, C_TITLE + "\u65cb\u8f6c",
                C_INFO + "\u504f\u822a\uff1a" + C_VAL + MathUtilRound(data.lastYaw, 1),
                C_INFO + "\u4fef\u4ef0\uff1a" + C_VAL + MathUtilRound(data.lastPitch, 1),
                C_INFO + "\u504f\u822a\u53d8\u5316\uff1a" + C_VAL + MathUtilRound(data.lastYawDelta, 1),
                C_INFO + "\u6709\u65cb\u8f6c\uff1a" + C_VAL + data.hasRotation));
        java.util.List<CheckType> sorted = new ArrayList<CheckType>();
        for (CheckType type : CheckType.values()) {
            sorted.add(type);
        }
        sorted.sort((a, b) -> Long.compare(data.getViolations(b), data.getViolations(a)));
        for (int i = 0; i < sorted.size(); i++) {
            CheckType type = sorted.get(i);
            long vl = data.getViolations(type);
            int kickAt = cfg.i("checks." + type.getConfigPath() + ".kick-at-vl", 20);
            ItemStack item = new ItemStack(Material.INK_SACK, 1, dye(vl, kickAt));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((vl >= kickAt ? C_OFF : vl > 0 ? C_ACTION : C_INFO)
                    + type.getDisplay());
            List<String> lore = new ArrayList<String>();
            lore.add(C_INFO + "\u8fdd\u89c4\uff1a" + C_VAL + vl + C_INFO + " / " + kickAt
                    + "  " + progressBar(vl, kickAt));
            lore.add(C_INFO + "\u542f\u7528\uff1a" + (cfg.enabled(type.getConfigPath())
                    ? C_ON + "\u662f" : C_OFF + "\u5426"));
            long last = data.getLastFlagTime(type);
            if (last > 0L) {
                long ago = (System.currentTimeMillis() - last) / 1000L;
                lore.add(C_INFO + "\u6700\u8fd1\u89e6\u53d1\uff1a" + C_VAL + ago + "s\u524d");
            }
            lore.add("");
            lore.add(C_DIM + "\u5de6\u952e\u8fdb\u5165\u5b50\u68c0\u6d4b\uff0cShift+\u5de6\u952e\u6253\u5f00\u8bbe\u7f6e");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(18 + i, item);
        }
        GuiState prevState = states.get(viewer.getUniqueId());
        long confirmAt = prevState != null ? prevState.confirmAt : 0L;
        String confirmAction = prevState != null ? prevState.confirmAction : null;
        boolean armed = confirmAt > 0L && System.currentTimeMillis() - confirmAt < CONFIRM_MS;
        inv.setItem(45, named(Material.BOOK, C_ACTION + "\u68c0\u6d4b"));
        inv.setItem(46, named(Material.EYE_OF_ENDER, C_ACTION + "\u5207\u6362\u544a\u8b66\uff1a"
                + (manager.getMainHandler().hasAlert(target.getUniqueId())
                        ? C_ON + "\u5f00" : C_OFF + "\u5173")));
        long remain = CONFIRM_MS - (System.currentTimeMillis() - confirmAt);
        if (armed && "clear".equals(confirmAction)) {
            inv.setItem(47, named(Material.GLOWSTONE_DUST, C_OFF
                    + "\u518d\u6b21\u70b9\u51fb\u786e\u8ba4\u6e05\u7a7a\u8fdd\u89c4!"
                    + "\u00a77(" + Math.max(1, remain / 1000L) + "s)"));
        } else {
            inv.setItem(47, named(Material.GLOWSTONE_DUST, C_ACTION + "\u6e05\u7a7a\u8fdd\u89c4"));
        }
        if (armed && "kick".equals(confirmAction)) {
            inv.setItem(48, named(Material.BARRIER,
                    C_OFF + "\u518d\u6b21\u70b9\u51fb\u786e\u8ba4\u8e22\u51fa " + target.getName() + "!"
                    + "\u00a77(" + Math.max(1, remain / 1000L) + "s)"));
        } else {
            inv.setItem(48, named(Material.BARRIER, C_OFF + "\u8e22\u51fa " + target.getName()));
        }
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.DETAIL;
        state.from = prevState != null ? prevState.from : Page.PLAYERS;
        state.target = targetId;
        state.playerPage = prevState != null ? prevState.playerPage : 0;
        state.confirmAt = confirmAt;
        state.confirmAction = confirmAction;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openChecks(Player viewer) {
        Snapshot snap = snapshot();
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_CHECKS);
        fillBorder(inv, new int[] { 45, 47, 52 });
        CheckType[] types = CheckType.values();
        for (int i = 0; i < types.length; i++) {
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, checkItem(types[i], snap.serverVl));
        }
        int enabled = 0;
        long total = 0L;
        for (CheckType type : types) {
            if (cfg.enabled(type.getConfigPath())) {
                enabled++;
            }
            Long vl = snap.serverVl.get(type);
            total += vl == null ? 0L : vl.longValue();
        }
        List<String> statsLore = new ArrayList<String>();
        statsLore.add(C_INFO + "\u542f\u7528\u68c0\u6d4b\uff1a" + C_VAL + enabled + " / "
                + types.length);
        statsLore.add(C_INFO + "\u5168\u670d\u8fdd\u89c4\uff1a" + violationColor(total));
        statsLore.add(C_INFO + "\u878d\u65ad\uff1a"
                + (manager.getMainHandler().isFused() ? C_OFF + "\u6fc0\u6d3b\u4e2d"
                        : C_ON + "\u6b63\u5e38"));
        statsLore.add("");
        statsLore.add(C_DIM + "\u5de6\u952e\u8fdb\u5165\u5b50\u68c0\u6d4b\uff0cShift+\u5de6\u952e\u6253\u5f00\u8bbe\u7f6e");
        ItemStack stats = new ItemStack(Material.NETHER_STAR);
        ItemMeta statsMeta = stats.getItemMeta();
        statsMeta.setDisplayName(C_TITLE + "\u5168\u670f\u7edf\u8ba1");
        statsMeta.setLore(statsLore);
        stats.setItemMeta(statsMeta);
        inv.setItem(45, stats);
        inv.setItem(47, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        inv.setItem(52, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
            state.from = prev.page == Page.DETAIL ? Page.DETAIL : Page.MENU;
        } else {
            state.from = Page.MENU;
        }
        state.page = Page.CHECKS;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openCheckSubs(Player viewer, CheckType type) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SUBS + " \u00a77\u2503 " + type.getDisplay());
        fillBorder(inv, new int[] { 45, 46, 47, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        boolean mainEnabled = cfg.enabled(type.getConfigPath());
        ItemStack mainToggle = new ItemStack(Material.STAINED_CLAY, 1, mainEnabled ? (short) 5 : (short) 14);
        ItemMeta mainMeta = mainToggle.getItemMeta();
        mainMeta.setDisplayName((mainEnabled ? C_ON : C_OFF) + "\u68c0\u6d4b\u5f00\u5173"
                + (mainEnabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> mainLore = new ArrayList<String>();
        mainLore.add(C_INFO + "\u5f53\u524d\uff1a" + (mainEnabled ? C_ON + "\u5f00"
                : C_OFF + "\u5173"));
        mainLore.add("");
        mainLore.add(C_DIM + "\u70b9\u51fb\u5207\u6362\u6574\u4e2a\u68c0\u6d4b");
        mainMeta.setLore(mainLore);
        mainToggle.setItemMeta(mainMeta);
        inv.setItem(47, mainToggle);
        java.util.List<String> subs = subsOf(type);
        if (!subs.isEmpty()) {
            for (int i = 0; i < subs.size() && i < 35; i++) {
                String sub = subs.get(i);
                boolean enabled = cfg.raw().getBoolean(
                        "checks." + type.getConfigPath() + "." + sub + ".enabled", true);
                ItemStack item = new ItemStack(Material.INK_SACK, 1, enabled ? GREEN : GRAY);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName((enabled ? C_ON : C_OFF) + sub
                        + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
                List<String> lore = new ArrayList<String>();
                lore.add(C_INFO + "\u89e6\u53d1\u9608\u503c\uff1a" + C_VAL
                        + cfg.i("checks." + type.getConfigPath() + "." + sub + ".vl-before-flag", 5));
                lore.add("");
                lore.add(C_DIM + "\u70b9\u51fb" + (enabled ? "\u7981\u7528" : "\u542f\u7528"));
                meta.setLore(lore);
                item.setItemMeta(meta);
                inv.setItem(10 + i, item);
            }
            inv.setItem(45, named(Material.EMERALD, C_ON + "\u5168\u90e8\u542f\u7528"));
            inv.setItem(46, named(Material.REDSTONE, C_OFF + "\u5168\u90e8\u7981\u7528"));
        }
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.CHECK_SUBS;
        state.from = prev != null && prev.page == Page.DETAIL ? Page.DETAIL : Page.CHECKS;
        state.type = type;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openCheckSettings(Player viewer, CheckType type) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SETTINGS + " \u00a77\u2503 " + type.getDisplay());
        fillBorder(inv, new int[] { 0, 9, 10, 11, 12, 13, 45, 46, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        String base = "checks." + type.getConfigPath() + ".";
        boolean enabled = cfg.enabled(type.getConfigPath());
        ItemStack toggle = new ItemStack(Material.STAINED_CLAY, 1, enabled ? (short) 5 : (short) 14);
        ItemMeta toggleMeta = toggle.getItemMeta();
        toggleMeta.setDisplayName((enabled ? C_ON : C_OFF) + "\u68c0\u6d4b\u5f00\u5173"
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> toggleLore = new ArrayList<String>();
        toggleLore.add(C_INFO + "\u5f53\u524d\uff1a" + (enabled ? C_ON + "\u5f00"
                : C_OFF + "\u5173"));
        toggleLore.add("");
        toggleLore.add(C_DIM + "\u70b9\u51fb\u5207\u6362");
        toggleMeta.setLore(toggleLore);
        toggle.setItemMeta(toggleMeta);
        inv.setItem(9, toggle);
        inv.setItem(10, editItem(base + "kick-at-vl", "\u8e22\u51fa\u9608\u503c (VL)",
                String.valueOf(cfg.i(base + "kick-at-vl", 20))));
        inv.setItem(11, editItem(base + "vl-before-flag", "\u89e6\u53d1\u9608\u503c (VL)",
                String.valueOf(cfg.i(base + "vl-before-flag", 5))));
        inv.setItem(12, editItem(base + "kick-message", "\u8e22\u51fa\u63d0\u793a",
                cfg.s(base + "kick-message", "&cKicked by YCBR")));
        java.util.List<String> subList = subsOf(type);
        if (!subList.isEmpty()) {
            inv.setItem(13, named(Material.COMPASS, ChatColor.AQUA + "\u5b50\u68c0\u6d4b\u7ba1\u7406 ("
                    + subList.size() + "\u4e2a)"));
        }
        List<String> infoLore = new ArrayList<String>();
        long total = 0L;
        for (PlayerData data : manager.getDataManager().all()) {
            total += data.getViolations(type);
        }
        infoLore.add(C_INFO + "\u5168\u670d\u8fdd\u89c4\uff1a" + C_VAL + total);
        infoLore.add(C_INFO + "\u914d\u7f6e\u8def\u5f84\uff1a" + C_VAL + base);
        infoLore.add("");
        infoLore.add(C_DIM + "\u6570\u503c\u9879\u70b9\u51fb\u540e\u5728\u804a\u5929\u8f93\u5165\u65b0\u503c");
        infoLore.add(C_DIM + "\u8f93\u5165 cancel \u53d6\u6d88");
        inv.setItem(18, infoItem(Material.PAPER, C_TITLE + "\u8bf4\u660e", infoLore.toArray(new String[0])));
        if (!subList.isEmpty()) {
            inv.setItem(45, named(Material.EMERALD, C_ON + "\u5168\u90e8\u542f\u7528\u5b50\u68c0\u6d4b"));
            inv.setItem(46, named(Material.REDSTONE, C_OFF + "\u5168\u90e8\u7981\u7528\u5b50\u68c0\u6d4b"));
        }
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.CHECK_SETTINGS;
        state.from = prev != null && prev.page == Page.DETAIL ? Page.DETAIL : Page.CHECKS;
        state.type = type;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openLogs(Player viewer, int pageIdx) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_LOGS);
        fillBorder(inv, new int[] { 45, 46, 47, 48, 52 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        java.util.List<String> logs = manager.getMainHandler().getViolationLog();
        int perPage = 27;
        int totalPages = Math.max(1, (logs.size() + perPage - 1) / perPage);
        if (pageIdx >= totalPages) {
            pageIdx = totalPages - 1;
        }
        for (int k = 0; k < perPage; k++) {
            int index = pageIdx * perPage + k;
            if (index >= logs.size()) {
                continue;
            }
            String line = logs.get(index);
            inv.setItem(18 + k, named(Material.PAPER, logColor(line) + line));
        }
        inv.setItem(46, named(Material.PAPER, C_VAL + "\u7b2c" + (pageIdx + 1) + "/" + totalPages
                + "\u9875"));
        if (pageIdx > 0) {
            inv.setItem(45, named(Material.ARROW, C_ON + "\u2190 \u4e0a\u4e00\u9875"));
        }
        if (pageIdx < totalPages - 1) {
            inv.setItem(47, named(Material.ARROW, C_ON + "\u4e0b\u4e00\u9875 \u2192"));
        }
        inv.setItem(48, named(Material.REDSTONE, C_OFF + "\u6e05\u7a7a\u65e5\u5fd7"));
        inv.setItem(52, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.LOGS;
        state.from = Page.MENU;
        state.logPage = pageIdx;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openDdos(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_DDOS);
        fillBorder(inv, new int[] { 0, 45, 46, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        boolean enabled = cfg.raw().getBoolean("settings.ddos.enabled", true);
        ItemStack toggle = new ItemStack(Material.STAINED_CLAY, 1, enabled ? (short) 5 : (short) 14);
        ItemMeta toggleMeta = toggle.getItemMeta();
        toggleMeta.setDisplayName((enabled ? C_ON : C_OFF) + "DDOS \u9632\u62a4\u5f00\u5173"
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> toggleLore = new ArrayList<String>();
        toggleLore.add(C_INFO + "\u5f53\u524d\uff1a" + (enabled ? C_ON + "\u5f00"
                : C_OFF + "\u5173"));
        toggleLore.add("");
        toggleLore.add(C_DIM + "\u70b9\u51fb\u5207\u6362");
        toggleMeta.setLore(toggleLore);
        toggle.setItemMeta(toggleMeta);
        inv.setItem(9, toggle);
        for (int i = 0; i < DDOS_PATHS.length; i++) {
            inv.setItem(10 + i, editItem(DDOS_PATHS[i], DDOS_LABELS[i],
                    String.valueOf(cfg.i(DDOS_PATHS[i], DDOS_DEFAULTS[i]))));
        }
        inv.setItem(27, editItem("settings.ddos.kick-rate-limit", "\u9650\u901f\u8e22\u51fa\u63d0\u793a",
                cfg.s("settings.ddos.kick-rate-limit", "&c\u767b\u5f55\u5c1d\u8bd5\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7b49\u5f85\u4e00\u5206\u949f\u540e\u518d\u8bd5")));
        inv.setItem(28, editItem("settings.ddos.kick-invalid", "\u53c2\u6570\u5f02\u5e38\u8e22\u51fa\u63d0\u793a",
                cfg.s("settings.ddos.kick-invalid", "&c\u8fde\u63a5\u53c2\u6570\u5f02\u5e38")));
        List<String> stats = new ArrayList<String>();
        stats.add(C_INFO + "\u5f02\u5e38\u5305/\u8fde\u63a5\uff1a" + C_VAL
                + manager.getDdosGuard().getViolations());
        stats.add(C_INFO + "\u8d85\u65f6\u5173\u95ed\u8fde\u63a5\uff1a" + C_VAL
                + manager.getDdosGuard().getClosedConnections());
        stats.add(C_INFO + "\u9650\u901f\u62e6\u622a\uff1a" + C_VAL
                + manager.getDdosGuard().getRateBlocks());
        stats.add(C_INFO + "\u72b6\u6001\u8bf7\u6c42(ping)\uff1a" + C_VAL
                + manager.getDdosGuard().getStatusPings());
        stats.add(C_INFO + "\u5f53\u524d\u8fde\u63a5\uff1a" + C_VAL
                + manager.getDdosGuard().getCurrentConnections());
        stats.add("");
        stats.add(C_DIM + "\u6253\u5f00\u9875\u9762\u65f6\u5237\u65b0");
        inv.setItem(29, infoItem(Material.PAPER, C_TITLE + "\u5b9e\u65f6\u7edf\u8ba1",
                stats.toArray(new String[0])));
        inv.setItem(45, infoItem(Material.BOOK, C_TITLE + "\u8bf4\u660e",
                C_DIM + "\u6570\u503c\u9879\u70b9\u51fb\u540e\u5728\u804a\u5929\u8f93\u5165\u65b0\u503c",
                C_DIM + "\u8f93\u5165 cancel \u53d6\u6d88",
                C_DIM + "\u8d85\u65f6\u503c\u4e3a\u8fde\u63a5\u5728\u8be5\u72b6\u6001\u505c\u7559\u7684\u6700\u957f\u79d2\u6570"));
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.DDOS;
        state.from = Page.MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openFusion(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_FUSION);
        fillBorder(inv, new int[] { 0, 45, 46, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        boolean fusionEnabled = cfg.raw().getBoolean("checks.improbable.enabled", false);
        boolean fuseActive = manager.getMainHandler().isFused();
        int shortThr = cfg.i("checks.improbable.short-threshold", 6);
        int longThr = cfg.i("checks.improbable.long-threshold", 30);
        int minCat = cfg.i("checks.improbable.min-categories", 2);
        List<String> headLore = new ArrayList<String>();
        headLore.add(C_INFO + "\u878d\u5408\u68c0\u6d4b\uff1a" + (fusionEnabled ? C_ON + "\u5f00\u542f"
                : C_OFF + "\u5173\u95ed")
                + C_INFO + "  \u5168\u5c40\u878d\u65ad\uff1a" + (fuseActive ? C_OFF + "\u6fc0\u6d3b\u4e2d"
                        : C_ON + "\u6b63\u5e38"));
        headLore.add(C_INFO + "\u9608\u503c\uff1a\u77ed\u7a97" + C_VAL + shortThr + "t"
                + C_INFO + " / \u957f\u7a97" + C_VAL + longThr + "t"
                + C_INFO + " / \u8986\u76d6" + C_VAL + minCat + C_INFO + "\u7c7b");
        headLore.add("");
        headLore.add(C_DIM + "\u5404\u68c0\u6d4b\u5c0f\u8fdd\u89c4\u4f53\u5f81\u5192\u5728\u65f6\u5b9e\u65f6\u8ba1\u6570");
        headLore.add(C_DIM + "\u77ed\u7a97\u2191\u4e3a\u88ab\u6b64\u68c0\u6d4b\u5c0f\u8fdd\u89c4\u9ad8\u9891\u89e6\u53d1\u4e2d");
        inv.setItem(4, infoItem(Material.WATCH, C_TITLE + "\u878d\u5408\u6982\u51b5",
                headLore.toArray(new String[0])));
        List<FusionEntry> entries = fusionData();
        int shown = Math.min(entries.size(), 30);
        for (int i = 0; i < shown; i++) {
            FusionEntry e = entries.get(i);
            int slot = i < 15 ? 9 + i : 27 + (i - 15);
            boolean anyHot = (e.combatS >= shortThr && e.combatL >= longThr)
                    || (e.moveS >= shortThr && e.moveL >= longThr)
                    || (e.protoS >= shortThr && e.protoL >= longThr);
            ItemStack item = new ItemStack(Material.INK_SACK, 1, anyHot ? RED : YELLOW);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((anyHot ? C_OFF : C_ACTION) + e.name
                    + (anyHot ? " \u00a77(\u4e34\u754c)" : ""));
            List<String> lore = new ArrayList<String>();
            lore.add(catLine("Combat", e.combatS, e.combatL, shortThr, longThr));
            lore.add(catLine("Movement", e.moveS, e.moveL, shortThr, longThr));
            lore.add(catLine("Protocol", e.protoS, e.protoL, shortThr, longThr));
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
        if (shown == 0) {
            List<String> emptyLore = new ArrayList<String>();
            emptyLore.add(C_INFO + "\u6682\u65e0\u5c0f\u8fdd\u89c4\u8bb0\u5f55");
            emptyLore.add("");
            emptyLore.add(C_DIM + "\u5f00\u542f checks.improbable \u540e\uff0c\u5404\u68c0\u6d4b\u7684"
                    + "\u4e9a\u9608\u503c\u5c0f\u8fdd\u89c4\u5c06\u5728\u6b64\u5c55\u793a");
            inv.setItem(22, infoItem(Material.PAPER, C_TITLE + "\u65e0\u6570\u636e",
                    emptyLore.toArray(new String[0])));
        }
        inv.setItem(45, infoItem(Material.BOOK, C_TITLE + "\u8bf4\u660e",
                C_DIM + "Improbable \u878d\u5408\uff1a\u5404\u68c0\u6d4b\u7684\u5c0f\u8fdd\u89c4\u2192\u7edf\u4e00\u9891\u7387\u6876",
                C_DIM + "\u77ed\u7a97 + \u957f\u7a97\u540c\u65f6\u8d85\u9608\u4e14\u8986\u76d6 "
                        + minCat + "\u4e2a\u7c7b\u522b\u2192 \u5347 VL",
                C_DIM + "\u5148\u5f00\u542f checks.improbable.enabled \u4f53\u9a8c"));
        inv.setItem(46, named(Material.EMERALD, C_ON + "\u5f00\u542f\u878d\u5408\u68c0\u6d4b"));
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.FUSION;
        state.from = Page.MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private static String catLine(String label, int s, int l, int shortThr, int longThr) {
        ChatColor color = (s >= shortThr && l >= longThr) ? C_OFF : C_INFO;
        return C_INFO + label + ": " + color + s + "/" + l
                + C_INFO + " (\u9608 " + shortThr + "/" + longThr + ")";
    }

    private void openOpMenu(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_OP);
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, glass());
        }
        int suspects = 0;
        Snapshot snap = snapshot();
        for (PlayerSnapshot ps : snap.players) {
            if (ps.timeTest >= 50) {
                suspects++;
            }
        }
        List<String> manualLore = new ArrayList<String>();
        manualLore.add(C_INFO + "timeTest\u226550\uff1a" + C_VAL + suspects
                + C_INFO + " \u4eba");
        manualLore.add("");
        manualLore.add(C_DIM + "\u70b9\u51fb\u67e5\u770b\u53ef\u7591\u73a9\u5bb6\uff0c\u4f20\u9001\u9690\u8eab\u89c2\u5bdf");
        inv.setItem(11, infoItem(Material.DIAMOND_SWORD, C_TITLE + "\u4eba\u5de5\u68c0\u6d4b",
                manualLore.toArray(new String[0])));
        List<String> vlLore = new ArrayList<String>();
        vlLore.add(C_INFO + "\u67e5\u770b\u5728\u7ebf\u73a9\u5bb6\u7684\u8fdd\u89c4\u503c");
        vlLore.add("");
        vlLore.add(C_DIM + "\u70b9\u51fb\u67e5\u770b\u73a9\u5bb6\u5404\u68c0\u6d4b VL");
        inv.setItem(15, infoItem(Material.BOOK, C_TITLE + "\u67e5\u770b\u73a9\u5bb6VL",
                vlLore.toArray(new String[0])));

        GuiState state = new GuiState();
        state.page = Page.OP_MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openOpManual(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_OP_MANUAL);
        fillBorder(inv, new int[] { 0, 45, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        List<PlayerSnapshot> suspects = new ArrayList<PlayerSnapshot>();
        Snapshot snap = snapshot();
        for (PlayerSnapshot ps : snap.players) {
            if (ps.timeTest >= 50) {
                suspects.add(ps);
            }
        }
        for (int i = 0; i < suspects.size() && i < 35; i++) {
            PlayerSnapshot ps = suspects.get(i);
            ItemStack item = head(ps.name);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(C_OFF + ps.name);
            List<String> lore = new ArrayList<String>();
            lore.add(C_INFO + "timeTest\uff1a" + C_VAL + ps.timeTest);
            lore.add(C_INFO + "\u5ef6\u8fdf\uff1a" + pingColor(ps.ping));
            lore.add("");
            lore.add(C_DIM + "\u70b9\u51fb\u4f20\u9001\u5230\u73a9\u5bb6\u8eab\u8fb9\uff08\u9690\u8eab\uff09");
            meta.setLore(lore);
            item.setItemMeta(meta);
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, item);
        }
        if (manager.getGhostManager().isGhost(viewer.getUniqueId())) {
            inv.setItem(45, named(Material.REDSTONE, C_OFF + "\u9000\u51fa\u9690\u8eab\u6a21\u5f0f"));
        } else {
            inv.setItem(45, named(Material.PAPER, C_DIM + "\u5f53\u524d\u672a\u5728\u9690\u8eab\u6a21\u5f0f"));
        }
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.OP_MANUAL;
        state.from = Page.OP_MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openOpVl(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_OP_VL);
        fillBorder(inv, new int[] { 0, 53 });
        inv.setItem(0, named(Material.ARROW, C_ACTION + "\u2190 \u8fd4\u56de"));
        Snapshot snap = snapshot();
        for (int i = 0; i < snap.players.size() && i < 35; i++) {
            PlayerSnapshot ps = snap.players.get(i);
            ItemStack item = head(ps.name);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((ps.totalVl >= 30L ? C_OFF : ps.totalVl > 0L ? C_ACTION : ChatColor.AQUA)
                    + ps.name);
            List<String> lore = new ArrayList<String>();
            lore.add(C_INFO + "\u8fdd\u89c4\uff1a" + violationColor(ps.totalVl));
            lore.add(C_INFO + "\u5ef6\u8fdf\uff1a" + pingColor(ps.ping));
            lore.add("");
            lore.add(C_DIM + "\u70b9\u51fb\u67e5\u770b\u5404\u68c0\u6d4b\u8be6\u60c5");
            meta.setLore(lore);
            item.setItemMeta(meta);
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, item);
        }
        inv.setItem(53, named(Material.BARRIER, C_OFF + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.OP_VL;
        state.from = Page.OP_MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void sendVlDetail(Player viewer, PlayerData data) {
        Player target = Bukkit.getPlayer(data.getUuid());
        if (target == null) {
            viewer.sendMessage(cfg.prefix() + "&c\u73a9\u5bb6\u5df2\u4e0b\u7ebf\u3002");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (CheckType type : CheckType.values()) {
            long vl = data.getViolations(type);
            if (vl > 0) {
                if (sb.length() > 0) {
                    sb.append("&7 | ");
                }
                sb.append("&f").append(type.getDisplay()).append("=&e").append(vl);
            }
        }
        viewer.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                cfg.prefix() + "&b" + target.getName() + " &7VL\u8be6\u660e: "
                        + (sb.length() == 0 ? "&7\u5168\u90e8\u4e3a\u96f6" : sb.toString())));
    }

    // =====================================================================
    // 点击分发
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player viewer = (Player) event.getWhoClicked();
        // 会话内二次权限复核（权限被撤销/名单被改后立即失效）
        if (!viewer.hasPermission("ycbr.admin") && !manager.isYcbrOp(viewer.getName())) {
            close(viewer);
            return;
        }
        GuiState state = states.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        String title = event.getView().getTitle();
        if (!TITLE_MENU.equals(title) && !TITLE_PLAYERS.equals(title) && !TITLE_DETAIL.equals(title)
                && !TITLE_CHECKS.equals(title) && !title.startsWith(TITLE_SUBS)
                && !title.startsWith(TITLE_SETTINGS) && !TITLE_LOGS.equals(title)
                && !TITLE_DDOS.equals(title) && !TITLE_FUSION.equals(title)
                && !TITLE_OP.equals(title) && !TITLE_OP_MANUAL.equals(title)
                && !TITLE_OP_VL.equals(title)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }
        boolean shift = event.isShiftClick();
        switch (state.page) {
            case MENU:
                if (slot == 9) {
                    openPlayers(viewer, 0);
                } else if (slot == 11) {
                    openChecks(viewer);
                } else if (slot == 13) {
                    openLogs(viewer, 0);
                } else if (slot == 15) {
                    openDdos(viewer);
                } else if (slot == 17) {
                    openFusion(viewer);
                }
                return;
            case PLAYERS:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 9) {
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061\u914d\u7f6e\u5df2\u91cd\u8f7d\u3002");
                    openPlayers(viewer, state.playerPage);
                    return;
                }
                if (slot == 10) {
                    openChecks(viewer);
                    return;
                }
                if (slot == 11) {
                    boolean enabled = manager.getMainHandler().toggleAlert(viewer.getUniqueId());
                    viewer.sendMessage(cfg.prefix() + "&\u0061\u544a\u8b66\u5df2" + (enabled ? "\u5f00\u542f" : "\u5173\u95ed")
                            + "\u3002");
                    openPlayers(viewer, state.playerPage);
                    return;
                }
                if (slot == 12) {
                    openPlayers(viewer, state.playerPage);
                    return;
                }
                if (slot == 45 && state.playerPage > 0) {
                    openPlayers(viewer, state.playerPage - 1);
                    return;
                }
                if (slot == 47) {
                    openPlayers(viewer, state.playerPage + 1);
                    return;
                }
                if (slot >= 18 && slot < 18 + PLAYERS_PER_PAGE) {
                    Snapshot snap = snapshot();
                    int index = state.playerPage * PLAYERS_PER_PAGE + (slot - 18);
                    if (index < snap.players.size()) {
                        openDetail(viewer, snap.players.get(index).uuid);
                    }
                }
                return;
            case DETAIL:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot == 45) {
                    state.confirmAt = 0L;
                    state.confirmAction = null;
                    openChecks(viewer);
                    return;
                }
                if (slot == 46) {
                    Player target = Bukkit.getPlayer(state.target);
                    if (target != null) {
                        boolean enabled = manager.getMainHandler().toggleAlert(target.getUniqueId());
                        viewer.sendMessage(cfg.prefix() + "&\u0061" + target.getName() + " \u7684\u544a\u8b66\u5df2"
                                + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    }
                    state.confirmAt = 0L;
                    state.confirmAction = null;
                    openDetail(viewer, state.target);
                    return;
                }
                if (slot == 47) {
                    confirmOrClear(viewer, state);
                    return;
                }
                if (slot == 48) {
                    confirmOrKick(viewer, state);
                    return;
                }
                if (slot >= 18 && slot < 18 + CheckType.values().length) {
                    CheckType type = CheckType.values()[slot - 18];
                    if (shift || subsOf(type).isEmpty()) {
                        openCheckSettings(viewer, type);
                    } else {
                        openCheckSubs(viewer, type);
                    }
                    return;
                }
                return;
            case CHECKS:
                if (slot == 52) {
                    close(viewer);
                    return;
                }
                if (slot == 47) {
                    back(viewer, state);
                    return;
                }
                int checkSlot = slot < 19 ? slot - 9 : slot - 27 + 10;
                if ((slot >= 9 && slot < 19 || slot >= 27 && slot < 36) && checkSlot >= 0
                        && checkSlot < CheckType.values().length) {
                    CheckType type = CheckType.values()[checkSlot];
                    if (shift || subsOf(type).isEmpty()) {
                        openCheckSettings(viewer, type);
                        return;
                    }
                    openCheckSubs(viewer, type);
                    return;
                }
                return;
            case CHECK_SETTINGS:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                String settingsBase = "checks." + state.type.getConfigPath() + ".";
                if (slot == 9) {
                    boolean enabled = !cfg.enabled(state.type.getConfigPath());
                    cfg.set(settingsBase + "enabled", enabled);
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061" + state.type.getDisplay() + " \u5df2"
                            + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    openCheckSettings(viewer, state.type);
                    return;
                }
                if (slot == 10) {
                    beginEdit(viewer, state, settingsBase + "kick-at-vl", "\u8e22\u51fa\u9608\u503c");
                    return;
                }
                if (slot == 11) {
                    beginEdit(viewer, state, settingsBase + "vl-before-flag", "\u89e6\u53d1\u9608\u503c");
                    return;
                }
                if (slot == 12) {
                    beginEdit(viewer, state, settingsBase + "kick-message", "\u8e22\u51fa\u63d0\u793a");
                    return;
                }
                if (slot == 13 && !subsOf(state.type).isEmpty()) {
                    openCheckSubs(viewer, state.type);
                    return;
                }
                if (slot == 45 || slot == 46) {
                    java.util.List<String> subList = subsOf(state.type);
                    if (!subList.isEmpty()) {
                        boolean enable = slot == 45;
                        for (String sub : subList) {
                            cfg.set(settingsBase + sub + ".enabled", enable);
                        }
                        cfg.save();
                        manager.reload();
                        viewer.sendMessage(cfg.prefix() + "&\u0061" + state.type.getDisplay() + " \u5df2"
                                + (enable ? "\u5168\u90e8\u542f\u7528\u5b50\u68c0\u6d4b"
                                        : "\u5168\u90e8\u7981\u7528\u5b50\u68c0\u6d4b") + "\u3002");
                    }
                    openCheckSettings(viewer, state.type);
                }
                return;
            case LOGS:
                if (slot == 52) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot == 48) {
                    manager.getMainHandler().clearViolationLog();
                    viewer.sendMessage(cfg.prefix() + "&\u0061\u65e5\u5fd7\u5df2\u6e05\u7a7a\u3002");
                    openLogs(viewer, 0);
                    return;
                }
                if (slot == 45 && state.logPage > 0) {
                    openLogs(viewer, state.logPage - 1);
                } else if (slot == 47) {
                    openLogs(viewer, state.logPage + 1);
                }
                return;
            case CHECK_SUBS:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                java.util.List<String> subs = subsOf(state.type);
                if (subs.isEmpty()) {
                    return;
                }
                String base = "checks." + state.type.getConfigPath() + ".";
                if (slot == 47) {
                    boolean enabled = !cfg.enabled(state.type.getConfigPath());
                    cfg.set(base + "enabled", enabled);
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061" + state.type.getDisplay() + " \u5df2"
                            + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    openCheckSubs(viewer, state.type);
                    return;
                }
                if (slot == 45 || slot == 46) {
                    boolean enable = slot == 45;
                    for (String sub : subs) {
                        cfg.set(base + sub + ".enabled", enable);
                    }
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061" + state.type.getDisplay() + " \u5df2"
                            + (enable ? "\u5168\u90e8\u542f\u7528" : "\u5168\u90e8\u7981\u7528") + "\u3002");
                    openCheckSubs(viewer, state.type);
                    return;
                }
                if (slot >= 10 && slot - 10 < subs.size()) {
                    String sub = subs.get(slot - 10);
                    String path = base + sub + ".enabled";
                    boolean enabled = !cfg.raw().getBoolean(path, true);
                    cfg.set(path, enabled);
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061" + state.type.getDisplay() + "." + sub + " \u5df2"
                            + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    openCheckSubs(viewer, state.type);
                }
                return;
            case DDOS:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot == 9) {
                    boolean enabled = !cfg.raw().getBoolean("settings.ddos.enabled", true);
                    cfg.set("settings.ddos.enabled", enabled);
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061DDoS \u9632\u62a4\u5df2"
                            + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    openDdos(viewer);
                    return;
                }
                if (slot >= 10 && slot < 10 + DDOS_PATHS.length) {
                    beginEdit(viewer, state, DDOS_PATHS[slot - 10], DDOS_LABELS[slot - 10]);
                    return;
                }
                if (slot == 27) {
                    beginEdit(viewer, state, "settings.ddos.kick-rate-limit", "\u9650\u901f\u8e22\u51fa\u63d0\u793a");
                    return;
                }
                if (slot == 28) {
                    beginEdit(viewer, state, "settings.ddos.kick-invalid", "\u53c2\u6570\u5f02\u5e38\u8e22\u51fa\u63d0\u793a");
                    return;
                }
                return;
            case FUSION:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot == 46) {
                    boolean enabled = !cfg.raw().getBoolean("checks.improbable.enabled", false);
                    cfg.set("checks.improbable.enabled", enabled);
                    cfg.save();
                    manager.reload();
                    viewer.sendMessage(cfg.prefix() + "&\u0061\u878d\u5408\u68c0\u6d4b\u5df2"
                            + (enabled ? "\u5f00\u542f" : "\u5173\u95ed") + "\u3002");
                    openFusion(viewer);
                    return;
                }
                return;
            case OP_MENU:
                if (slot == 11) {
                    openOpManual(viewer);
                } else if (slot == 15) {
                    openOpVl(viewer);
                }
                return;
            case OP_MANUAL:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot == 45 && manager.getGhostManager().isGhost(viewer.getUniqueId())) {
                    manager.getGhostManager().deactivate(viewer);
                    openOpManual(viewer);
                    return;
                }
                if (slot >= 9 && slot < 19 || slot >= 27 && slot < 36) {
                    int index = slot < 19 ? slot - 9 : slot - 27 + 10;
                    List<PlayerSnapshot> suspects = new ArrayList<PlayerSnapshot>();
                    for (PlayerSnapshot ps : snapshot().players) {
                        if (ps.timeTest >= 50) {
                            suspects.add(ps);
                        }
                    }
                    if (index < suspects.size()) {
                        Player target = Bukkit.getPlayer(suspects.get(index).uuid);
                        if (target != null && !target.getUniqueId().equals(viewer.getUniqueId())) {
                            manager.getGhostManager().activate(viewer, target);
                            close(viewer);
                        }
                    }
                }
                return;
            case OP_VL:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    back(viewer, state);
                    return;
                }
                if (slot >= 9 && slot < 19 || slot >= 27 && slot < 36) {
                    int index = slot < 19 ? slot - 9 : slot - 27 + 10;
                    Snapshot snap = snapshot();
                    if (index < snap.players.size()) {
                        sendVlDetail(viewer, manager.getDataManager().get(snap.players.get(index).uuid));
                    }
                }
                return;
            default:
                return;
        }
    }

    /** 统一返回链：按来源页打开对应页面。 */
    private void back(Player viewer, GuiState state) {
        Page from = state.from;
        if (from == Page.PLAYERS) {
            openPlayers(viewer, state.playerPage);
        } else if (from == Page.DETAIL) {
            openDetail(viewer, state.target);
        } else if (from == Page.CHECKS) {
            openChecks(viewer);
        } else if (from == Page.CHECK_SUBS) {
            openCheckSubs(viewer, state.type);
        } else if (from == Page.CHECK_SETTINGS) {
            openCheckSettings(viewer, state.type);
        } else if (from == Page.LOGS) {
            openLogs(viewer, state.logPage);
        } else if (from == Page.DDOS) {
            openDdos(viewer);
        } else if (from == Page.FUSION) {
            openFusion(viewer);
        } else if (from == Page.OP_MANUAL) {
            openOpManual(viewer);
        } else if (from == Page.OP_VL) {
            openOpVl(viewer);
        } else {
            openMenu(viewer);
        }
    }

    /** 踢人二次确认：未确认 → 布防；已确认 → 踢出。 */
    private void confirmOrKick(Player viewer, GuiState state) {
        long now = System.currentTimeMillis();
        if (state.confirmAt > 0L && "kick".equals(state.confirmAction)
                && now - state.confirmAt < CONFIRM_MS) {
            Player target = Bukkit.getPlayer(state.target);
            if (target != null) {
                target.kickPlayer(ChatColor.RED + "\u88ab " + viewer.getName() + " \u8e22\u51fa (YCBR)");
                viewer.sendMessage(cfg.prefix() + "&\u0061\u5df2\u8e22\u51fa " + target.getName() + "\u3002");
            }
            close(viewer);
            return;
        }
        state.confirmAt = now;
        state.confirmAction = "kick";
        openDetail(viewer, state.target);
    }

    /** 清空违规二次确认。 */
    private void confirmOrClear(Player viewer, GuiState state) {
        long now = System.currentTimeMillis();
        if (state.confirmAt > 0L && "clear".equals(state.confirmAction)
                && now - state.confirmAt < CONFIRM_MS) {
            Player target = Bukkit.getPlayer(state.target);
            if (target != null) {
                PlayerData data = manager.getDataManager().get(state.target);
                for (CheckType type : CheckType.values()) {
                    data.resetViolations(type);
                }
                data.buffers.clear();
                viewer.sendMessage(cfg.prefix() + "&\u0061\u5df2\u6e05\u7a7a " + target.getName()
                        + " \u7684\u8fdd\u89c4\u3002");
            }
            state.confirmAt = 0L;
            state.confirmAction = null;
            openDetail(viewer, state.target);
            return;
        }
        state.confirmAt = now;
        state.confirmAction = "clear";
        openDetail(viewer, state.target);
    }

    private void beginEdit(Player viewer, GuiState state, String path, String label) {
        state.editKey = path;
        state.editLabel = label;
        state.editAt = System.currentTimeMillis();
        viewer.sendMessage(cfg.prefix() + "&\u0061\u8bf7\u5728\u804a\u5929\u8f93\u5165\u65b0\u503c\uff08"
                + label + "\uff09\uff0c\u8f93\u5165 cancel \u53d6\u6d88\u3002\u5f53\u524d\u503c\u53ef\u4ee5\u5728\u63d0\u793a\u4e0a\u65b9\u67e5\u770b\u3002");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        // 会话内二次权限复核（编辑配置属于敏感操作）
        if (!player.hasPermission("ycbr.admin") && !manager.isYcbrOp(player.getName())) {
            close(player);
            return;
        }
        GuiState state = states.get(player.getUniqueId());
        if (state == null || state.editKey == null) {
            return;
        }
        if (System.currentTimeMillis() - state.editAt > 180000L) {
            state.editKey = null;
            return;
        }
        event.setCancelled(true);
        final String input = event.getMessage().trim();
        final String key = state.editKey;
        final String label = state.editLabel;
        final CheckType type = state.type;
        final Page page = state.page;
        state.editKey = null;
        final org.bukkit.plugin.Plugin plugin = manager.getPlugin();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (input.equalsIgnoreCase("cancel")) {
                player.sendMessage(cfg.prefix() + "&\u0061\u5df2\u53d6\u6d88\u3002");
            } else if (isStringKey(key)) {
                cfg.set(key, input);
                cfg.save();
                manager.reload();
                player.sendMessage(cfg.prefix() + "&\u0061" + label + " \u5df2\u66f4\u6539\u4e3a: " + input);
            } else {
                int value;
                try {
                    value = Integer.parseInt(input);
                } catch (NumberFormatException e) {
                    player.sendMessage(cfg.prefix() + "&\u0063\u65e0\u6548\u6570\u503c\uff1a" + input);
                    return;
                }
                if (value < 1) {
                    player.sendMessage(cfg.prefix() + "&\u0063\u6570\u503c\u5fc5\u987b\u4e3a\u6b63\u6574\u6570\u3002");
                    return;
                }
                cfg.set(key, value);
                cfg.save();
                manager.reload();
                player.sendMessage(cfg.prefix() + "&\u0061" + label + " \u5df2\u66f4\u6539\u4e3a: " + value);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    if (page == Page.DDOS) {
                        openDdos(player);
                    } else if (type != null) {
                        openCheckSettings(player, type);
                    }
                }
            }, 2L);
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private ItemStack checkItem(CheckType type, Map<CheckType, Long> serverVl) {
        boolean enabled = cfg.enabled(type.getConfigPath());
        ItemStack item = new ItemStack(Material.INK_SACK, 1, enabled ? GREEN : GRAY);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((enabled ? C_ON : C_OFF) + type.getDisplay()
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        Long vl = serverVl.get(type);
        long serverVlValue = vl == null ? 0L : vl.longValue();
        List<String> lore = new ArrayList<String>();
        lore.add(C_INFO + "\u5168\u670d\u8fdd\u89c4\uff1a" + C_VAL + serverVlValue);
        lore.add(C_INFO + "\u8e22\u51fa\u9608\u503c\uff1a" + C_VAL
                + cfg.i("checks." + type.getConfigPath() + ".kick-at-vl", 20));
        if (!subsOf(type).isEmpty()) {
            lore.add("");
            lore.add(C_DIM + "\u5de6\u952e\u8fdb\u5165\u5b50\u68c0\u6d4b\uff0cShift+\u5de6\u952e\u6253\u5f00\u8bbe\u7f6e");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack editItem(String path, String label, String current) {
        ItemStack item = new ItemStack(Material.BOOK_AND_QUILL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(C_ACTION + label);
        List<String> lore = new ArrayList<String>();
        lore.add(C_INFO + "\u5f53\u524d\u503c\uff1a" + C_VAL + current);
        lore.add(C_INFO + "\u914d\u7f6e\u952e\uff1a" + C_DIM + path);
        lore.add("");
        lore.add(C_DIM + "\u70b9\u51fb\u7f16\u8f91\uff0c\u804a\u5929\u8f93\u5165\u65b0\u503c");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isStringKey(String key) {
        return key.endsWith("kick-message") || key.endsWith("kick-rate-limit")
                || key.endsWith("kick-invalid");
    }

    private java.util.List<String> subsOf(CheckType type) {
        return cfg.subs(type.getConfigPath());
    }

    /** 日志行按检测类别着色：战斗=红、移动=黄、协议=水蓝；未识别保持灰。 */
    private static ChatColor logColor(String line) {
        for (CheckType type : CheckType.values()) {
            if (line.contains(type.getDisplay())) {
                ImprobableTracker.Category cat = ImprobableTracker.categoryOf(type);
                if (cat == ImprobableTracker.Category.COMBAT) {
                    return ChatColor.RED;
                }
                if (cat == ImprobableTracker.Category.MOVEMENT) {
                    return ChatColor.YELLOW;
                }
                return ChatColor.AQUA;
            }
        }
        return C_INFO;
    }

    private long totalViolations(PlayerData data) {
        long total = 0L;
        for (CheckType type : CheckType.values()) {
            total += data.getViolations(type);
        }
        return total;
    }

    private static String lastFlag(PlayerData data) {
        long best = 0L;
        CheckType bestType = null;
        for (CheckType type : CheckType.values()) {
            long t = data.getLastFlagTime(type);
            if (t > best) {
                best = t;
                bestType = type;
            }
        }
        if (bestType == null) {
            return null;
        }
        long ago = (System.currentTimeMillis() - best) / 1000L;
        return bestType.getDisplay() + " \u00a77(" + ago + "s\u524d)";
    }

    private String recentFlag(PlayerData data) {
        return lastFlag(data);
    }

    private short dye(long vl, int kickAt) {
        if (vl <= 0) {
            return GRAY;
        }
        if (vl >= kickAt) {
            return RED;
        }
        return YELLOW;
    }

    /** 10 格进度条，如 [|||||-----]，按 vl/kickAt 着色。 */
    private static String progressBar(long vl, int kickAt) {
        if (kickAt <= 0) {
            return C_INFO + "[-]";
        }
        int filled = (int) Math.min(10L, Math.max(0L, vl * 10L / kickAt));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '|' : '-');
        }
        sb.append(']');
        ChatColor color = filled >= 10 ? C_OFF : filled >= 5 ? C_ACTION : C_ON;
        return color + sb.toString();
    }

    private static void fillBorder(Inventory inv, int[] exclude) {
        for (int slot : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47,
                48, 49, 50, 51, 52, 53 }) {
            boolean skip = false;
            for (int e : exclude) {
                if (e == slot) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                inv.setItem(slot, glass());
            }
        }
    }

    private static ItemStack glass() {
        ItemStack item = new ItemStack(Material.STAINED_GLASS_PANE, 1, GRAY);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private static String pingColor(int ping) {
        if (ping <= 0) {
            return C_INFO + "?";
        }
        if (ping < 100) {
            return C_ON + "" + ping + "ms";
        }
        if (ping < 200) {
            return C_ACTION + "" + ping + "ms";
        }
        return C_OFF + "" + ping + "ms";
    }

    private static String violationColor(long total) {
        if (total <= 0L) {
            return C_ON + "0";
        }
        if (total < 30L) {
            return C_ACTION + String.valueOf(total);
        }
        return C_OFF + String.valueOf(total);
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack infoItem(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<String>();
        for (String line : lines) {
            lore.add(line);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack head(String name) {
        ItemStack item = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwner(name);
        item.setItemMeta(meta);
        return item;
    }

    private static String MathUtilRound(double value) {
        return String.valueOf(Math.round(value * 100D) / 100D);
    }

    private static String MathUtilRound(double value, int places) {
        double factor = Math.pow(10D, places);
        return String.valueOf(Math.round(value * factor) / factor);
    }

    private void close(Player viewer) {
        viewer.closeInventory();
        states.remove(viewer.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
