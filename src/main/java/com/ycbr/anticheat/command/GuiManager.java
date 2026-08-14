package com.ycbr.anticheat.command;

import java.util.ArrayList;
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
import com.ycbr.anticheat.data.PlayerData;

public final class GuiManager implements Listener {

    private static final String TITLE_MENU = "\u00a78YCBR \u00a77\u2503 \u00a7f\u4e3b\u83dc\u5355";
    private static final String TITLE_PLAYERS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u73a9\u5bb6\u5217\u8868";
    private static final String TITLE_DETAIL = "\u00a78YCBR \u00a77\u2503 \u00a7f\u73a9\u5bb6\u8be6\u60c5";
    private static final String TITLE_CHECKS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u68c0\u6d4b\u9762\u677f";
    private static final String TITLE_SUBS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u5b50\u68c0\u6d4b";
    private static final String TITLE_SETTINGS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u8bbe\u7f6e";
    private static final String TITLE_LOGS = "\u00a78YCBR \u00a77\u2503 \u00a7f\u8fdd\u89c4\u65e5\u5fd7";
    private static final String TITLE_DDOS = "\u00a78YCBR \u00a77\u2503 \u00a7fDDOS \u9632\u62a4";
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
    private static final short BLACK = 15;
    private static final long KICK_CONFIRM_MS = 5000L;

    private enum Page {
        MENU, PLAYERS, DETAIL, CHECKS, CHECK_SUBS, CHECK_SETTINGS, LOGS, DDOS, OP_MENU, OP_MANUAL, OP_VL
    }

    private static final class GuiState {
        Page page;
        UUID target;
        int playerPage;
        CheckType type;
        long kickConfirmAt;
        int logPage;
        String editKey;
        String editLabel;
        long editAt;
    }

    private java.util.List<String> subsOf(CheckType type) {
        return cfg.subs(type.getConfigPath());
    }

    private final AntiCheatManager manager;
    private final YCBRConfig cfg;
    private final Map<UUID, GuiState> states = new ConcurrentHashMap<UUID, GuiState>();

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

