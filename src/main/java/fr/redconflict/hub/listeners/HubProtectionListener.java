package fr.redconflict.hub.listeners;

import fr.redconflict.hub.RedConflictHub;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/**
 * Verrouille toute interaction avec le monde du HUB pour les joueurs sans la permission
 * {@link RedConflictHub#BYPASS} (staff). Casse/pose de blocs, drop/ramassage d'items, manipulation
 * d'inventaire, degats, faim, meteo et spawn de mobs sont neutralises.
 */
public class HubProtectionListener implements Listener {

    private static boolean locked(Player p) {
        return !p.hasPermission(RedConflictHub.BYPASS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player && locked((Player) e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // Aucun degat dans le HUB (chute, PvP, noyade, feu...) — pour tout le monde.
        if (e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        e.setCancelled(true);
        if (e.getEntity() instanceof Player) ((Player) e.getEntity()).setFoodLevel(20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        if (e.toWeatherState()) e.setCancelled(true); // jamais de pluie/orage
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        // Pas de mobs naturels dans le lobby (on laisse passer les spawns custom/plugins).
        switch (e.getSpawnReason()) {
            case CUSTOM:
            case SPAWNER_EGG:
                return;
            default:
                e.setCancelled(true);
        }
    }
}
