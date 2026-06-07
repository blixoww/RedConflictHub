package fr.redconflict.hub.listeners;

import fr.redconflict.hub.RedConflictHub;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Bloque TOUTES les commandes pour les joueurs sans la permission {@link RedConflictHub#BYPASS}
 * ni {@link RedConflictHub#ADMIN}. Les staff (bypass OU admin) peuvent utiliser les commandes
 * librement dans le HUB.
 */
public class CommandBlockerListener implements Listener {

    private final RedConflictHub plugin;

    public CommandBlockerListener(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        // Les staff (bypass ou admin) peuvent utiliser les commandes
        if (p.hasPermission(RedConflictHub.BYPASS) || p.hasPermission(RedConflictHub.ADMIN)) return;
        e.setCancelled(true);
        p.sendMessage(plugin.prefixed(plugin.getConfig().getString("messages.no-commands",
                "Les commandes sont desactivees dans le HUB.")));
    }
}
