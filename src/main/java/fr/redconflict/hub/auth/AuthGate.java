package fr.redconflict.hub.auth;

import fr.redconflict.hub.RedConflictHub;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verrou d'authentification du HUB.
 *
 * <p>Le reseau tourne en offline mode : le serveur accepte le pseudo que lui
 * annonce n'importe quel client. Sans ce controle, un Minecraft 1.8.9 vanilla
 * suffirait a entrer sous le pseudo de son choix, y compris celui d'un
 * administrateur — le launcher et l'API ne seraient qu'un confort.
 *
 * <p>Deroulement : a la connexion, le joueur est en attente. Le client modifie
 * presente son jeton sur {@code CUSTOM:AUTH_C2S}. Le plugin le valide aupres de
 * l'API, <b>compare le pseudo renvoye a celui de la connexion</b>, puis libere
 * le joueur. Sans jeton valide dans le delai imparti, il est expulse.
 *
 * <p>Le HUB est le bon endroit pour ce controle : tout le monde y arrive
 * ({@code try = ["hub"]} dans velocity.toml) et le lobby y verrouille deja
 * toutes les interactions. Encore faut-il que les ports des autres serveurs
 * soient injoignables de l'exterieur, sinon on contourne le HUB — et donc ce
 * controle — en se connectant directement au faction.
 */
public final class AuthGate implements Listener, PluginMessageListener {

    /** Doit correspondre a {@code PacketChannel.AUTH_C2S} cote client. */
    public static final String CHANNEL = "CUSTOM:AUTH_C2S";
    /** Doit correspondre a {@code PacketId.AUTH_HELLO} cote client. */
    private static final int AUTH_HELLO = 0xF0;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_TOKEN_LENGTH = 128;

    private final RedConflictHub plugin;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final boolean kickWhenApiDown;
    private final ApiClient api;

    /** Joueurs dont le jeton a ete valide. */
    private final Map<UUID, Boolean> verified = new ConcurrentHashMap<UUID, Boolean>();
    /** Taches d'expulsion en attente, annulees des qu'un jeton est accepte. */
    private final Map<UUID, BukkitRunnable> pending = new ConcurrentHashMap<UUID, BukkitRunnable>();

