package fr.redconflict.hub.commands;

import fr.redconflict.hub.RedConflictHub;
import fr.redconflict.hub.queue.QueueManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Commande d'administration {@code /hub} reservee au staff ({@link RedConflictHub#ADMIN}).
 *
 * <ul>
 *   <li>{@code /hub reload}              — recharge la configuration.</li>
 *   <li>{@code /hub setspawn}            — definit le spawn du HUB a la position du joueur.</li>
 *   <li>{@code /hub send [joueur]}       — envoie un joueur (ou soi-meme) vers le faction.</li>
 *   <li>{@code /hub queue list}          — affiche la file d'attente.</li>
 *   <li>{@code /hub queue clear}         — vide la file d'attente.</li>
 *   <li>{@code /hub queue add <joueur>}  — ajoute un joueur a la file.</li>
 *   <li>{@code /hub queue remove <joueur>} — retire un joueur de la file.</li>
 * </ul>
 */
public class HubCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS       = Arrays.asList("reload", "setspawn", "send", "queue");
    private static final List<String> QUEUE_SUBS = Arrays.asList("list", "clear", "add", "remove");

    private final RedConflictHub plugin;

    public HubCommand(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(RedConflictHub.ADMIN)) {
            sender.sendMessage(plugin.prefixed(plugin.getConfig().getString("messages.no-permission",
                    "&cTu n'as pas la permission.")));
            return true;
        }

        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "reload":
                plugin.reload();
                sender.sendMessage(plugin.prefixed(plugin.getConfig().getString("messages.reloaded",
                        "&aConfiguration rechargee.")));
                return true;

            case "setspawn":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(plugin.prefixed("&cReserve aux joueurs."));
                    return true;
                }
                plugin.setHubSpawn(((Player) sender).getLocation());
                sender.sendMessage(plugin.prefixed(plugin.getConfig().getString("messages.spawn-set",
                        "&aSpawn du HUB defini a ta position actuelle.")));
                return true;

            case "send":
                return handleSend(sender, args);

            case "queue":
                return handleQueue(sender, args);

            default:
                usage(sender);
                return true;
        }
    }

    // ── /hub send ─────────────────────────────────────────────────────────────

    private boolean handleSend(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.prefixed(plugin.getConfig()
                        .getString("messages.player-not-found", "&cJoueur introuvable : &f%player%")
                        .replace("%player%", args[1])));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            usage(sender);
            return true;
        }
        plugin.sendToFaction(target);
        if (!target.equals(sender)) {
            sender.sendMessage(plugin.prefixed(plugin.getConfig()
                    .getString("messages.sent", "&a%player% a ete envoye vers le faction.")
                    .replace("%player%", target.getName())));
        }
        return true;
    }

    // ── /hub queue ────────────────────────────────────────────────────────────

    private boolean handleQueue(CommandSender sender, String[] args) {
        QueueManager q = plugin.getQueue();

        if (args.length < 2) {
            // Affichage rapide si pas de sous-commande
            return showQueueList(sender, q);
        }

        switch (args[1].toLowerCase()) {

            case "list":
                return showQueueList(sender, q);

            case "clear":
                if (q == null) { sender.sendMessage(plugin.prefixed("&cFile desactivee.")); return true; }
                q.clearQueue();
                sender.sendMessage(plugin.prefixed("&aFile d'attente videe."));
                return true;

            case "add":
                if (args.length < 3) {
                    sender.sendMessage(plugin.prefixed("&7Usage : &f/hub queue add <joueur>"));
                    return true;
                }
                if (q == null) { sender.sendMessage(plugin.prefixed("&cFile desactivee.")); return true; }
                Player addTarget = Bukkit.getPlayerExact(args[2]);
                if (addTarget == null) {
                    sender.sendMessage(plugin.prefixed(plugin.getConfig()
                            .getString("messages.player-not-found", "&cJoueur introuvable : &f%player%")
                            .replace("%player%", args[2])));
                    return true;
                }
                if (q.addPlayer(addTarget)) {
                    sender.sendMessage(plugin.prefixed("&a" + addTarget.getName()
                            + " ajoute a la file (position #" + q.getPosition(addTarget) + ")."));
                } else {
                    sender.sendMessage(plugin.prefixed("&e" + addTarget.getName()
                            + " est deja en file (position #" + q.getPosition(addTarget) + ")."));
                }
                return true;

            case "remove":
                if (args.length < 3) {
                    sender.sendMessage(plugin.prefixed("&7Usage : &f/hub queue remove <joueur>"));
                    return true;
                }
                if (q == null) { sender.sendMessage(plugin.prefixed("&cFile desactivee.")); return true; }
                Player removeTarget = Bukkit.getPlayerExact(args[2]);
                if (removeTarget == null) {
                    sender.sendMessage(plugin.prefixed(plugin.getConfig()
                            .getString("messages.player-not-found", "&cJoueur introuvable : &f%player%")
                            .replace("%player%", args[2])));
                    return true;
                }
                if (q.removePlayer(removeTarget)) {
                    sender.sendMessage(plugin.prefixed("&a" + removeTarget.getName()
                            + " retire de la file d'attente."));
                    removeTarget.sendMessage(plugin.prefixed("&7Tu as ete retire de la file d'attente par un admin."));
                } else {
                    sender.sendMessage(plugin.prefixed("&e" + removeTarget.getName()
                            + " n'etait pas en file."));
                }
                return true;

            default:
                sender.sendMessage(plugin.prefixed("&7Usage : &f/hub queue <list|clear|add|remove>"));
                return true;
        }
    }

    private boolean showQueueList(CommandSender sender, QueueManager q) {
        if (q == null) {
            sender.sendMessage(plugin.prefixed("&cLa file d'attente est desactivee."));
            return true;
        }
        List<UUID> snap = q.getQueueSnapshot();
        if (snap.isEmpty()) {
            sender.sendMessage(plugin.prefixed("&7La file d'attente est vide."));
            return true;
        }
        sender.sendMessage(plugin.prefixed("&7File d'attente &8(&f" + snap.size() + " joueur(s)&8) &8:"));
        int idx = 0;
        for (UUID u : snap) {
            idx++;
            Player p = Bukkit.getPlayer(u);
            String name = p != null ? p.getName() : u.toString().substring(0, 8) + "...";
            sender.sendMessage(plugin.color("  &8#" + idx + " &f" + name));
        }
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void usage(CommandSender sender) {
        sender.sendMessage(plugin.prefixed(plugin.getConfig().getString("messages.usage",
                "&7Usage : &f/hub <reload|setspawn|send|queue> [args]")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(RedConflictHub.ADMIN)) return Collections.emptyList();

        if (args.length == 1) return filter(SUBS, args[0]);

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("send")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, args[1]);
            }
            if (args[0].equalsIgnoreCase("queue")) return filter(QUEUE_SUBS, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("queue")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String o : options) {
            if (o.toLowerCase().startsWith(lower)) out.add(o);
        }
        return out;
    }
}
