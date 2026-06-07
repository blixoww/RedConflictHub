package fr.redconflict.hub;

import fr.redconflict.hub.board.PlayerListManager;
import fr.redconflict.hub.commands.HubCommand;
import fr.redconflict.hub.listeners.CommandBlockerListener;
import fr.redconflict.hub.listeners.HubPlayerListener;
import fr.redconflict.hub.listeners.HubProtectionListener;
import fr.redconflict.hub.queue.QueueManager;
import fr.redconflict.hub.util.RankService;
import fr.redconflict.hub.util.TabListUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin du serveur HUB de Red Conflict.
 *
 * <p>Transforme un serveur Spigot en lobby totalement verrouille (aucune interaction, aucune
 * commande pour les non-staff) et offre un item / un auto-join pour entrer dans la file d'attente
 * du serveur faction (file geree cote proxy par VelocityHUB).
 */
public final class RedConflictHub extends JavaPlugin {

    /** Permission qui contourne toutes les protections du HUB (staff). */
    public static final String BYPASS = "hub.bypass";
    /** Permission d'acces a la commande {@code /hub}. */
    public static final String ADMIN = "hub.admin";
    /** Permission pour bypass la file et etre place en 1ère position. */
    public static final String QUEUE_BYPASS = "hub.queue.bypass";

    private static RedConflictHub instance;
    private ServerConnector connector;
    private RankService ranks;
    private PlayerListManager playerList;
    private QueueManager queueManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.connector = new ServerConnector(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // Grades (Vault/LuckPerms) + playerlist standardisee (prefixes, tri par grade, tablist).
        this.ranks = new RankService(this);
        this.ranks.hook();
        this.playerList = new PlayerListManager(this, this.ranks);
        this.playerList.start();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new HubProtectionListener(), this);
        pm.registerEvents(new HubPlayerListener(this), this);
        pm.registerEvents(new CommandBlockerListener(this), this);

        HubCommand hubCommand = new HubCommand(this);
        if (getCommand("hub") != null) {
            getCommand("hub").setExecutor(hubCommand);
            getCommand("hub").setTabCompleter(hubCommand);
        }

        // File d'attente interne
        this.queueManager = new QueueManager(this);
        if (getConfig().getBoolean("queue.enabled", true)) {
            this.queueManager.start();
        }

        applyWorldRules();

        getLogger().info("RedConflictHub active — faction cible : " + getConfig().getString("faction-server"));
    }

    @Override
    public void onDisable() {
        if (queueManager != null) queueManager.stop();
        if (playerList  != null) playerList.stop();
        instance = null;
    }

    public static RedConflictHub getInstance() {
        return instance;
    }

    public ServerConnector getConnector() {
        return connector;
    }

    public RankService getRanks() {
        return ranks;
    }

    public PlayerListManager getPlayerList() {
        return playerList;
    }

    public QueueManager getQueue() {
        return queueManager;
    }

    /** Recharge la config depuis le disque et reapplique les reglages de monde. */
    public void reload() {
        reloadConfig();
        applyWorldRules();
        if (ranks != null) ranks.hook();
        if (playerList != null) playerList.restart();
        if (queueManager != null) {
            if (getConfig().getBoolean("queue.enabled", true)) {
                queueManager.restart();
            } else {
                queueManager.stop();
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) applyTablist(p);
    }

    // ── Connexion vers le faction ──────────────────────────────────────────────

    /** Envoie le joueur vers le serveur faction configure (via le proxy + file VelocityHUB). */
    public void sendToFaction(Player player) {
        player.sendMessage(prefixed(getConfig().getString("messages.joining", "Connexion...")));
        connector.send(player, getConfig().getString("faction-server", "faction"));
    }

    // ── Monde (heure verrouillee) ──────────────────────────────────────────────

    /** Applique le verrouillage de l'heure du jour a tous les mondes si active. */
    private void applyWorldRules() {
        if (!getConfig().getBoolean("world.lock-time", true)) return;
        long time = getConfig().getLong("world.time", 6000L);
        for (World w : Bukkit.getWorlds()) {
            w.setGameRuleValue("doDaylightCycle", "false");
            w.setTime(time);
        }
    }

    // ── Spawn du lobby ──────────────────────────────────────────────────────────

    /** Le spawn configure via {@code /hub setspawn}, ou {@code null} s'il n'est pas defini. */
    public Location getHubSpawn() {
        if (!getConfig().getBoolean("spawn.set", false)) return null;
        World w = Bukkit.getWorld(getConfig().getString("spawn.world", ""));
        if (w == null) return null;
        return new Location(w,
                getConfig().getDouble("spawn.x"),
                getConfig().getDouble("spawn.y"),
                getConfig().getDouble("spawn.z"),
                (float) getConfig().getDouble("spawn.yaw"),
                (float) getConfig().getDouble("spawn.pitch"));
    }

    /** Spawn du HUB, ou a defaut le spawn du monde principal. */
    public Location effectiveSpawn() {
        Location custom = getHubSpawn();
        if (custom != null) return custom;
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** Enregistre {@code loc} comme spawn du HUB et sauvegarde la config. */
    public void setHubSpawn(Location loc) {
        getConfig().set("spawn.set", true);
        getConfig().set("spawn.world", loc.getWorld().getName());
        getConfig().set("spawn.x", loc.getX());
        getConfig().set("spawn.y", loc.getY());
        getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.yaw", (double) loc.getYaw());
        getConfig().set("spawn.pitch", (double) loc.getPitch());
        saveConfig();
    }

    // ── Tablist ──────────────────────────────────────────────────────────────────

    /** Pousse l'entete/pied de tablist configure vers {@code player}. */
    public void applyTablist(Player player) {
        if (!getConfig().getBoolean("tablist.enabled", true)) return;
        String header = renderLines(player, getConfig().getStringList("tablist.header"));
        String footer = renderLines(player, getConfig().getStringList("tablist.footer"));
        TabListUtil.send(player, header, footer);
    }

    private String renderLines(Player player, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(applyPlaceholders(player, lines.get(i)));
        }
        return color(sb.toString());
    }

    public String applyPlaceholders(Player player, String s) {
        if (s == null) return "";
        String prefix = (ranks != null && player != null) ? ranks.prefix(player) : "";
        String grade  = (ranks != null && player != null) ? ranks.group(player) : "";
        return s.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%prefix%", prefix)
                .replace("%grade%", grade)
                .replace("%player%", player == null ? "" : player.getName());
    }

    // ── Helpers messages / item ──────────────────────────────────────────────────

    public String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Prefixe un message avec le prefixe Red Conflict de la config. */
    public String prefixed(String msg) {
        return color(getConfig().getString("messages.prefix", "")) + color(msg);
    }

    /** Construit l'item de jonction d'apres la config, ou {@code null} s'il est desactive. */
    public ItemStack buildJoinItem() {
        if (!getConfig().getBoolean("join-item.enabled", true)) return null;
        Material mat = Material.matchMaterial(getConfig().getString("join-item.material", "COMPASS"));
        if (mat == null) mat = Material.COMPASS;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("join-item.name", "&cRejoindre")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("join-item.lore")) lore.add(color(line));
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public int joinItemSlot() {
        return Math.max(0, Math.min(8, getConfig().getInt("join-item.slot", 4)));
    }
}
