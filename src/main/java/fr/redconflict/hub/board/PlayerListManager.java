package fr.redconflict.hub.board;

import fr.redconflict.hub.RedConflictHub;
import fr.redconflict.hub.util.RankService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Affichage standardise de la playerlist, calque sur OriginsFightCore.
 *
 * <p>Sur une tache periodique, chaque joueur recoit un scoreboard dont les Teams portent le
 * prefixe de grade (LuckPerms via Vault) de chaque joueur en ligne. Le prefixe de team colore
 * a la fois le nom dans la tablist ET le nom au-dessus de la tete. Le nom de la team commence par
 * un index de grade (2 chiffres) pour trier la tablist du plus haut au plus bas grade.
 *
 * <p>L'en-tete et le pied de la tablist sont envoyes via {@link RedConflictHub#applyTablist(Player)}
 * (paquet NMS). Aucun sidebar : le HUB reste un serveur de transition minimaliste.
 */
public class PlayerListManager {

    private final RedConflictHub plugin;
    private final RankService ranks;

    private BukkitTask task;

    public PlayerListManager(RedConflictHub plugin, RankService ranks) {
        this.plugin = plugin;
        this.ranks = ranks;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("nametags.enabled", true);
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    public void start() {
        update();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(main);
    }

    public void restart() {
        stop();
        start();
    }

    private void update() {
        // Vault peut s'enregistrer apres notre activation : on retente le hook tant qu'il manque.
        if (!ranks.isAvailable()) ranks.hook();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(board);
            }
            if (enabled()) setupTeams(board);
            plugin.applyTablist(viewer);
        }
    }

    /**
     * Cree/maj une team par joueur en ligne sur {@code board}, portant son prefixe de grade.
     * Nom de team = index de grade (2 chiffres) + pseudo, tronque a 16 caracteres, pour trier
     * la tablist par grade.
     */
    private void setupTeams(Scoreboard board) {
        boolean sort = plugin.getConfig().getBoolean("nametags.sort-by-rank", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            String sortPrefix = sort ? String.format("%02d", Math.min(ranks.sortIndex(p), 99)) : "";
            String teamName = cut(sortPrefix + p.getName(), 16);
            String prefix = cut(ranks.prefix(p), 16);

            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            if (!prefix.equals(team.getPrefix())) team.setPrefix(prefix);

            Team current = board.getEntryTeam(p.getName());
            if (current == null || !current.getName().equals(teamName)) {
                removeFromTeams(board, p.getName());
                team.addEntry(p.getName());
            }
        }

        // Nettoyage des teams orphelines (joueurs deconnectes).
        for (Team t : board.getTeams()) {
            if (t.getEntries().isEmpty()) t.unregister();
        }
    }

    private static void removeFromTeams(Scoreboard board, String entry) {
        for (Team t : board.getTeams()) {
            if (t.hasEntry(entry)) t.removeEntry(entry);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
