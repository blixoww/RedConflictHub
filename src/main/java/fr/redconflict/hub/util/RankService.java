package fr.redconflict.hub.util;

import fr.redconflict.hub.RedConflictHub;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Source unique des grades (prefixes / groupes) des joueurs sur le HUB, via Vault
 * (alimente par LuckPerms). Tout est tolerant a l'absence de Vault : le HUB ne doit
 * jamais casser si aucun plugin de permissions n'est present (prefixe par defaut).
 */
public class RankService {

    private final RedConflictHub plugin;

    private Chat chat;                 // fournisseur Vault, null si indisponible
    private boolean available;
    private Boolean lastLogged;        // dernier etat journalise (evite le spam au re-hook)
    private List<String> order = new ArrayList<>();

    public RankService(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    /** (Re)lie le service a Vault et recharge l'ordre des grades depuis la config. */
    public void hook() {
        order = new ArrayList<>();
        for (String g : plugin.getConfig().getStringList("ranks.order")) {
            order.add(g.toLowerCase(Locale.ROOT));
        }

        chat = null;
        available = false;
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                RegisteredServiceProvider<Chat> rsp =
                        Bukkit.getServicesManager().getRegistration(Chat.class);
                if (rsp != null) {
                    chat = rsp.getProvider();
                    available = chat != null;
                }
            } catch (Throwable t) {
                available = false;
            }
        }

        // Ne journalise qu'au changement d'etat (hook() est rappele periodiquement).
        if (lastLogged == null || lastLogged != available) {
            if (available) {
                plugin.getLogger().info("[Ranks] Vault/Chat detecte — grades actives.");
            } else {
                plugin.getLogger().warning("[Ranks] Vault introuvable — grades non affiches "
                        + "(prefixe par defaut utilise).");
            }
            lastLogged = available;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Prefixe colore du joueur (ex. {@code §c[VIP] }), ou le prefixe par defaut de la config. */
    public String prefix(Player p) {
        if (available) {
            try {
                String pre = chat.getPlayerPrefix(p);
                if (pre != null && !pre.isEmpty()) {
                    return ChatColor.translateAlternateColorCodes('&', pre);
                }
            } catch (Throwable ignored) {
            }
        }
        return ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("ranks.default-prefix", ""));
    }

    /** Groupe principal du joueur (en minuscule), ou {@code "default"}. */
    public String group(Player p) {
        if (available) {
            try {
                String g = chat.getPrimaryGroup(p);
                if (g != null && !g.isEmpty()) return g.toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
            }
        }
        return "default";
    }

    /**
     * Index de tri du joueur d'apres {@code ranks.order} (0 = plus haut grade).
     * Les groupes absents de la liste sont places en dernier.
     */
    public int sortIndex(Player p) {
        int idx = order.indexOf(group(p));
        return idx >= 0 ? idx : order.size();
    }
}
