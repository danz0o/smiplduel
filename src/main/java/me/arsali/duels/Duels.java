package me.arsali.duels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

import java.time.Duration;
import java.util.*;

public class Duels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static final MiniMessage MM = MiniMessage.miniMessage();

    enum State { COUNTDOWN, ACTIVE, ENDED }

    record Req(UUID from, String kit, long time) {}

    static class Party {
        UUID leader;
        final List<UUID> members = new ArrayList<>();
    }

    record PInv(Party party, long time) {}

    /** Kit selection GUI. mode = duel | queue | ffa */
    static class KitMenu implements InventoryHolder {
        final String mode;
        final UUID target;
        final List<String> kits = new ArrayList<>();
        Inventory inv;
        KitMenu(String mode, UUID target) { this.mode = mode; this.target = target; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** Settings GUI */
    static class SettingsMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    class Match {
        final List<Player> t1, t2;
        final String kit;
        final boolean ffa;
        String arena;
        final Set<UUID> specs = new HashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> left = new HashSet<>();
        State state = State.COUNTDOWN;
        long start = 0;
        // arena regen
        BoundingBox box;
        World world;
        BlockData[] snap;

        Match(List<Player> t1, List<Player> t2, String kit, boolean ffa) {
            this.t1 = t1; this.t2 = t2; this.kit = kit; this.ffa = ffa;
            for (Player p : t1) alive.add(p.getUniqueId());
            for (Player p : t2) alive.add(p.getUniqueId());
        }
        boolean inTeam1(Player p) {
            for (Player x : t1) if (x.getUniqueId().equals(p.getUniqueId())) return true;
            return false;
        }
        List<Player> team(Player p) {
            if (ffa) return List.of(p);
            return inTeam1(p) ? t1 : t2;
        }
        List<Player> enemy(Player p) {
            if (ffa) {
                List<Player> l = new ArrayList<>();
                for (Player x : t1) if (!x.getUniqueId().equals(p.getUniqueId())) l.add(x);
                return l;
            }
            return inTeam1(p) ? t2 : t1;
        }
        List<Player> all() { List<Player> l = new ArrayList<>(t1); l.addAll(t2); return l; }
    }

    final Map<UUID, Match> matches = new HashMap<>();   // fighters
    final Map<UUID, Match> watching = new HashMap<>();  // spectators
    final Set<Match> activeMatches = new HashSet<>();
    final Map<UUID, Req> requests = new HashMap<>();    // key = target
    final Map<UUID, Scoreboard> boards = new HashMap<>();
    final Set<String> busyArenas = new HashSet<>();
    final Map<UUID, Party> parties = new HashMap<>();
    final Map<UUID, PInv> pinvites = new HashMap<>();
    final Map<UUID, String> queue = new LinkedHashMap<>(); // player -> kit
    final Map<UUID, String> editing = new HashMap<>();     // player -> kit being edited

    // ───────────────────────── setup ─────────────────────────

    @Override
    public void onEnable() {
        getConfig().addDefault("footer", "<yellow>play.example.net");
        getConfig().addDefault("wind-charge-reset", true);
        getConfig().addDefault("party-max", 4);
        getConfig().addDefault("max-arena-volume", 300000);
        getConfig().options().copyDefaults(true);
        saveConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : List.of("duel", "spectate", "leave", "setlobby", "arena", "kit", "party", "settings", "kiteditor")) {
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
            Objects.requireNonNull(getCommand(c)).setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(this::update), 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
    }

    @Override
    public void onDisable() {
        for (Match m : new HashSet<>(activeMatches)) restore(m);
    }

    void msg(CommandSender s, String mm) { s.sendMessage(MM.deserialize(mm)); }

    void title(Player p, String t, String sub) {
        p.showTitle(Title.title(MM.deserialize(t), MM.deserialize(sub),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(400))));
    }

    String label(List<Player> l) {
        if (l.isEmpty()) return "-";
        return l.get(0).getName() + (l.size() > 1 ? " +" + (l.size() - 1) : "");
    }

    String names(List<Player> l) {
        List<String> n = new ArrayList<>();
        for (Player p : l) n.add(p.getName());
        return String.join(", ", n);
    }

    ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ───────────────────────── lobby / reset ─────────────────────────

    Location lobby() {
        Location l = getConfig().getLocation("lobby");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    void reset(Player p, GameMode gm) {
        p.setGameMode(gm);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setCooldown(Material.WIND_CHARGE, 0);
    }

    void lobbyItems(Player p) {
        p.getInventory().setItem(0, named(Material.IRON_SWORD, "<yellow><bold>Unranked Queue", "<gray>Right-click to pick a kit and queue"));
        p.getInventory().setItem(1, named(Material.NAME_TAG, "<light_purple><bold>Party", "<gray>Right-click: create / view your party"));
        p.getInventory().setItem(2, named(Material.BOOK, "<aqua><bold>Kit Editor", "<gray>Right-click to rearrange your kits"));
        p.getInventory().setItem(8, named(Material.COMPARATOR, "<green><bold>Settings", "<gray>Scoreboard, nametags, players, requests"));
    }

    void toLobby(Player p) {
        editing.remove(p.getUniqueId());
        reset(p, GameMode.ADVENTURE);
        p.teleport(lobby());
        lobbyItems(p);
        update(p);
    }

    boolean busy(Player p) {
        return matches.containsKey(p.getUniqueId()) || watching.containsKey(p.getUniqueId());
    }

    boolean anyBusy(List<Player> l) {
        for (Player p : l) if (busy(p)) return true;
        return false;
    }

    // ───────────────────────── kits / arenas ─────────────────────────

    String firstKit() {
        ConfigurationSection s = getConfig().getConfigurationSection("kits");
        if (s == null || s.getKeys(false).isEmpty()) return null;
        return s.getKeys(false).iterator().next();
    }

    void giveKit(Player p, String kit) {
        List<?> raw = getConfig().getList("kits." + kit + ".items");
        if (raw == null) return;
        ItemStack[] arr = new ItemStack[raw.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = raw.get(i) instanceof ItemStack is ? is : null;
        }
        p.getInventory().setContents(arr);
        // player's own saved layout (Kit Editor)
        List<?> lay = getConfig().getList("layouts." + p.getUniqueId() + "." + kit);
        if (lay != null && lay.size() == 36 && arr.length >= 36) {
            ItemStack[] ls = new ItemStack[36];
            for (int i = 0; i < 36; i++) ls[i] = lay.get(i) instanceof ItemStack li ? li : null;
            if (sameMulti(merge(ls, 0, 36), merge(arr, 0, 36))) {
                for (int i = 0; i < 36; i++) p.getInventory().setItem(i, ls[i]);
            }
        }
    }

    String freeArena() {
        ConfigurationSection s = getConfig().getConfigurationSection("arenas");
        if (s == null) return null;
        List<String> free = new ArrayList<>();
        for (String n : s.getKeys(false)) {
            if (busyArenas.contains(n)) continue;
            if (s.getLocation(n + ".spawn1") == null || s.getLocation(n + ".spawn2") == null) continue;
            free.add(n);
        }
        if (free.isEmpty()) return null;
        return free.get(new Random().nextInt(free.size()));
    }

    // ───────────────────────── settings ─────────────────────────

    static final String[][] SETTINGS = {
            {"scoreboard", "Scoreboard", "Show the sidebar scoreboard"},
            {"nametags", "Player nametags", "Show names above players' heads"},
            {"players", "Show players", "See other players in the lobby"},
            {"requests", "Duel requests", "Receive duel requests"}
    };
    static final int[] SETTING_SLOTS = {10, 12, 14, 16};

    boolean setting(Player p, String key) {
        return getConfig().getBoolean("settings." + p.getUniqueId() + "." + key, true);
    }

    void openSettings(Player p) {
        SettingsMenu h = new SettingsMenu();
        Inventory inv = Bukkit.createInventory(h, 27, MM.deserialize("<dark_gray>Settings"));
        h.inv = inv;
        fillSettings(p, inv);
        p.openInventory(inv);
    }

    void fillSettings(Player p, Inventory inv) {
        for (int i = 0; i < SETTINGS.length; i++) {
            boolean on = setting(p, SETTINGS[i][0]);
            inv.setItem(SETTING_SLOTS[i], named(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "<green>" : "<red>") + "<bold>" + SETTINGS[i][1] + ": " + (on ? "ON" : "OFF"),
                    "<gray>" + SETTINGS[i][2], "<yellow>Click to toggle"));
        }
    }

    /** Who can p see? Fighters only see their match, lobby players see the lobby (unless "Show players" is off). */
    void visibility(Player p) {
        Match pm = matches.get(p.getUniqueId());
        Match pw = watching.get(p.getUniqueId());
        Match pMatch = pm != null ? pm : pw;
        boolean hideLobby = !setting(p, "players");
        Party pa = parties.get(p.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            Match om = matches.get(o.getUniqueId());
            Match ow = watching.get(o.getUniqueId());
            Match oMatch = om != null ? om : ow;
            boolean see;
            if (pMatch != null) see = pMatch == oMatch;
            else see = oMatch == null && (!hideLobby || (pa != null && pa == parties.get(o.getUniqueId())));
            boolean can = p.canSee(o);
            if (see && !can) p.showPlayer(this, o);
            else if (!see && can) p.hidePlayer(this, o);
        }
    }

    // ───────────────────────── kit editor ─────────────────────────

    boolean sim(ItemStack a, ItemStack b) {
        boolean ea = a == null || a.getType().isAir(), eb = b == null || b.getType().isAir();
        if (ea || eb) return ea && eb;
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    List<ItemStack> merge(ItemStack[] arr, int from, int to) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = from; i < to && i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            boolean found = false;
            for (ItemStack o : out) {
                if (o.isSimilar(it)) { o.setAmount(o.getAmount() + it.getAmount()); found = true; break; }
            }
            if (!found) out.add(it.clone());
        }
        return out;
    }

    boolean sameMulti(List<ItemStack> x, List<ItemStack> y) {
        if (x.size() != y.size()) return false;
        List<ItemStack> rest = new ArrayList<>(y);
        for (ItemStack a : x) {
            boolean ok = false;
            for (int i = 0; i < rest.size(); i++) {
                ItemStack b = rest.get(i);
                if (a.isSimilar(b) && a.getAmount() == b.getAmount()) { rest.remove(i); ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    ItemStack[] kitBase(String kit) {
        List<?> raw = getConfig().getList("kits." + kit + ".items");
        if (raw == null) return null;
        ItemStack[] arr = new ItemStack[raw.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = raw.get(i) instanceof ItemStack b ? b : null;
        return arr;
    }

    void startEditor(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (kitBase(kit) == null) { msg(p, "<red>Kit not found."); return; }
        queue.remove(id);
        reset(p, GameMode.ADVENTURE);
        giveKit(p, kit);
        editing.put(id, kit);
        msg(p, "<gold><bold>Kit Editor <gray>- <aqua>" + kit);
        msg(p, "<gray>Rearrange your inventory / hotbar, then click: "
                + "<click:run_command:'/kiteditor save'><green><bold>[SAVE]</bold></click> "
                + "<click:run_command:'/kiteditor reset'><yellow><bold>[RESET]</bold></click> "
                + "<click:run_command:'/kiteditor cancel'><red><bold>[CANCEL]</bold></click>");
    }

    void editorSave(Player p) {
        UUID id = p.getUniqueId();
        String kit = editing.get(id);
        if (kit == null) { msg(p, "<red>You're not editing a kit. Use the book in the lobby."); return; }
        ItemStack[] base = kitBase(kit);
        ItemStack[] cur = p.getInventory().getContents();
        if (base == null || base.length < 36 || cur.length < 36) {
            msg(p, "<red>Kit not found.");
            toLobby(p);
            return;
        }
        boolean ok = sameMulti(merge(cur, 0, 36), merge(base, 0, 36));
        for (int i = 36; i < base.length && i < cur.length && ok; i++) if (!sim(base[i], cur[i])) ok = false;
        if (!ok) {
            msg(p, "<red>Keep all your kit items in the inventory (armor stays on, nothing in the crafting grid), then save again.");
            return;
        }
        getConfig().set("layouts." + id + "." + kit, new ArrayList<>(Arrays.asList(Arrays.copyOfRange(cur, 0, 36))));
        saveConfig();
        toLobby(p);
        msg(p, "<green>Layout saved for <aqua>" + kit + "<green>!");
    }

    void kiteditor(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        UUID id = p.getUniqueId();
        switch (sub) {
            case "save" -> editorSave(p);
            case "cancel" -> {
                if (editing.containsKey(id)) { toLobby(p); msg(p, "<yellow>Editor closed."); }
                else msg(p, "<red>You're not editing a kit.");
            }
            case "reset" -> {
                String kit = editing.get(id);
                if (kit == null) { msg(p, "<red>You're not editing a kit."); return; }
                getConfig().set("layouts." + id + "." + kit, null);
                saveConfig();
                giveKit(p, kit);
                msg(p, "<yellow>Layout reset to default.");
            }
            default -> {
                if (busy(p)) { msg(p, "<red>Leave your duel first."); return; }
                openKitMenu(p, "editor", null);
            }
        }
    }

    // ───────────────────────── kit GUI ─────────────────────────

    ItemStack kitIcon(String name) {
        Material mat = null;
        String ic = getConfig().getString("kits." + name + ".icon");
        if (ic != null) mat = Material.matchMaterial(ic);
        if (mat == null) {
            List<?> raw = getConfig().getList("kits." + name + ".items");
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof ItemStack is) {
                        String n = is.getType().name();
                        if (n.contains("SWORD") || n.contains("AXE") || n.contains("MACE") || n.contains("SPEAR")
                                || n.contains("BOW") || n.contains("TRIDENT")) { mat = is.getType(); break; }
                    }
                }
                if (mat == null) {
                    for (Object o : raw) {
                        if (o instanceof ItemStack is2 && !is2.getType().isAir()) { mat = is2.getType(); break; }
                    }
                }
            }
        }
        if (mat == null) mat = Material.PAPER;
        String pretty = name.substring(0, 1).toUpperCase() + name.substring(1);
        return named(mat, "<yellow><bold>" + pretty, "<gray>Click to select");
    }

    void openKitMenu(Player p, String mode, UUID target) {
        ConfigurationSection ks = getConfig().getConfigurationSection("kits");
        if (ks == null || ks.getKeys(false).isEmpty()) {
            msg(p, "<red>No kits yet. (Admin: build a kit then /kit save [name])");
            return;
        }
        KitMenu h = new KitMenu(mode, target);
        for (String k : ks.getKeys(false)) {
            if (h.kits.size() >= 54) break;
            if (getConfig().getList("kits." + k + ".items") != null) h.kits.add(k);
        }
        int size = Math.max(9, ((h.kits.size() - 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(h, size, MM.deserialize("<dark_gray>Select a gamemode"));
        h.inv = inv;
        for (int i = 0; i < h.kits.size(); i++) inv.setItem(i, kitIcon(h.kits.get(i)));
        p.openInventory(inv);
    }

    void choose(Player p, KitMenu h, String kit) {
        switch (h.mode) {
            case "duel" -> {
                Player t = Bukkit.getPlayer(h.target);
                if (t == null) { msg(p, "<red>That player went offline."); return; }
                sendRequest(p, t, kit);
            }
            case "queue" -> joinQueue(p, kit);
            case "ffa" -> startPartyFfa(p, kit);
            case "editor" -> startEditor(p, kit);
            default -> { }
        }
    }

    // ───────────────────────── queue ─────────────────────────

    void toggleQueue(Player p) {
        UUID id = p.getUniqueId();
        if (queue.remove(id) != null) { msg(p, "<yellow>You left the queue."); update(p); return; }
        if (parties.containsKey(id)) { msg(p, "<red>Leave your party to join the solo queue."); return; }
        openKitMenu(p, "queue", null);
    }

    void joinQueue(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (parties.containsKey(id)) { msg(p, "<red>Leave your party to join the solo queue."); return; }
        UUID found = null;
        for (Map.Entry<UUID, String> en : queue.entrySet()) {
            if (!en.getKey().equals(id) && en.getValue().equals(kit)) {
                Player o = Bukkit.getPlayer(en.getKey());
                if (o != null && !busy(o)) { found = en.getKey(); break; }
            }
        }
        if (found != null) {
            if (freeArena() == null) { msg(p, "<red>No free arena right now, try again in a moment."); return; }
            Player o = Bukkit.getPlayer(found);
            queue.remove(found);
            queue.remove(id);
            start(List.of(o), List.of(p), kit);
            return;
        }
        queue.put(id, kit);
        msg(p, "<green>Searching for a <aqua>" + kit + " <green>match... <gray>(right-click the sword again to cancel)");
        update(p);
    }

    // ───────────────────────── arena regen ─────────────────────────

    void snapshot(Match m, ConfigurationSection s) {
        Location c1 = s.getLocation("corner1"), c2 = s.getLocation("corner2");
        if (c1 == null || c2 == null || c1.getWorld() == null || !c1.getWorld().equals(c2.getWorld())) return;
        BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
        if (box.getVolume() > getConfig().getInt("max-arena-volume", 300000)) {
            getLogger().warning("Arena " + m.arena + " is too big for regen (max-arena-volume).");
            return;
        }
        World w = c1.getWorld();
        BlockData[] snap = new BlockData[(int) box.getVolume()];
        int i = 0;
        for (int x = (int) box.getMinX(); x < (int) box.getMaxX(); x++)
            for (int y = (int) box.getMinY(); y < (int) box.getMaxY(); y++)
                for (int z = (int) box.getMinZ(); z < (int) box.getMaxZ(); z++)
                    snap[i++] = w.getBlockAt(x, y, z).getBlockData();
        m.box = box; m.world = w; m.snap = snap;
    }

    void restore(Match m) {
        if (m.snap == null || m.world == null) return;
        int i = 0;
        for (int x = (int) m.box.getMinX(); x < (int) m.box.getMaxX(); x++)
            for (int y = (int) m.box.getMinY(); y < (int) m.box.getMaxY(); y++)
                for (int z = (int) m.box.getMinZ(); z < (int) m.box.getMaxZ(); z++) {
                    Block b = m.world.getBlockAt(x, y, z);
                    BlockData d = m.snap[i++];
                    if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
                }
        for (Entity e : m.world.getNearbyEntities(m.box, en -> en instanceof Item || en instanceof Projectile)) {
            e.remove();
        }
        m.snap = null;
    }

    boolean inBox(Match m, Block b) {
        return m.box != null && m.box.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    boolean inAnyRegion(Block b) {
        for (Match m : activeMatches) if (inBox(m, b)) return true;
        return false;
    }

    // ───────────────────────── match flow ─────────────────────────

    void start(List<Player> t1, List<Player> t2, String kit) {
        launch(new Match(new ArrayList<>(t1), new ArrayList<>(t2), kit, false));
    }

    void startFfa(List<Player> all, String kit) {
        launch(new Match(new ArrayList<>(all), new ArrayList<>(), kit, true));
    }

    void launch(Match m) {
        List<Player> everyone = m.all();
        String arena = freeArena();
        if (arena == null) {
            for (Player p : everyone) msg(p, "<red>No free arena right now.");
            return;
        }
        m.arena = arena;
        ConfigurationSection s = Objects.requireNonNull(getConfig().getConfigurationSection("arenas." + arena));
        busyArenas.add(arena);
        activeMatches.add(m);
        for (Player p : everyone) { matches.put(p.getUniqueId(), m); queue.remove(p.getUniqueId()); editing.remove(p.getUniqueId()); }
        snapshot(m, s);

        if (m.ffa) {
            int i = 0;
            for (Player p : m.t1) prepare(p, m.kit, s.getLocation(i++ % 2 == 0 ? "spawn1" : "spawn2"));
        } else {
            for (Player p : m.t1) prepare(p, m.kit, s.getLocation("spawn1"));
            for (Player p : m.t2) prepare(p, m.kit, s.getLocation("spawn2"));
        }

        new BukkitRunnable() {
            int t = 5;
            @Override public void run() {
                if (m.state == State.ENDED) { cancel(); return; }
                if (t == 0) {
                    m.state = State.ACTIVE;
                    m.start = System.currentTimeMillis();
                    for (Player p : m.all()) title(p, "<green><bold>FIGHT!", "");
                    cancel();
                    return;
                }
                for (Player p : m.all()) {
                    title(p, "<yellow>" + t, m.ffa ? "<gray>Free for all" : "<gray>vs <white>" + label(m.enemy(p)));
                }
                t--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    void prepare(Player p, String kit, Location l) {
        reset(p, GameMode.SURVIVAL);
        p.teleport(l);
        giveKit(p, kit);
        update(p);
    }

    /** A player died / quit. Decides if the match is over. */
    void eliminate(Match m, Player p, boolean quit) {
        if (m.state == State.ENDED) return;
        if (!m.alive.remove(p.getUniqueId())) return;

        if (m.ffa) {
            if (m.alive.size() <= 1) {
                List<Player> win = new ArrayList<>(), lose = new ArrayList<>();
                for (Player x : m.all()) {
                    if (m.alive.contains(x.getUniqueId())) win.add(x); else lose.add(x);
                }
                end(m, win, lose);
            } else {
                for (Player x : m.all()) msg(x, "<red>" + p.getName() + " <gray>was eliminated. <white>" + m.alive.size() + " left");
                if (!quit) {
                    p.setGameMode(GameMode.SPECTATOR);
                    title(p, "<red><bold>ELIMINATED", "<gray>/leave to go back to the lobby");
                }
            }
            return;
        }

        List<Player> mine = m.team(p);
        boolean teamDead = true;
        for (Player x : mine) if (m.alive.contains(x.getUniqueId())) { teamDead = false; break; }
        if (teamDead) {
            end(m, m.enemy(p), mine);
        } else {
            for (Player x : m.all()) msg(x, "<red>" + p.getName() + " <gray>was eliminated.");
            if (!quit) {
                p.setGameMode(GameMode.SPECTATOR);
                title(p, "<red><bold>ELIMINATED", "<gray>Your team is still fighting");
            }
        }
    }

    void end(Match m, List<Player> winners, List<Player> losers) {
        if (m.state == State.ENDED) return;
        m.state = State.ENDED;
        long secs = m.start == 0 ? 0 : (System.currentTimeMillis() - m.start) / 1000;
        String wn = names(winners), ln = names(losers);

        for (Player w : winners) {
            if (!w.isOnline() || m.left.contains(w.getUniqueId())) continue;
            title(w, "<green><bold>VICTORY!", "<gray>You defeated <white>" + ln);
            msg(w, "<green><bold>VICTORY! <gray>You beat <white>" + ln + " <gray>in " + secs + "s");
        }
        for (Player l : losers) {
            if (!l.isOnline() || m.left.contains(l.getUniqueId())) continue;
            title(l, "<red><bold>DEFEAT", "<gray>You lost to <white>" + wn);
            msg(l, "<red><bold>DEFEAT! <gray>You lost to <white>" + wn);
            l.setGameMode(GameMode.SPECTATOR);
        }
        for (UUID id : m.specs) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) msg(sp, "<gold>Duel over! <green>" + wn + " <gray>beat <red>" + ln);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> finish(m), 60L);
    }

    void finish(Match m) {
        for (Player p : m.all()) {
            if (p.isOnline() && matches.get(p.getUniqueId()) == m) {
                matches.remove(p.getUniqueId());
                toLobby(p);
            }
        }
        for (UUID id : new ArrayList<>(m.specs)) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null && watching.get(id) == m) {
                watching.remove(id);
                toLobby(sp);
            }
        }
        restore(m);               // arena regen
        activeMatches.remove(m);
        busyArenas.remove(m.arena);
    }

    void leave(Player p) {
        UUID id = p.getUniqueId();
        queue.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        toLobby(p);
    }

    // ───────────────────────── parties ─────────────────────────

    Party partyOf(Player p) { return parties.get(p.getUniqueId()); }

    List<Player> online(Party pt) {
        List<Player> l = new ArrayList<>();
        for (UUID id : pt.members) {
            Player x = Bukkit.getPlayer(id);
            if (x != null) l.add(x);
        }
        return l;
    }

    void pmsg(Party pt, String mm) { for (Player x : online(pt)) msg(x, mm); }

    List<Player> team(Player leader) {
        Party pt = partyOf(leader);
        if (pt == null) return new ArrayList<>(List.of(leader));
        return online(pt);
    }

    void partyLeave(Player p) {
        UUID id = p.getUniqueId();
        Party pt = parties.remove(id);
        if (pt == null) return;
        pt.members.remove(id);
        pmsg(pt, "<yellow>" + p.getName() + " left the party.");
        if (pt.members.size() <= 1) {
            for (UUID m : pt.members) {
                parties.remove(m);
                Player x = Bukkit.getPlayer(m);
                if (x != null) msg(x, "<red>Party disbanded.");
            }
            return;
        }
        if (pt.leader.equals(id)) {
            pt.leader = pt.members.get(0);
            Player nl = Bukkit.getPlayer(pt.leader);
            pmsg(pt, "<yellow>" + (nl != null ? nl.getName() : "?") + " is now the party leader.");
        }
    }

    void startPartyFfa(Player p, String kit) {
        Party pt = partyOf(p);
        if (pt == null || !pt.leader.equals(p.getUniqueId())) { msg(p, "<red>Only the party leader can start an FFA."); return; }
        List<Player> all = online(pt);
        if (all.size() < 2) { msg(p, "<red>You need at least 2 players in the party."); return; }
        if (anyBusy(all)) { msg(p, "<red>Someone in your party is busy."); return; }
        startFfa(all, kit);
    }

    void party(Player p, String[] a) {
        UUID id = p.getUniqueId();
        Party pt = parties.get(id);
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (pt != null) { msg(p, "<red>You're already in a party."); return; }
                queue.remove(id);
                Party n = new Party();
                n.leader = id;
                n.members.add(id);
                parties.put(id, n);
                msg(p, "<green>Party created! <gray>/party invite [player] | /party ffa");
            }
            case "invite" -> {
                if (pt == null) { msg(p, "<red>Create a party first: /party create"); return; }
                if (!pt.leader.equals(id)) { msg(p, "<red>Only the leader can invite."); return; }
                if (a.length < 2) { msg(p, "<red>/party invite [player]"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || t.equals(p)) { msg(p, "<red>Player not found."); return; }
                if (parties.containsKey(t.getUniqueId())) { msg(p, "<red>That player is already in a party."); return; }
                if (pt.members.size() >= getConfig().getInt("party-max", 4)) { msg(p, "<red>Party is full."); return; }
                pinvites.put(t.getUniqueId(), new PInv(pt, System.currentTimeMillis()));
                msg(p, "<green>Invited <white>" + t.getName());
                msg(t, "<gold>" + p.getName() + " <yellow>invited you to their party! "
                        + "<click:run_command:'/party accept'><green><bold>[ACCEPT]</bold></click>");
            }
            case "accept" -> {
                PInv inv = pinvites.remove(id);
                if (inv == null || System.currentTimeMillis() - inv.time() > 60_000) {
                    msg(p, "<red>No pending party invite."); return;
                }
                if (pt != null) { msg(p, "<red>Leave your party first."); return; }
                Party target = inv.party();
                if (parties.get(target.leader) != target) { msg(p, "<red>That party no longer exists."); return; }
                if (target.members.size() >= getConfig().getInt("party-max", 4)) { msg(p, "<red>Party is full."); return; }
                queue.remove(id);
                target.members.add(id);
                parties.put(id, target);
                pmsg(target, "<green>" + p.getName() + " joined the party!");
            }
            case "leave" -> {
                if (pt == null) { msg(p, "<red>You're not in a party."); return; }
                msg(p, "<yellow>You left the party.");
                partyLeave(p);
            }
            case "kick" -> {
                if (pt == null || !pt.leader.equals(id)) { msg(p, "<red>Only the party leader can kick."); return; }
                if (a.length < 2) { msg(p, "<red>/party kick [player]"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || t.equals(p) || !pt.members.contains(t.getUniqueId())) {
                    msg(p, "<red>That player isn't in your party."); return;
                }
                msg(t, "<red>You were kicked from the party.");
                partyLeave(t);
            }
            case "disband" -> {
                if (pt == null || !pt.leader.equals(id)) { msg(p, "<red>Only the party leader can disband."); return; }
                pmsg(pt, "<red>Party disbanded.");
                for (UUID m : new ArrayList<>(pt.members)) parties.remove(m);
            }
            case "ffa" -> {
                if (pt == null || !pt.leader.equals(id)) { msg(p, "<red>Only the party leader can start an FFA."); return; }
                if (online(pt).size() < 2) { msg(p, "<red>You need at least 2 players in the party."); return; }
                openKitMenu(p, "ffa", null);
            }
            case "chat" -> {
                if (pt == null) { msg(p, "<red>You're not in a party."); return; }
                if (a.length < 2) { msg(p, "<red>/party chat [message]"); return; }
                String text = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                Component c = MM.deserialize("<light_purple>[Party] <white>" + p.getName() + "<gray>: <white>")
                        .append(Component.text(text));
                for (Player x : online(pt)) x.sendMessage(c);
            }
            default -> { // list
                if (pt == null) {
                    msg(p, "<gray>You're not in a party. <white>/party create <gray>| /party accept");
                    return;
                }
                List<String> n = new ArrayList<>();
                for (Player x : online(pt)) n.add((x.getUniqueId().equals(pt.leader) ? "★" : "") + x.getName());
                msg(p, "<gold>Party (" + pt.members.size() + "/" + getConfig().getInt("party-max", 4) + "): <white>"
                        + String.join(", ", n) + " <gray>| /party ffa");
            }
        }
    }

    // ───────────────────────── commands ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        switch (c.getName().toLowerCase()) {
            case "duel" -> duel(p, a);
            case "spectate" -> spec(p, a);
            case "leave" -> leave(p);
            case "party" -> party(p, a);
            case "settings" -> openSettings(p);
            case "kiteditor" -> kiteditor(p, a);
            case "setlobby" -> {
                getConfig().set("lobby", p.getLocation());
                saveConfig();
                msg(p, "<green>Lobby set!");
            }
            case "arena" -> arena(p, a);
            case "kit" -> kit(p, a);
            default -> { }
        }
        return true;
    }

    void duel(Player p, String[] a) {
        if (a.length == 0) {
            msg(p, "<red>/duel [player] [kit] <gray>| /duel accept | /duel deny");
            return;
        }
        Party mp = partyOf(p);
        if (mp != null && !mp.leader.equals(p.getUniqueId())) {
            msg(p, "<red>Only your party leader can start or accept duels.");
            return;
        }
        if (a[0].equalsIgnoreCase("accept")) {
            Req r = requests.remove(p.getUniqueId());
            if (r == null || System.currentTimeMillis() - r.time() > 60_000) {
                msg(p, "<red>No pending duel request.");
                return;
            }
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) { msg(p, "<red>That player is offline."); return; }
            List<Player> t1 = team(from), t2 = team(p);
            if (t1.size() != t2.size()) { msg(p, "<red>Both parties must be the same size."); return; }
            if (anyBusy(t1) || anyBusy(t2)) { msg(p, "<red>Someone is already busy."); return; }
            start(t1, t2, r.kit());
            return;
        }
        if (a[0].equalsIgnoreCase("deny")) {
            Req r = requests.remove(p.getUniqueId());
            if (r != null) {
                Player f = Bukkit.getPlayer(r.from());
                if (f != null) msg(f, "<red>" + p.getName() + " declined your duel.");
            }
            msg(p, "<gray>Declined.");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(p)) { msg(p, "<red>Player not found."); return; }
        if (a.length > 1) sendRequest(p, t, a[1].toLowerCase());
        else openKitMenu(p, "duel", t.getUniqueId());   // GUI with all gamemodes
    }

    void sendRequest(Player p, Player t, String kit) {
        if (!setting(t, "requests")) { msg(p, "<red>That player isn't accepting duel requests."); return; }
        Party tp = partyOf(t);
        if (tp != null && !tp.leader.equals(t.getUniqueId())) {
            Player lead = Bukkit.getPlayer(tp.leader);
            msg(p, "<red>That player is in a party. Challenge their leader" + (lead != null ? ": <white>" + lead.getName() : "."));
            return;
        }
        List<Player> mine = team(p), theirs = team(t);
        if (mine.size() != theirs.size()) { msg(p, "<red>Both sides must be the same size (party vs party, solo vs solo)."); return; }
        if (anyBusy(mine) || anyBusy(theirs)) { msg(p, "<red>Someone is already in a duel / spectating."); return; }
        if (getConfig().getList("kits." + kit + ".items") == null) { msg(p, "<red>Kit not found."); return; }
        requests.put(t.getUniqueId(), new Req(p.getUniqueId(), kit, System.currentTimeMillis()));
        String size = mine.size() > 1 ? " <gray>(" + mine.size() + "v" + mine.size() + ")" : "";
        msg(p, "<green>Duel request sent to <white>" + t.getName() + " <gray>(kit: " + kit + ")" + size);
        msg(t, "<gold>" + p.getName() + " <yellow>challenged you!" + size + " <gray>Kit: <aqua>" + kit
                + " <click:run_command:'/duel accept'><green><bold>[ACCEPT]</bold></click>"
                + " <click:run_command:'/duel deny'><red><bold>[DENY]</bold></click>");
    }

    void spec(Player p, String[] a) {
        if (a.length == 0) { msg(p, "<red>/spectate [player]"); return; }
        UUID id = p.getUniqueId();
        if (matches.containsKey(id)) { msg(p, "<red>You can't spectate while dueling."); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        Match m = t == null ? null : matches.get(t.getUniqueId());
        if (m == null) { msg(p, "<red>That player isn't in a duel."); return; }
        queue.remove(id);
        Match old = watching.remove(id);
        if (old != null) old.specs.remove(id);
        m.specs.add(id);
        watching.put(id, m);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(t);
        msg(p, "<green>Spectating <white>" + t.getName() + "<green>. <gray>/leave to exit.");
    }

    void arena(Player p, String[] a) {
        String help = "<red>/arena [create|spawn1|spawn2|corner1|corner2|delete|list] [name]";
        if (a.length == 0) { msg(p, help); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection s = getConfig().getConfigurationSection("arenas");
            msg(p, "<yellow>Arenas: <white>" + (s == null ? "none" : String.join(", ", s.getKeys(false))));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give an arena name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        String path = "arenas." + n;
        switch (sub) {
            case "create" -> {
                getConfig().set(path + ".spawn1", p.getLocation());
                getConfig().set(path + ".spawn2", p.getLocation());
            }
            case "spawn1" -> getConfig().set(path + ".spawn1", p.getLocation());
            case "spawn2" -> getConfig().set(path + ".spawn2", p.getLocation());
            case "corner1" -> getConfig().set(path + ".corner1", p.getLocation());
            case "corner2" -> getConfig().set(path + ".corner2", p.getLocation());
            case "delete" -> getConfig().set(path, null);
            default -> { msg(p, help); return; }
        }
        saveConfig();
        msg(p, "<green>Done.");
        if (sub.startsWith("corner")) {
            Location c1 = getConfig().getLocation(path + ".corner1");
            Location c2 = getConfig().getLocation(path + ".corner2");
            if (c1 != null && c2 != null && c1.getWorld() != null && c1.getWorld().equals(c2.getWorld())) {
                BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
                msg(p, "<gray>Arena region: <white>" + (int) box.getVolume() + " blocks <gray>(max "
                        + getConfig().getInt("max-arena-volume", 300000) + ")");
            }
        }
    }

    void kit(Player p, String[] a) {
        if (a.length == 0) { msg(p, "<red>/kit [save|load|delete|icon|list] [name]"); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection s = getConfig().getConfigurationSection("kits");
            msg(p, "<yellow>Kits: <white>" + (s == null ? "none" : String.join(", ", s.getKeys(false))));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give a kit name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        switch (sub) {
            case "save" -> {
                getConfig().set("kits." + n + ".items", Arrays.asList(p.getInventory().getContents()));
                saveConfig();
                msg(p, "<green>Kit <white>" + n + " <green>saved from your inventory (armor + offhand included).");
            }
            case "load" -> giveKit(p, n);
            case "delete" -> {
                getConfig().set("kits." + n, null);
                saveConfig();
                msg(p, "<green>Kit deleted.");
            }
            case "icon" -> {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir() || getConfig().getList("kits." + n + ".items") == null) {
                    msg(p, "<red>Hold an item and use an existing kit name.");
                    return;
                }
                getConfig().set("kits." + n + ".icon", hand.name());
                saveConfig();
                msg(p, "<green>Menu icon for <white>" + n + " <green>set to <white>" + hand.name());
            }
            default -> msg(p, "<red>/kit [save|load|delete|icon|list] [name]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String name = c.getName().toLowerCase();
        if (a.length == 1) {
            switch (name) {
                case "duel" -> { out.addAll(List.of("accept", "deny")); Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName())); }
                case "spectate" -> matches.values().forEach(m -> m.all().forEach(p -> out.add(p.getName())));
                case "arena" -> out.addAll(List.of("create", "spawn1", "spawn2", "corner1", "corner2", "delete", "list"));
                case "kit" -> out.addAll(List.of("save", "load", "delete", "icon", "list"));
                case "party" -> out.addAll(List.of("create", "invite", "accept", "leave", "kick", "disband", "list", "chat", "ffa"));
                case "kiteditor" -> out.addAll(List.of("save", "reset", "cancel"));
                default -> { }
            }
        } else if (a.length == 2) {
            switch (name) {
                case "duel", "kit" -> {
                    ConfigurationSection k = getConfig().getConfigurationSection("kits");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "arena" -> {
                    ConfigurationSection k = getConfig().getConfigurationSection("arenas");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "party" -> {
                    if (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("kick"))
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                default -> { }
            }
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ───────────────────────── scoreboard + tab ─────────────────────────

    Scoreboard board(Player p) {
        return boards.computeIfAbsent(p.getUniqueId(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("duels", Criteria.DUMMY,
                    MM.deserialize("<gradient:#ff5555:#ffaa00><bold>DUELS"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < 12; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                sb.registerNewTeam("l" + i).addEntry(entry);
            }
            Team nt = sb.registerNewTeam("nt");
            nt.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            p.setScoreboard(sb);
            return sb;
        });
    }

    int aliveCount(Match m, List<Player> team) {
        int n = 0;
        for (Player x : team) if (m.alive.contains(x.getUniqueId())) n++;
        return n;
    }

    void update(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        Match w = watching.get(id);
        List<String> L = new ArrayList<>();
        L.add("");
        if (m != null) {
            long s = m.state == State.ACTIVE ? (System.currentTimeMillis() - m.start) / 1000 : 0;
            String time = String.format("%02d:%02d", s / 60, s % 60);
            if (m.ffa) {
                L.add("<white>Mode: <gold>FFA");
                L.add("<white>Kit: <aqua>" + m.kit);
                L.add("<white>Time: <yellow>" + time);
                L.add("");
                L.add("<white>Alive: <green>" + m.alive.size() + "<gray>/" + m.t1.size());
            } else {
                List<Player> en = m.enemy(p);
                L.add("<white>Opponent: <red>" + label(en));
                L.add("<white>Kit: <aqua>" + m.kit);
                L.add("<white>Time: <yellow>" + time);
                L.add("");
                if (m.t1.size() > 1) {
                    L.add("<white>Alive: <green>" + aliveCount(m, m.team(p)) + "<gray> vs <red>" + aliveCount(m, en));
                } else {
                    L.add("<white>You: <green>" + p.getPing() + "ms");
                    L.add("<white>Them: <red>" + en.get(0).getPing() + "ms");
                }
            }
        } else if (w != null) {
            L.add("<white>Spectating");
            L.add(w.ffa ? "<white>FFA <gray>(" + w.alive.size() + " alive)"
                    : "<white>" + label(w.t1) + " <gray>vs <white>" + label(w.t2));
            L.add("<white>Kit: <aqua>" + w.kit);
            L.add("<gray>/leave to exit");
        } else {
            L.add("<white>Online: <green>" + Bukkit.getOnlinePlayers().size());
            L.add("<white>Fighting: <red>" + matches.size());
            L.add("<white>Ping: <green>" + p.getPing() + "ms");
            Party pt = parties.get(id);
            if (pt != null) L.add("<white>Party: <gold>" + pt.members.size());
            String q = queue.get(id);
            if (q != null) L.add("<white>Queue: <yellow>" + q);
        }
        L.add("");
        L.add(getConfig().getString("footer", "<yellow>play.example.net"));

        Scoreboard sb = board(p);
        Objective obj = sb.getObjective("duels");
        if (obj != null) {
            obj.setDisplaySlot(setting(p, "scoreboard") ? DisplaySlot.SIDEBAR : null);
            for (int i = 0; i < 12; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                Team t = sb.getTeam("l" + i);
                if (t == null) continue;
                if (i < L.size()) {
                    t.prefix(MM.deserialize(L.get(i)));
                    obj.getScore(entry).setScore(L.size() - i);
                } else {
                    sb.resetScores(entry);
                }
            }
        }

        // nametags above heads (per viewer)
        Team nt = sb.getTeam("nt");
        if (nt != null) {
            boolean show = setting(p, "nametags");
            for (String en : new ArrayList<>(nt.getEntries())) {
                if (show || Bukkit.getPlayerExact(en) == null || en.equals(p.getName())) nt.removeEntry(en);
            }
            if (!show) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!o.equals(p) && !nt.hasEntry(o.getName())) nt.addEntry(o.getName());
                }
            }
        }
        visibility(p);

        // TAB
        p.sendPlayerListHeaderAndFooter(
                MM.deserialize("<gradient:#ff5555:#ffaa00><bold>DUELS</bold></gradient><newline><gray>Online: <white>"
                        + Bukkit.getOnlinePlayers().size()),
                MM.deserialize("<gray>Ping: <green>" + p.getPing() + "ms"));
        p.playerListName(MM.deserialize((m != null ? "<red>[Fighting] " : w != null ? "<gray>[Spec] " : "<green>")
                + "<white>" + p.getName()));
    }

    // ───────────────────────── events ─────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { toLobby(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        queue.remove(id);
        editing.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        partyLeave(p);
        pinvites.remove(id);
        boards.remove(id);
        requests.remove(id);
    }

    // lobby hotbar items
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (busy(p) || editing.containsKey(p.getUniqueId()) || p.getGameMode() != GameMode.ADVENTURE) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        if (it.getType() == Material.IRON_SWORD) {
            e.setCancelled(true);
            toggleQueue(p);
        } else if (it.getType() == Material.NAME_TAG) {
            e.setCancelled(true);
            party(p, new String[]{partyOf(p) == null ? "create" : "list"});
        } else if (it.getType() == Material.BOOK) {
            e.setCancelled(true);
            openKitMenu(p, "editor", null);
        } else if (it.getType() == Material.COMPARATOR) {
            e.setCancelled(true);
            openSettings(p);
        }
    }

    // GUI clicks + lobby inventory lock
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof KitMenu h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            int slot = e.getSlot();
            if (slot < 0 || slot >= h.kits.size()) return;
            String kit = h.kits.get(slot);
            p.closeInventory();
            choose(p, h, kit);
            return;
        }
        if (e.getView().getTopInventory().getHolder() instanceof SettingsMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            for (int i = 0; i < SETTING_SLOTS.length; i++) {
                if (e.getSlot() == SETTING_SLOTS[i]) {
                    String key = SETTINGS[i][0];
                    getConfig().set("settings." + p.getUniqueId() + "." + key, !setting(p, key));
                    saveConfig();
                    fillSettings(p, e.getView().getTopInventory());
                    update(p);
                }
            }
            return;
        }
        if (!matches.containsKey(p.getUniqueId()) && !editing.containsKey(p.getUniqueId()) && !admin(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder hd = e.getView().getTopInventory().getHolder();
        if (hd instanceof KitMenu || hd instanceof SettingsMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!matches.containsKey(e.getPlayer().getUniqueId()) && !admin(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m != null && m.state == State.COUNTDOWN && e.hasChangedPosition()) e.setCancelled(true);
    }

    // no friendly fire (teams only, FFA is everyone vs everyone)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player d = null;
        if (e.getDamager() instanceof Player pl) d = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2) d = pl2;
        if (d == null) return;
        Match m = matches.get(v.getUniqueId());
        if (m != null && !m.ffa && m.team(v) == m.team(d)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(lobby());
            return;
        }
        if (m.state != State.ACTIVE || !m.alive.contains(p.getUniqueId())) { e.setCancelled(true); return; }
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            eliminate(m, p, false);
        }
    }

    // Wind charge reset: instant re-throw, no cooldown
    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!getConfig().getBoolean("wind-charge-reset", true)) return;
        if (e.getEntity() instanceof WindCharge wc && wc.getShooter() instanceof Player p
                && matches.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.WIND_CHARGE, 0));
        }
    }

    // ── building: only inside the arena region, only while the fight is active ──
    boolean admin(Player p) { return p.isOp() && p.getGameMode() == GameMode.CREATIVE; }

    boolean canBuild(Player p, Block b) {
        if (admin(p)) return true;
        Match m = matches.get(p.getUniqueId());
        return m != null && m.state == State.ACTIVE && m.alive.contains(p.getUniqueId()) && inBox(m, b);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketEmpty(PlayerBucketEmptyEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketFill(PlayerBucketFillEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    // explosions only destroy blocks inside an active arena region (those get regenerated)
    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }
    @EventHandler public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }

    // liquids can't flow out of the arena
    @EventHandler
    public void onFlow(BlockFromToEvent e) {
        if (activeMatches.isEmpty()) return;
        for (Match m : activeMatches) {
            if (inBox(m, e.getBlock()) && !inBox(m, e.getToBlock())) { e.setCancelled(true); return; }
        }
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!admin(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !admin(p)) e.setCancelled(true);
    }
}
