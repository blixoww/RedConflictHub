package fr.redconflict.hub.listeners;

import fr.redconflict.hub.RedConflictHub;
import fr.redconflict.hub.queue.QueueManager;
import fr.redconflict.hub.util.TitleUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

/**
 * Met le joueur dans l'etat "lobby" a la connexion (adventure, vie/faim pleines, inventaire propre,
 * item de jonction, teleportation au spawn) et gere : le clic sur l'item, l'anti-vide, le double-saut,
 * la tablist, les messages de connexion/deconnexion et le blocage du chat.
 */
public class HubPlayerListener implements Listener {

    private final RedConflictHub plugin;

    public HubPlayerListener(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    private static boolean locked(Player p) {
        return !p.hasPermission(RedConflictHub.BYPASS);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();

        // ── Message de connexion ──────────────────────────────────────────────
        String joinMsg = plugin.getConfig().getString("join-quit.join-message", null);
        if (joinMsg != null) {
            e.setJoinMessage(joinMsg.isEmpty() ? null
                    : plugin.color(plugin.applyPlaceholders(p, joinMsg)));
        }

        // ── Etat lobby ────────────────────────────────────────────────────────
        if (locked(p)) {
            p.setGameMode(GameMode.ADVENTURE);
        }
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        for (PotionEffect eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());

        float walk = (float) plugin.getConfig().getDouble("comfort.walk-speed", 0.2D);
        p.setWalkSpeed(Math.max(0f, Math.min(1f, walk)));

        boolean doubleJump = plugin.getConfig().getBoolean("comfort.double-jump.enabled", false);
        if (doubleJump && locked(p)) {
            p.setAllowFlight(true);
            p.setFlying(false);
        }

        if (plugin.getConfig().getBoolean("clear-inventory-on-join", true)) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
        }

        ItemStack joinItem = plugin.buildJoinItem();
        if (joinItem != null) {
            p.getInventory().setItem(plugin.joinItemSlot(), joinItem);
            p.getInventory().setHeldItemSlot(plugin.joinItemSlot());
        }

        Location spawn = plugin.effectiveSpawn();
        if (spawn != null) p.teleport(spawn);
        p.updateInventory();

        plugin.applyTablist(p);

        // ── Title de bienvenue ────────────────────────────────────────────────
        if (plugin.getConfig().getBoolean("join-title.enabled", true)) {
            final String title = plugin.color(plugin.applyPlaceholders(p,
                    plugin.getConfig().getString("join-title.title", "&c&lRED &f&lCONFLICT")));
            final String subtitle = plugin.color(plugin.applyPlaceholders(p,
                    plugin.getConfig().getString("join-title.subtitle", "&7Bienvenue &f%prefix%%player% &c!")));
            int fadeIn  = plugin.getConfig().getInt("join-title.fade-in",  10);
            int stay    = plugin.getConfig().getInt("join-title.stay",     60);
            int fadeOut = plugin.getConfig().getInt("join-title.fade-out", 20);
            // Leger delai pour que le joueur soit bien charge en jeu
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> TitleUtil.sendTitle(p, title, subtitle, fadeIn, stay, fadeOut), 5L);
        }

        // ── Auto-join / file d'attente ────────────────────────────────────────
        if (plugin.getConfig().getBoolean("auto-join", false)) {
            long ticks = Math.max(0L, plugin.getConfig().getLong("auto-join-delay-seconds", 1L)) * 20L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                QueueManager q = plugin.getQueue();
                if (plugin.getConfig().getBoolean("queue.enabled", true) && q != null) {
                    q.addPlayer(p);
                } else {
                    plugin.sendToFaction(p);
                }
            }, ticks);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        // Retirer de la file d'attente
        QueueManager q = plugin.getQueue();
        if (q != null) q.removePlayer(p);

        String quitMsg = plugin.getConfig().getString("join-quit.quit-message", null);
        if (quitMsg != null) {
            e.setQuitMessage(quitMsg.isEmpty() ? null
                    : plugin.color(plugin.applyPlaceholders(p, quitMsg)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        double minY = plugin.getConfig().getDouble("void-teleport-y", 0D);
        if (e.getTo().getY() < minY) {
            Location spawn = plugin.effectiveSpawn();
            if (spawn != null) {
                e.getPlayer().teleport(spawn);
                e.getPlayer().setFallDistance(0f);
            }
        }
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("comfort.double-jump.enabled", false)) return;
        if (!locked(p) || !e.isFlying()) return;

        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(true);

        double power = plugin.getConfig().getDouble("comfort.double-jump.power", 1.2D);
        Vector launch = p.getLocation().getDirection().multiply(power).setY(0.9D);
        p.setVelocity(launch);
        try {
            p.playSound(p.getLocation(), Sound.valueOf("ENDERDRAGON_WINGS"), 0.6f, 1.4f);
        } catch (IllegalArgumentException ignored) {}
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!plugin.getConfig().getBoolean("comfort.block-chat", false)) return;
        if (locked(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.prefixed(plugin.getConfig()
                    .getString("messages.no-chat", "Le chat est desactive dans le HUB.")));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        boolean rightClick = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        Player p = e.getPlayer();

        // Clic droit avec l'item de jonction → rejoindre la file (ou envoyer directement)
        if (rightClick && plugin.getConfig().getBoolean("join-item.enabled", true)) {
            ItemStack inHand = p.getItemInHand();
            ItemStack join   = plugin.buildJoinItem();
            if (inHand != null && join != null && inHand.getType() == join.getType()) {
                e.setCancelled(true);
                QueueManager q = plugin.getQueue();
                if (plugin.getConfig().getBoolean("queue.enabled", true) && q != null) {
                    if (q.isInQueue(p)) {
                        // Informer de la position actuelle
                        p.sendMessage(plugin.prefixed(plugin.getConfig()
                                .getString("queue.already-message",
                                        "&7Tu es deja en file — position &c#%pos% &7sur &f%total%")
                                .replace("%pos%",   String.valueOf(q.getPosition(p)))
                                .replace("%total%", String.valueOf(q.getSize()))));
                    } else {
                        q.addPlayer(p);
                    }
                } else {
                    plugin.sendToFaction(p);
                }
                return;
            }
        }

        // Bloquer les interactions blocs pour les non-staff
        if (locked(p) && (a == Action.RIGHT_CLICK_BLOCK || a == Action.PHYSICAL)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
}