    public AuthGate(RedConflictHub plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("auth.enabled", true);
        this.timeoutSeconds = Math.max(3, plugin.getConfig().getInt("auth.timeout-seconds", 10));
        this.kickWhenApiDown = plugin.getConfig().getBoolean("auth.kick-when-api-down", true);
        this.api = new ApiClient(
                plugin.getConfig().getString("auth.api-url", "http://127.0.0.1:8080"),
                plugin.getConfig().getString("auth.internal-key", ""));

        if (enabled && plugin.getConfig().getString("auth.internal-key", "").isEmpty()) {
            plugin.getLogger().severe("[Auth] auth.internal-key est vide : l'API refusera toutes "
                    + "les verifications et personne ne pourra se connecter.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Vrai si le joueur a presente un jeton valide. */
    public boolean isVerified(Player player) {
        return !enabled || verified.containsKey(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════
    //  CYCLE DE VIE DU JOUEUR
    // ══════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;

        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        verified.remove(id);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                pending.remove(id);
                if (verified.containsKey(id)) return;
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    kick(p, "auth.messages.no-token",
                         "&cConnexion refusee.\n\n&7Lance le jeu depuis le launcher Red Conflict.");
                }
            }
        };
        pending.put(id, task);
        task.runTaskLater(plugin, timeoutSeconds * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        verified.remove(id);
        BukkitRunnable task = pending.remove(id);
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Deja executee : sans consequence.
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  RECEPTION DU JETON
    // ══════════════════════════════════════════════════════════
    @Override
    public void onPluginMessageReceived(String channel, final Player player, byte[] message) {
        if (!enabled || !CHANNEL.equals(channel)) return;
        if (verified.containsKey(player.getUniqueId())) return;

        final String token;
        try {
            Cursor cursor = new Cursor(message);
            if (cursor.readVarInt() != AUTH_HELLO) return;
            token = cursor.readString(MAX_TOKEN_LENGTH);
        } catch (Exception e) {
            // Payload illisible : on laisse la tache d'expulsion faire son
            // travail plutot que d'expulser tout de suite, au cas ou un autre
            // packet legitime suivrait.
            plugin.getLogger().warning("[Auth] Payload illisible de " + player.getName());
            return;
        }

        if (token == null || token.isEmpty()) return;

        // La verification part en asynchrone : une requete HTTP sur le thread
        // principal ferait laguer tout le serveur.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final ApiClient.Verification result = api.verify(token);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        apply(player, result);
                    }
                });
            }
        });
    }

    /** Applique le resultat sur le thread principal. */
    private void apply(Player player, ApiClient.Verification result) {
        if (!player.isOnline()) return;

        switch (result.status) {
            case VALID:
                // Comparaison indispensable : un joueur disposant d'un compte
                // valide pourrait sinon se connecter sous le pseudo d'un autre
                // en presentant son propre jeton.
                if (!player.getName().equalsIgnoreCase(result.pseudo)) {
                    plugin.getLogger().warning("[Auth] " + player.getName()
                            + " a presente un jeton appartenant a " + result.pseudo);
                    kick(player, "auth.messages.wrong-account",
                         "&cCe compte ne correspond pas au pseudo utilise.");
                    return;
                }
                accept(player);
                break;

            case INVALID:
                kick(player, "auth.messages.invalid-token",
                     "&cSession expiree.\n\n&7Reconnecte-toi dans le launcher.");
                break;

            case MISCONFIGURED:
                plugin.getLogger().severe("[Auth] L'API refuse la cle interne "
                        + "(auth.internal-key). Verifie qu'elle correspond a RC_INTERNAL_KEY.");
                if (kickWhenApiDown) {
                    kick(player, "auth.messages.api-down",
                         "&cService d'authentification indisponible.\n\n&7Reessaie dans quelques minutes.");
                } else {
                    accept(player);
                }
                break;

            case UNREACHABLE:
            default:
                plugin.getLogger().warning("[Auth] API injoignable lors de la verification de "
                        + player.getName());
                // Choix volontairement configurable : refuser tout le monde
                // pendant une panne d'API, ou laisser entrer au risque
                // d'accepter des usurpations. Par defaut on refuse.
                if (kickWhenApiDown) {
                    kick(player, "auth.messages.api-down",
                         "&cService d'authentification indisponible.\n\n&7Reessaie dans quelques minutes.");
                } else {
                    accept(player);
                }
                break;
        }
    }

    private void accept(Player player) {
        UUID id = player.getUniqueId();
        verified.put(id, Boolean.TRUE);
        BukkitRunnable task = pending.remove(id);
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
            }
        }
        plugin.getLogger().info("[Auth] " + player.getName() + " authentifie.");
    }

    private void kick(Player player, String configKey, String fallback) {
        String message = plugin.getConfig().getString(configKey, fallback);
        player.kickPlayer(plugin.color(message));
    }

    // ══════════════════════════════════════════════════════════
    //  DECODAGE DU PAYLOAD
    // ══════════════════════════════════════════════════════════
    /**
     * Lecteur minimal au format PacketBuffer de Minecraft : VarInt, puis
     * chaine prefixee de sa longueur en octets.
     */
    private static final class Cursor {

        private final byte[] data;
        private int index;

        Cursor(byte[] data) {
            this.data = data;
        }

        int readVarInt() {
            int value = 0, size = 0, b;
            do {
                b = readByte();
                value |= (b & 0x7F) << (size++ * 7);
                if (size > 5) throw new IllegalArgumentException("VarInt trop long");
            } while ((b & 0x80) == 0x80);
            return value;
        }

        String readString(int maxLength) {
            int length = readVarInt();
            if (length < 0 || length > maxLength * 4)
                throw new IllegalArgumentException("Chaine trop longue : " + length);
            if (index + length > data.length)
                throw new IllegalArgumentException("Payload tronque");
            String value = new String(data, index, length, UTF8);
            index += length;
            if (value.length() > maxLength)
                throw new IllegalArgumentException("Chaine trop longue : " + value.length());
            return value;
        }

        private int readByte() {
            if (index >= data.length) throw new IllegalArgumentException("Payload tronque");
            return data[index++] & 0xFF;
        }
    }
}
