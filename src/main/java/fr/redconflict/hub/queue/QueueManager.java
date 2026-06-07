package fr.redconflict.hub.queue;

import fr.redconflict.hub.RedConflictHub;
import fr.redconflict.hub.util.TitleUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Gestionnaire de la file d'attente interne du HUB.
 *
 * <p>Les joueurs rejoignent la file en cliquant sur l'item de jonction (ou via auto-join).
 * Un traitement periodique envoie les premiers joueurs vers le serveur faction.
 * Toutes les {@code queue.title-interval-ticks} ticks, chaque joueur en file reçoit
 * un title / actionbar indiquant sa position.
 *
 * <p>Les joueurs possedant la permission {@link #QUEUE_BYPASS} sont places en
 * <strong>1ère position</strong> des leur entree en file.
 */
public final class QueueManager {

    /** Permission pour bypasser la file et être place en 1ère position. */
    public static final String QUEUE_BYPASS = "hub.queue.bypass";

    private final RedConflictHub plugin;

    /** File ordonnee (UUID des joueurs connectes). */
    private final LinkedList<UUID> queue = new LinkedList<>();

    private BukkitTask titleTask;
    private BukkitTask sendTask;

    public QueueManager(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────────

    public void start() {
        long titleInterval = plugin.getConfig().getLong("queue.title-interval-ticks", 40L);
        long sendInterval  = plugin.getConfig().getLong("queue.send-interval-ticks",  100L);

        titleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTitles, 20L, titleInterval);
        sendTask  = Bukkit.getScheduler().runTaskTimer(plugin, this::processSend,  60L, sendInterval);
    }

    public void stop() {
        if (titleTask != null) { titleTask.cancel(); titleTask = null; }
        if (sendTask  != null) { sendTask.cancel();  sendTask  = null; }
    }

    public void restart() {
        stop();
        start();
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Ajoute un joueur a la file.
     * Si {@link #QUEUE_BYPASS}, il est place en 1ère position ; sinon en derniere.
     *
     * @return {@code true} si ajoute, {@code false} s'il etait deja en file.
     */
    public boolean addPlayer(Player player) {
        if (isInQueue(player)) return false;

        if (player.hasPermission(QUEUE_BYPASS)) {
            queue.addFirst(player.getUniqueId());
        } else {
            queue.addLast(player.getUniqueId());
        }

        int pos   = getPosition(player);
        int total = queue.size();

        // Title + message d'entree en file
        if (player.hasPermission(QUEUE_BYPASS)) {
            String t = plugin.color(plugin.getConfig().getString("queue.bypass-title", "&6&lBYPASS FILE"));
            String s = plugin.color(plugin.getConfig().getString("queue.bypass-sub",   "&7Place en &c1ère position"));
            TitleUtil.sendTitle(player, t, s, 5, 50, 10);
            player.sendMessage(plugin.prefixed(plugin.getConfig().getString(
                    "queue.bypass-message", "&6Tu as bypasse la file et es en 1ère position !")));
        } else {
            String t = plugin.color(repl(plugin.getConfig().getString("queue.join-title",
                    "&a&lFILE D'ATTENTE"), pos, total));
            String s = plugin.color(repl(plugin.getConfig().getString("queue.join-sub",
                    "&7Tu es en position &c#%pos%"), pos, total));
            TitleUtil.sendTitle(player, t, s, 5, 50, 10);
            player.sendMessage(plugin.prefixed(repl(plugin.getConfig().getString(
                    "queue.join-message", "&7File d'attente rejointe — position &c#%pos% &7sur &f%total%"),
                    pos, total)));
        }
        return true;
    }

    /**
     * Retire un joueur de la file (deconnexion, commande admin…).
     *
     * @return {@code true} si le joueur etait en file.
     */
    public boolean removePlayer(Player player) {
        return queue.remove(player.getUniqueId());
    }

    /** Retire un UUID de la file (joueurs hors-ligne). */
    public boolean removePlayer(UUID uuid) {
        return queue.remove(uuid);
    }

    public boolean isInQueue(Player player) {
        return queue.contains(player.getUniqueId());
    }

    /**
     * Position du joueur dans la file (1 = premier), ou {@code -1} s'il n'y est pas.
     */
    public int getPosition(Player player) {
        int idx = 0;
        for (UUID u : queue) {
            idx++;
            if (u.equals(player.getUniqueId())) return idx;
        }
        return -1;
    }

    public int getSize() {
        return queue.size();
    }

    /** Instantane immuable de la file. */
    public List<UUID> getQueueSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    /** Vide entierement la file. */
    public void clearQueue() {
        queue.clear();
    }

    // ── Taches internes ───────────────────────────────────────────────────────

    /** Supprime les UUID de joueurs hors-ligne. */
    private void cleanOffline() {
        queue.removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    /** Envoie un title/actionbar de position a chaque joueur en file. */
    private void updateTitles() {
        cleanOffline();
        List<UUID> snap  = new ArrayList<>(queue);
        int        total = snap.size();
        for (int i = 0; i < snap.size(); i++) {
            Player p = Bukkit.getPlayer(snap.get(i));
            if (p == null) continue;
            sendPositionFeedback(p, i + 1, total);
        }
    }

    private void sendPositionFeedback(Player p, int pos, int total) {
        String actionBar = plugin.getConfig().getString("queue.actionbar", "");
        if (actionBar != null && !actionBar.isEmpty()) {
            TitleUtil.sendActionBar(p, plugin.color(repl(actionBar, pos, total)));
        } else {
            String t = plugin.color(repl(
                    plugin.getConfig().getString("queue.title-main", "&c&lFILE D'ATTENTE"), pos, total));
            String s = plugin.color(repl(
                    plugin.getConfig().getString("queue.title-sub",  "&7Position &c#%pos% &7sur &f%total%"), pos, total));
            TitleUtil.sendTitle(p, t, s, 0, 35, 5);
        }
    }

    /** Envoie les premiers joueurs de la file vers le serveur faction. */
    private void processSend() {
        cleanOffline();
        if (queue.isEmpty()) return;

        int batch = plugin.getConfig().getInt("queue.batch-size", 1);
        for (int i = 0; i < batch && !queue.isEmpty(); i++) {
            UUID   first = queue.peekFirst();
            Player p     = Bukkit.getPlayer(first);
            if (p == null) {
                queue.pollFirst();
                i--; // ne compte pas comme un envoi effectif
                continue;
            }
            queue.pollFirst();
            plugin.sendToFaction(p);
        }
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    /** Remplace %pos% et %total% dans une chaine. */
    private static String repl(String s, int pos, int total) {
        return s == null ? "" : s.replace("%pos%", String.valueOf(pos))
                                 .replace("%total%", String.valueOf(total));
    }
}