    private void openOpMenu(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_OP);
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, glass());
        }
        int suspects = 0;
        for (PlayerData data : manager.getDataManager().all()) {
            if (data.timeTest >= 50) {
                suspects++;
            }
        }
        List<String> manualLore = new ArrayList<String>();
        manualLore.add(ChatColor.GRAY + "timeTest\u226550\uff1a" + ChatColor.WHITE + suspects
                + ChatColor.GRAY + " \u4eba");
        manualLore.add("");
        manualLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u67e5\u770b\u53ef\u7591\u73a9\u5bb6\uff0c\u4f20\u9001\u9690\u8eab\u89c2\u5bdf");
        inv.setItem(11, infoItem(Material.DIAMOND_SWORD, ChatColor.GOLD + "\u4eba\u5de5\u68c0\u6d4b",
                manualLore.toArray(new String[0])));
        List<String> vlLore = new ArrayList<String>();
        vlLore.add(ChatColor.GRAY + "\u67e5\u770b\u5728\u7ebf\u73a9\u5bb6\u7684\u8fdd\u89c4\u503c");
        vlLore.add("");
        vlLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u67e5\u770b\u73a9\u5bb6\u5404\u68c0\u6d4b VL");
        inv.setItem(15, infoItem(Material.BOOK, ChatColor.GOLD + "\u67e5\u770b\u73a9\u5bb6VL",
                vlLore.toArray(new String[0])));

        GuiState state = new GuiState();
        state.page = Page.OP_MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openOpManual(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_OP_MANUAL);
        fillBorder(inv, new int[] { 0, 45, 53 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        List<PlayerData> suspects = new ArrayList<PlayerData>();
        for (PlayerData data : manager.getDataManager().all()) {
            Player p = Bukkit.getPlayer(data.getUuid());
            if (p != null && data.timeTest >= 50) {
                suspects.add(data);
            }
        }
        suspects.sort((a, b) -> Integer.compare(b.timeTest, a.timeTest));
        for (int i = 0; i < suspects.size() && i < 35; i++) {
            PlayerData data = suspects.get(i);
            Player p = Bukkit.getPlayer(data.getUuid());
            ItemStack item = head(p.getName());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.RED + p.getName());
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "timeTest\uff1a" + ChatColor.WHITE + data.timeTest);
            lore.add(ChatColor.GRAY + "\u5ef6\u8fdf\uff1a" + pingColor(data.ping));
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u4f20\u9001\u5230\u73a9\u5bb6\u8eab\u8fb9\uff08\u9690\u8eab\uff09");
            meta.setLore(lore);
            item.setItemMeta(meta);
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, item);
        }
        for (int i = suspects.size(); i < 35; i++) {
            inv.setItem(i < 10 ? 9 + i : 27 + (i - 10), blank());
        }
        if (manager.getGhostManager().isGhost(viewer.getUniqueId())) {
            inv.setItem(45, named(Material.REDSTONE, ChatColor.RED + "\u9000\u51fa\u9690\u8eab\u6a21\u5f0f"));
        } else {
            inv.setItem(45, named(Material.PAPER, ChatColor.DARK_GRAY + "\u5f53\u524d\u672a\u5728\u9690\u8eab\u6a21\u5f0f"));
        }
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.OP_MANUAL;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openOpVl(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_OP_VL);
        fillBorder(inv, new int[] { 0, 53 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        List<Player> players = new ArrayList<Player>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.add(p);
        }
        players.sort((a, b) -> {
            long va = totalViolations(manager.getDataManager().get(a.getUniqueId()));
            long vb = totalViolations(manager.getDataManager().get(b.getUniqueId()));
            if (va != vb) {
                return Long.compare(vb, va);
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (int i = 0; i < players.size() && i < 35; i++) {
            Player p = players.get(i);
            PlayerData data = manager.getDataManager().get(p.getUniqueId());
            long total = totalViolations(data);
            ItemStack item = head(p.getName());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((total >= 30L ? ChatColor.RED : total > 0L ? ChatColor.GOLD : ChatColor.AQUA)
                    + p.getName());
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "\u8fdd\u89c4\uff1a" + violationColor(total));
            lore.add(ChatColor.GRAY + "\u5ef6\u8fdf\uff1a" + pingColor(data.ping));
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u67e5\u770b\u5404\u68c0\u6d4b\u8be6\u60c5");
            meta.setLore(lore);
            item.setItemMeta(meta);
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, item);
        }
        for (int i = players.size(); i < 35; i++) {
            inv.setItem(i < 10 ? 9 + i : 27 + (i - 10), blank());
        }
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.OP_VL;
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

    private void openMenu(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_MENU);
        for (int slot : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 19, 20, 21, 22, 23, 24,
                25, 26 }) {
            inv.setItem(slot, glass());
        }
        List<String> playersLore = new ArrayList<String>();
        playersLore.add(ChatColor.GRAY + "\u5728\u7ebf\uff1a" + ChatColor.WHITE + Bukkit.getOnlinePlayers().size()
                + ChatColor.GRAY + "\u4eba");
        playersLore.add("");
        playersLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u6253\u5f00\u73a9\u5bb6\u5217\u8868");
        inv.setItem(10, infoItem(Material.SKULL_ITEM, ChatColor.GOLD + "\u73a9\u5bb6\u5217\u8868", playersLore
                .toArray(new String[0])));
        List<String> checksLore = new ArrayList<String>();
        int enabled = 0;
        for (CheckType type : CheckType.values()) {
            if (cfg.enabled(type.getConfigPath())) {
                enabled++;
            }
        }
        checksLore.add(ChatColor.GRAY + "\u542f\u7528\u68c0\u6d4b\uff1a" + ChatColor.WHITE + enabled + " / "
                + CheckType.values().length);
        checksLore.add("");
        checksLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u6253\u5f00\u68c0\u6d4b\u9762\u677f");
        inv.setItem(12, infoItem(Material.BOOK, ChatColor.GOLD + "\u68c0\u6d4b\u914d\u7f6e", checksLore
                .toArray(new String[0])));
        List<String> logsLore = new ArrayList<String>();
        logsLore.add(ChatColor.GRAY + "\u6700\u8fd1\u89e6\u53d1\u7684\u8fdd\u89c4\u8bb0\u5f55");
        logsLore.add("");
        logsLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u6253\u5f00\u65e5\u5fd7");
        inv.setItem(14, infoItem(Material.PAPER, ChatColor.GOLD + "\u8fdd\u89c4\u65e5\u5fd7", logsLore
                .toArray(new String[0])));
        List<String> ddosLore = new ArrayList<String>();
        ddosLore.add(ChatColor.GRAY + "\u9632\u62a4\uff1a" + (cfg.raw().getBoolean("settings.ddos.enabled", true)
                ? ChatColor.GREEN + "\u5f00\u542f" : ChatColor.RED + "\u5173\u95ed"));
        ddosLore.add(ChatColor.GRAY + "\u8d85\u65f6\u5173\u95ed\u8fde\u63a5\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getClosedConnections());
        ddosLore.add("");
        ddosLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u6253\u5f00 DDOS \u9632\u62a4\u8bbe\u7f6e");
        inv.setItem(15, infoItem(Material.DIAMOND_CHESTPLATE, ChatColor.GOLD + "DDoS \u9632\u62a4", ddosLore
                .toArray(new String[0])));
        List<String> statusLore = new ArrayList<String>();
        statusLore.add(ChatColor.GRAY + "TPS\uff1a" + (manager.getMainHandler().getTps() >= 18.0D
                ? ChatColor.GREEN : manager.getMainHandler().getTps() >= 14.0D
                        ? ChatColor.YELLOW : ChatColor.RED)
                + String.format("%.1f", manager.getMainHandler().getTps()));
        statusLore.add(ChatColor.GRAY + "\u878d\u65ad\uff1a"
                + (manager.getMainHandler().isFused() ? ChatColor.RED + "\u6fc0\u6d3b\u4e2d"
                        : ChatColor.GREEN + "\u6b63\u5e38"));
        statusLore.add("");
        statusLore.add(ChatColor.DARK_GRAY + "\u4fe1\u606f\u4ec5\u89c2\u770b");
        inv.setItem(16, infoItem(Material.NETHER_STAR, ChatColor.GOLD + "\u670d\u52a1\u5668\u72b6\u6001",
                statusLore.toArray(new String[0])));

        GuiState state = new GuiState();
        state.page = Page.MENU;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openPlayers(Player viewer, int pageIdx) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_PLAYERS);
        fillBorder(inv, new int[] { 9, 10, 11, 12, 45, 46, 47, 52 });
        List<Player> players = new ArrayList<Player>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.add(p);
        }
        players.sort((a, b) -> {
            long va = totalViolations(manager.getDataManager().get(a.getUniqueId()));
            long vb = totalViolations(manager.getDataManager().get(b.getUniqueId()));
            if (va != vb) {
                return Long.compare(vb, va);
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        int totalPages = Math.max(1, (players.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        if (pageIdx >= totalPages) {
            pageIdx = totalPages - 1;
        }
        inv.setItem(9, named(Material.ANVIL, ChatColor.YELLOW + "\u91cd\u8f7d\u914d\u7f6e"));
        inv.setItem(10, named(Material.BOOK, ChatColor.YELLOW + "\u68c0\u6d4b\u9762\u677f"));
        inv.setItem(11, named(Material.EYE_OF_ENDER, ChatColor.YELLOW + "\u6211\u7684\u544a\u8b66\uff1a"
                + (manager.getMainHandler().hasAlert(viewer.getUniqueId())
                        ? ChatColor.GREEN + "\u5f00" : ChatColor.RED + "\u5173")));
        inv.setItem(12, named(Material.EMERALD, ChatColor.YELLOW + "\u5237\u65b0"));
        inv.setItem(13, named(Material.STAINED_GLASS_PANE, "\u00a7e\u878d\u65ad\u72b6\u6001\uff1a"
                + (manager.getMainHandler().isFused()
                        ? ChatColor.RED + "\u6fc0\u6d3b\u4e2d" : ChatColor.GREEN + "\u6b63\u5e38")));
        int start = pageIdx * PLAYERS_PER_PAGE;
        for (int k = 0; k < PLAYERS_PER_PAGE; k++) {
            int index = start + k;
            if (index >= players.size()) {
                inv.setItem(18 + k, blank());
                continue;
            }
            Player player = players.get(index);
            PlayerData data = manager.getDataManager().get(player.getUniqueId());
            ItemStack item = head(player.getName());
            ItemMeta meta = item.getItemMeta();
            long total = totalViolations(data);
            meta.setDisplayName((total >= 30L ? ChatColor.RED : total > 0L ? ChatColor.GOLD : ChatColor.AQUA)
                    + player.getName() + (player.getUniqueId().equals(viewer.getUniqueId())
                            ? ChatColor.DARK_GRAY + " \u00a77(\u81ea\u5df1)" : ""));
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "\u5ef6\u8fdf\uff1a" + pingColor(data.ping));
            lore.add(ChatColor.GRAY + "\u8fde\u70b9\uff1a" + ChatColor.WHITE + data.attackTimes.size());
            lore.add(ChatColor.GRAY + "\u8fdd\u89c4\uff1a" + violationColor(total));
            String recent = recentFlag(data);
            if (recent != null) {
                lore.add(ChatColor.GRAY + "\u6700\u8fd1\uff1a" + ChatColor.WHITE + recent);
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u67e5\u770b\u8be6\u60c5");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(18 + k, item);
        }
        ItemStack pageInfo = named(Material.PAPER, ChatColor.WHITE + "\u7b2c" + (pageIdx + 1) + "/" + totalPages
                + "\u9875");
        inv.setItem(46, pageInfo);
        if (pageIdx > 0) {
            inv.setItem(45, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u4e0a\u4e00\u9875"));
        }
        if (pageIdx < totalPages - 1) {
            inv.setItem(47, named(Material.ARROW, ChatColor.YELLOW + "\u4e0b\u4e00\u9875 \u2192"));
        }
        inv.setItem(52, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.PLAYERS;
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
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        long total = totalViolations(data);
        ItemStack headItem = head(target.getName());
        ItemMeta headMeta = headItem.getItemMeta();
        headMeta.setDisplayName((total >= 30L ? ChatColor.RED : total > 0L ? ChatColor.GOLD : ChatColor.AQUA)
                + target.getName());
        List<String> headLore = new ArrayList<String>();
        headLore.add(ChatColor.GRAY + "\u8fdd\u89c4\uff1a" + violationColor(total));
        String recent = recentFlag(data);
        if (recent != null) {
            headLore.add(ChatColor.GRAY + "\u6700\u8fd1\uff1a" + ChatColor.WHITE + recent);
        }
        headMeta.setLore(headLore);
        headItem.setItemMeta(headMeta);
        inv.setItem(4, headItem);
        inv.setItem(10, infoItem(Material.PAPER, ChatColor.GOLD + "\u4fe1\u606f",
                ChatColor.GRAY + "\u5ef6\u8fdf\uff1a" + pingColor(data.ping),
                ChatColor.GRAY + "\u8fde\u70b9(1s)\uff1a" + ChatColor.WHITE + data.attackTimes.size(),
                ChatColor.GRAY + "\u901f\u5ea6\u7b49\u7ea7\uff1a" + ChatColor.WHITE + data.speedLevel,
                ChatColor.GRAY + "\u8df3\u8dc3\u7b49\u7ea7\uff1a" + ChatColor.WHITE + data.jumpLevel,
                ChatColor.GRAY + "\u521b\u9020\uff1a" + ChatColor.WHITE + data.creative));
        inv.setItem(11, infoItem(Material.MAP, ChatColor.GOLD + "\u79fb\u52a8",
                ChatColor.GRAY + "\u7ad6\u5411\u901f\u5ea6\uff1a" + ChatColor.WHITE + MathUtilRound(data.movement.motionY),
                ChatColor.GRAY + "\u6c34\u5e73\u8ddd\u79bb\uff1a" + ChatColor.WHITE + MathUtilRound(data.movement.distanceXZ),
                ChatColor.GRAY + "\u7a7a\u4e2dtick\uff1a" + ChatColor.WHITE + data.movement.airTicks,
                ChatColor.GRAY + "\u5730\u9762tick\uff1a" + ChatColor.WHITE + data.movement.groundTicks,
                ChatColor.GRAY + "\u8df3\u8d77\uff1a" + ChatColor.WHITE + data.movement.jumpedThisTick));
        inv.setItem(12, infoItem(Material.BOOK, ChatColor.GOLD + "\u72b6\u6001",
                ChatColor.GRAY + "\u68af\u5b50\uff1a" + ChatColor.WHITE + data.movement.ladderTicks + "t",
                ChatColor.GRAY + "\u6db2\u4f53\uff1a" + ChatColor.WHITE + data.movement.nearLiquidTicks + "t",
                ChatColor.GRAY + "\u86db\u7f51\uff1a" + ChatColor.WHITE + data.movement.inWebTicks + "t",
                ChatColor.GRAY + "\u53f2\u83b1\u59c6\uff1a" + ChatColor.WHITE + data.movement.slimeTicks + "t",
                ChatColor.GRAY + "\u88ab\u56f0\uff1a" + ChatColor.WHITE + data.movement.boxedIn));
        inv.setItem(13, infoItem(Material.COMPASS, ChatColor.GOLD + "\u65cb\u8f6c",
                ChatColor.GRAY + "\u504f\u822a\uff1a" + ChatColor.WHITE + MathUtilRound(data.lastYaw, 1),
                ChatColor.GRAY + "\u4fef\u4ef0\uff1a" + ChatColor.WHITE + MathUtilRound(data.lastPitch, 1),
                ChatColor.GRAY + "\u504f\u822a\u53d8\u5316\uff1a" + ChatColor.WHITE + MathUtilRound(data.lastYawDelta, 1),
                ChatColor.GRAY + "\u6709\u65cb\u8f6c\uff1a" + ChatColor.WHITE + data.hasRotation));
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
            meta.setDisplayName((vl >= kickAt ? ChatColor.RED : vl > 0 ? ChatColor.YELLOW : ChatColor.GRAY)
                    + type.getDisplay());
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "\u8fdd\u89c4\uff1a" + ChatColor.WHITE + vl + ChatColor.GRAY + " / " + kickAt);
            lore.add(ChatColor.GRAY + "\u542f\u7528\uff1a" + (cfg.enabled(type.getConfigPath())
                    ? ChatColor.GREEN + "\u662f" : ChatColor.RED + "\u5426"));
            long last = data.getLastFlagTime(type);
            if (last > 0L) {
                long ago = (System.currentTimeMillis() - last) / 1000L;
                lore.add(ChatColor.GRAY + "\u6700\u8fd1\u89e6\u53d1\uff1a" + ChatColor.WHITE + ago + "s\u524d");
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(18 + i, item);
        }
        for (int i = sorted.size(); i < 26; i++) {
            inv.setItem(18 + i, blank());
        }
        GuiState prevState = states.get(viewer.getUniqueId());
        long kickConfirm = prevState != null ? prevState.kickConfirmAt : 0L;
        boolean kickArmed = kickConfirm > 0L && System.currentTimeMillis() - kickConfirm < KICK_CONFIRM_MS;
        inv.setItem(45, named(Material.BOOK, ChatColor.YELLOW + "\u68c0\u6d4b"));
        inv.setItem(46, named(Material.EYE_OF_ENDER, ChatColor.YELLOW + "\u5207\u6362\u544a\u8b66\uff1a"
                + (manager.getMainHandler().hasAlert(target.getUniqueId())
                        ? ChatColor.GREEN + "\u5f00" : ChatColor.RED + "\u5173")));
        inv.setItem(47, named(Material.GLOWSTONE_DUST, ChatColor.YELLOW + "\u6e05\u7a7a\u8fdd\u89c4"));
        if (kickArmed) {
            inv.setItem(48, named(Material.BARRIER,
                    ChatColor.RED + "\u518d\u6b21\u70b9\u51fb\u786e\u8ba4\u8e22\u51fa " + target.getName() + "!"
                    + "\u00a77(" + Math.max(1, (KICK_CONFIRM_MS - (System.currentTimeMillis() - kickConfirm)) / 1000L)
                    + "s)"));
        } else {
            inv.setItem(48, named(Material.BARRIER, ChatColor.RED + "\u8e22\u51fa " + target.getName()));
        }
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.DETAIL;
        state.target = targetId;
        state.kickConfirmAt = kickConfirm;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openChecks(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_CHECKS);
        fillBorder(inv, new int[] { 45, 47, 52 });
        CheckType[] types = CheckType.values();
        for (int i = 0; i < types.length; i++) {
            int slot = i < 10 ? 9 + i : 27 + (i - 10);
            inv.setItem(slot, checkItem(types[i]));
        }
        for (int i = types.length; i < 20; i++) {
            inv.setItem(i < 10 ? 9 + i : 27 + (i - 10), blank());
        }
        int enabled = 0;
        long total = 0L;
        for (CheckType type : types) {
            if (cfg.enabled(type.getConfigPath())) {
                enabled++;
            }
            for (PlayerData data : manager.getDataManager().all()) {
                total += data.getViolations(type);
            }
        }
        List<String> statsLore = new ArrayList<String>();
        statsLore.add(ChatColor.GRAY + "\u542f\u7528\u68c0\u6d4b\uff1a" + ChatColor.WHITE + enabled + " / "
                + types.length);
        statsLore.add(ChatColor.GRAY + "\u5168\u670f\u8fdd\u89c4\uff1a" + violationColor(total));
        statsLore.add(ChatColor.GRAY + "\u878d\u65ad\uff1a"
                + (manager.getMainHandler().isFused() ? ChatColor.RED + "\u6fc0\u6d3b\u4e2d"
                        : ChatColor.GREEN + "\u6b63\u5e38"));
        statsLore.add("");
        statsLore.add(ChatColor.DARK_GRAY + "\u5de6\u952e\u8fdb\u5165\u5b50\u68c0\u6d4b\uff0cShift+\u5de6\u952e\u6253\u5f00\u8bbe\u7f6e");
        ItemStack stats = new ItemStack(Material.NETHER_STAR);
        ItemMeta statsMeta = stats.getItemMeta();
        statsMeta.setDisplayName(ChatColor.GOLD + "\u5168\u670f\u7edf\u8ba1");
        statsMeta.setLore(statsLore);
        stats.setItemMeta(statsMeta);
        inv.setItem(45, stats);
        inv.setItem(47, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        inv.setItem(52, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.CHECKS;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openCheckSubs(Player viewer, CheckType type) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SUBS + " \u00a77\u2503 " + type.getDisplay());
        fillBorder(inv, new int[] { 45, 46, 47, 53 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        boolean mainEnabled = cfg.enabled(type.getConfigPath());
        ItemStack mainToggle = new ItemStack(Material.STAINED_CLAY, 1, mainEnabled ? (short) 5 : (short) 14);
        ItemMeta mainMeta = mainToggle.getItemMeta();
        mainMeta.setDisplayName((mainEnabled ? ChatColor.GREEN : ChatColor.RED) + "\u68c0\u6d4b\u5f00\u5173"
                + (mainEnabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> mainLore = new ArrayList<String>();
        mainLore.add(ChatColor.GRAY + "\u5f53\u524d\uff1a" + (mainEnabled ? ChatColor.GREEN + "\u5f00"
                : ChatColor.RED + "\u5173"));
        mainLore.add("");
        mainLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u5207\u6362\u6574\u4e2a\u68c0\u6d4b");
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
                meta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + sub
                        + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
                List<String> lore = new ArrayList<String>();
                lore.add(ChatColor.GRAY + "\u89e6\u53d1\u9608\u503c\uff1a" + ChatColor.WHITE
                        + cfg.i("checks." + type.getConfigPath() + "." + sub + ".vl-before-flag", 5));
                lore.add("");
                lore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb" + (enabled ? "\u7981\u7528" : "\u542f\u7528"));
                meta.setLore(lore);
                item.setItemMeta(meta);
                inv.setItem(10 + i, item);
            }
            inv.setItem(45, named(Material.EMERALD, ChatColor.GREEN + "\u5168\u90e8\u542f\u7528"));
            inv.setItem(46, named(Material.REDSTONE, ChatColor.RED + "\u5168\u90e8\u7981\u7528"));
        }
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.CHECK_SUBS;
        state.type = type;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openCheckSettings(Player viewer, CheckType type) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SETTINGS + " \u00a77\u2503 " + type.getDisplay());
        fillBorder(inv, new int[] { 0, 9, 10, 11, 12, 13, 45, 46, 53 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        String base = "checks." + type.getConfigPath() + ".";
        boolean enabled = cfg.enabled(type.getConfigPath());
        ItemStack toggle = new ItemStack(Material.STAINED_CLAY, 1, enabled ? (short) 5 : (short) 14);
        ItemMeta toggleMeta = toggle.getItemMeta();
        toggleMeta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + "\u68c0\u6d4b\u5f00\u5173"
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> toggleLore = new ArrayList<String>();
        toggleLore.add(ChatColor.GRAY + "\u5f53\u524d\uff1a" + (enabled ? ChatColor.GREEN + "\u5f00"
                : ChatColor.RED + "\u5173"));
        toggleLore.add("");
        toggleLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u5207\u6362");
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
        } else {
            inv.setItem(13, blank());
        }
        List<String> infoLore = new ArrayList<String>();
        long total = 0L;
        for (PlayerData data : manager.getDataManager().all()) {
            total += data.getViolations(type);
        }
        infoLore.add(ChatColor.GRAY + "\u5168\u670f\u8fdd\u89c4\uff1a" + ChatColor.WHITE + total);
        infoLore.add(ChatColor.GRAY + "\u914d\u7f6e\u8def\u5f84\uff1a" + ChatColor.WHITE + base);
        infoLore.add("");
        infoLore.add(ChatColor.DARK_GRAY + "\u6570\u503c\u9879\u70b9\u51fb\u540e\u5728\u804a\u5929\u8f93\u5165\u65b0\u503c");
        infoLore.add(ChatColor.DARK_GRAY + "\u8f93\u5165 cancel \u53d6\u6d88");
        inv.setItem(18, infoItem(Material.PAPER, ChatColor.GOLD + "\u8bf4\u660e", infoLore.toArray(new String[0])));
        if (!subList.isEmpty()) {
            inv.setItem(45, named(Material.EMERALD, ChatColor.GREEN + "\u5168\u90e8\u542f\u7528\u5b50\u68c0\u6d4b"));
            inv.setItem(46, named(Material.REDSTONE, ChatColor.RED + "\u5168\u90e8\u7981\u7528\u5b50\u68c0\u6d4b"));
        }
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.CHECK_SETTINGS;
        state.type = type;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private ItemStack editItem(String path, String label, String current) {
        ItemStack item = new ItemStack(Material.BOOK_AND_QUILL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + label);
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "\u5f53\u524d\u503c\uff1a" + ChatColor.WHITE + current);
        lore.add(ChatColor.GRAY + "\u914d\u7f6e\u952e\uff1a" + ChatColor.DARK_GRAY + path);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u7f16\u8f91\uff0c\u804a\u5929\u8f93\u5165\u65b0\u503c");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openLogs(Player viewer, int pageIdx) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_LOGS);
        fillBorder(inv, new int[] { 45, 46, 47, 52 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        java.util.List<String> logs = manager.getMainHandler().getViolationLog();
        int perPage = 27;
        int totalPages = Math.max(1, (logs.size() + perPage - 1) / perPage);
        if (pageIdx >= totalPages) {
            pageIdx = totalPages - 1;
        }
        for (int k = 0; k < perPage; k++) {
            int index = pageIdx * perPage + k;
            if (index >= logs.size()) {
                inv.setItem(18 + k, blank());
                continue;
            }
            String line = logs.get(index);
            inv.setItem(18 + k, named(Material.PAPER, ChatColor.GRAY + line));
        }
        inv.setItem(46, named(Material.PAPER, ChatColor.WHITE + "\u7b2c" + (pageIdx + 1) + "/" + totalPages
                + "\u9875"));
        if (pageIdx > 0) {
            inv.setItem(45, named(Material.ARROW, ChatColor.GREEN + "\u2190 \u4e0a\u4e00\u9875"));
        } else {
            inv.setItem(45, named(Material.ARROW, ChatColor.DARK_GRAY + "\u2190 \u4e0a\u4e00\u9875"));
        }
        if (pageIdx < totalPages - 1) {
            inv.setItem(47, named(Material.ARROW, ChatColor.GREEN + "\u4e0b\u4e00\u9875 \u2192"));
        } else {
            inv.setItem(47, named(Material.ARROW, ChatColor.DARK_GRAY + "\u4e0b\u4e00\u9875 \u2192"));
        }
        inv.setItem(52, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        GuiState prev = states.get(viewer.getUniqueId());
        if (prev != null) {
            state.target = prev.target;
        }
        state.page = Page.LOGS;
        state.logPage = pageIdx;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private void openDdos(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_DDOS);
        fillBorder(inv, new int[] { 0, 45, 46, 53 });
        inv.setItem(0, named(Material.ARROW, ChatColor.YELLOW + "\u2190 \u8fd4\u56de"));
        boolean enabled = cfg.raw().getBoolean("settings.ddos.enabled", true);
        ItemStack toggle = new ItemStack(Material.STAINED_CLAY, 1, enabled ? (short) 5 : (short) 14);
        ItemMeta toggleMeta = toggle.getItemMeta();
        toggleMeta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + "DDOS \u9632\u62a4\u5f00\u5173"
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        List<String> toggleLore = new ArrayList<String>();
        toggleLore.add(ChatColor.GRAY + "\u5f53\u524d\uff1a" + (enabled ? ChatColor.GREEN + "\u5f00"
                : ChatColor.RED + "\u5173"));
        toggleLore.add("");
        toggleLore.add(ChatColor.DARK_GRAY + "\u70b9\u51fb\u5207\u6362");
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
        stats.add(ChatColor.GRAY + "\u5f02\u5e38\u5305/\u8fde\u63a5\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getViolations());
        stats.add(ChatColor.GRAY + "\u8d85\u65f6\u5173\u95ed\u8fde\u63a5\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getClosedConnections());
        stats.add(ChatColor.GRAY + "\u9650\u901f\u62e6\u622a\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getRateBlocks());
        stats.add(ChatColor.GRAY + "\u72b6\u6001\u8bf7\u6c42(ping)\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getStatusPings());
        stats.add(ChatColor.GRAY + "\u5f53\u524d\u8fde\u63a5\uff1a" + ChatColor.WHITE
                + manager.getDdosGuard().getCurrentConnections());
        stats.add("");
        stats.add(ChatColor.DARK_GRAY + "\u6253\u5f00\u9875\u9762\u65f6\u5237\u65b0");
        inv.setItem(29, infoItem(Material.PAPER, ChatColor.GOLD + "\u5b9e\u65f6\u7edf\u8ba1",
                stats.toArray(new String[0])));
        inv.setItem(45, infoItem(Material.BOOK, ChatColor.GOLD + "\u8bf4\u660e",
                ChatColor.DARK_GRAY + "\u6570\u503c\u9879\u70b9\u51fb\u540e\u5728\u804a\u5929\u8f93\u5165\u65b0\u503c",
                ChatColor.DARK_GRAY + "\u8f93\u5165 cancel \u53d6\u6d88",
                ChatColor.DARK_GRAY + "\u8d85\u65f6\u503c\u4e3a\u8fde\u63a5\u5728\u8be5\u72b6\u6001\u505c\u7559\u7684\u6700\u957f\u79d2\u6570"));
        inv.setItem(53, named(Material.BARRIER, ChatColor.RED + "\u5173\u95ed"));

        GuiState state = new GuiState();
        state.page = Page.DDOS;
        states.put(viewer.getUniqueId(), state);
        viewer.openInventory(inv);
    }

    private ItemStack checkItem(CheckType type) {
        boolean enabled = cfg.enabled(type.getConfigPath());
        ItemStack item = new ItemStack(Material.INK_SACK, 1, enabled ? GREEN : GRAY);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + type.getDisplay()
                + (enabled ? "" : " \u00a77(\u5df2\u7981\u7528)"));
        long vl = 0L;
        for (PlayerData data : manager.getDataManager().all()) {
            vl += data.getViolations(type);
        }
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "\u5168\u670d\u8fdd\u89c4\uff1a" + ChatColor.WHITE + vl);
        lore.add(ChatColor.GRAY + "\u8e22\u51fa\u9608\u503c\uff1a" + ChatColor.WHITE
                + cfg.i("checks." + type.getConfigPath() + ".kick-at-vl", 20));
        if (!subsOf(type).isEmpty()) {
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "\u5de6\u952e\u8fdb\u5165\u5b50\u68c0\u6d4b\uff0cShift+\u5de6\u952e\u6253\u5f00\u8bbe\u7f6e");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private long totalViolations(PlayerData data) {
        long total = 0L;
        for (CheckType type : CheckType.values()) {
            total += data.getViolations(type);
        }
        return total;
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

    private static ItemStack blank() {
        ItemStack item = new ItemStack(Material.STAINED_GLASS_PANE, 1, BLACK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private static String pingColor(int ping) {
        if (ping <= 0) {
            return ChatColor.GRAY + "?";
        }
        if (ping < 100) {
            return ChatColor.GREEN + "" + ping + "ms";
        }
        if (ping < 200) {
            return ChatColor.YELLOW + "" + ping + "ms";
        }
        return ChatColor.RED + "" + ping + "ms";
    }

    private static String violationColor(long total) {
        if (total <= 0L) {
            return ChatColor.GREEN + "0";
        }
        if (total < 30L) {
            return ChatColor.YELLOW + String.valueOf(total);
        }
        return ChatColor.RED + String.valueOf(total);
    }

    private String recentFlag(PlayerData data) {
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
                && !TITLE_CHECKS.equals(title) && !TITLE_SUBS.equals(title)
                && !title.startsWith(TITLE_SETTINGS) && !TITLE_LOGS.equals(title)
                && !TITLE_DDOS.equals(title) && !TITLE_OP.equals(title)
                && !TITLE_OP_MANUAL.equals(title) && !TITLE_OP_VL.equals(title)) {
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
                if (slot == 10) {
                    openPlayers(viewer, 0);
                } else if (slot == 12) {
                    openChecks(viewer);
                } else if (slot == 14) {
                    openLogs(viewer, 0);
                } else if (slot == 15) {
                    openDdos(viewer);
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
                    List<Player> players = new ArrayList<Player>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        players.add(p);
                    }
                    int index = state.playerPage * PLAYERS_PER_PAGE + (slot - 18);
                    if (index < players.size()) {
                        openDetail(viewer, players.get(index).getUniqueId());
                    }
                }
                return;
            case DETAIL:
                if (slot == 53) {
                    close(viewer);
                    return;
                }
                if (slot == 0) {
                    openPlayers(viewer, state.playerPage);
                    return;
                }
                if (slot == 45) {
                    state.kickConfirmAt = 0L;
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
                    state.kickConfirmAt = 0L;
                    openDetail(viewer, state.target);
                    return;
                }
                if (slot == 47) {
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
                    state.kickConfirmAt = 0L;
                    openDetail(viewer, state.target);
                    return;
                }
                if (slot == 48) {
                    long now = System.currentTimeMillis();
                    if (state.kickConfirmAt > 0L && now - state.kickConfirmAt < KICK_CONFIRM_MS) {
                        Player target = Bukkit.getPlayer(state.target);
                        if (target != null) {
                            target.kickPlayer(ChatColor.RED + "\u88ab " + viewer.getName() + " \u8e22\u51fa (YCBR)");
                            viewer.sendMessage(cfg.prefix() + "&\u0061\u5df2\u8e22\u51fa " + target.getName() + "\u3002");
                        }
                        close(viewer);
                    } else {
                        state.kickConfirmAt = now;
                        openDetail(viewer, state.target);
                    }
                }
                return;
            case CHECKS:
                if (slot == 52) {
                    close(viewer);
                    return;
                }
                if (slot == 47) {
                    GuiState prev = states.get(viewer.getUniqueId());
                    if (prev != null && prev.target != null) {
                        openDetail(viewer, prev.target);
                    } else {
                        openPlayers(viewer, 0);
                    }
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
                    openChecks(viewer);
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
                    openMenu(viewer);
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
                    openChecks(viewer);
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
                    openMenu(viewer);
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
                    openOpMenu(viewer);
                    return;
                }
                if (slot == 45 && manager.getGhostManager().isGhost(viewer.getUniqueId())) {
                    manager.getGhostManager().deactivate(viewer);
                    openOpManual(viewer);
                    return;
                }
                if (slot >= 9 && slot < 19 || slot >= 27 && slot < 36) {
                    int index = slot < 19 ? slot - 9 : slot - 27 + 10;
                    List<PlayerData> suspects = new ArrayList<PlayerData>();
                    for (PlayerData data : manager.getDataManager().all()) {
                        if (Bukkit.getPlayer(data.getUuid()) != null && data.timeTest >= 50) {
                            suspects.add(data);
                        }
                    }
                    suspects.sort((a, b) -> Integer.compare(b.timeTest, a.timeTest));
                    if (index < suspects.size()) {
                        Player target = Bukkit.getPlayer(suspects.get(index).getUuid());
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
                    openOpMenu(viewer);
                    return;
                }
                if (slot >= 9 && slot < 19 || slot >= 27 && slot < 36) {
                    int index = slot < 19 ? slot - 9 : slot - 27 + 10;
                    List<Player> players = new ArrayList<Player>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        players.add(p);
                    }
                    players.sort((a, b) -> {
                        long va = totalViolations(manager.getDataManager().get(a.getUniqueId()));
                        long vb = totalViolations(manager.getDataManager().get(b.getUniqueId()));
                        if (va != vb) {
                            return Long.compare(vb, va);
                        }
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    if (index < players.size()) {
                        sendVlDetail(viewer, manager.getDataManager().get(players.get(index).getUniqueId()));
                    }
                }
                return;
            default:
                return;
        }
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

    private static boolean isStringKey(String key) {
        return key.endsWith("kick-message") || key.endsWith("kick-rate-limit")
                || key.endsWith("kick-invalid");
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
