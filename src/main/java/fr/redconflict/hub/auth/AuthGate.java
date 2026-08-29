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
    /** Le secret est {@code <nonce b64>.<hmac b64>} : ~90 caractères, on borne large. */
    private static final int MAX_HANDSHAKE_LENGTH = 128;

    private final RedConflictHub plugin;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final boolean kickWhenApiDown;
    /**
     * Exiger une poignee de main du launcher valide, en plus du jeton.
     *
     * <p><b>Doit rester {@code false} tant que le nouveau launcher n'est pas
     * deploye partout.</b> Le launcher est ce qui emet le secret : avant sa
     * sortie, AUCUN joueur legitime n'a de poignee de main, et l'exiger les
     * bloquerait tous. Ordre de deploiement : launcher d'abord, puis client +
     * ce plugin, et enfin on passe ce drapeau a {@code true}.
     */
    private final boolean requireHandshake;
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
        this.requireHandshake = plugin.getConfig().getBoolean("auth.require-handshake", false);
        if (enabled && requireHandshake) {
            plugin.getLogger().info("[Auth] Poignée de main du launcher EXIGÉE. "
                    + "Assure-toi que le launcher qui émet le secret est bien déployé, "
                    + "sinon les joueurs légitimes seront refusés.");
        }
        // AzAuth n'utilise aucune cle partagee : plus d'internal-key.
        String apiUrl = plugin.getConfig().getString("auth.api-url", "https://redconflict.fr");
        this.api = new ApiClient(apiUrl);

        // Le launcher y envoie des mots de passe et le jeton transite en clair
        // sans TLS. En HTTP simple, tout le systeme de comptes est illusoire.
        if (enabled && apiUrl.startsWith("http://")) {
            plugin.getLogger().severe("[Auth] auth.api-url est en HTTP simple (" + apiUrl
                    + "). Les jetons de session circulent alors en clair. Utilise https://.");
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
        final String handshake;
        try {
            Cursor cursor = new Cursor(message);
            if (cursor.readVarInt() != AUTH_HELLO) return;
            token = cursor.readString(MAX_TOKEN_LENGTH);
            // Secret de poignee de main, ajoute apres le jeton par le nouveau
            // client. Un ancien client ne l'ecrit pas : champ absent = chaine
            // vide, jamais une erreur. La verification n'a lieu que si
            // require-handshake est actif.
            handshake = cursor.isReadable() ? cursor.readString(MAX_HANDSHAKE_LENGTH) : "";
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
                        apply(player, result, token, handshake);
                    }
                });
            }
        });
    }

    /** Applique le resultat sur le thread principal. */
    private void apply(Player player, ApiClient.Verification result, String token, String handshake) {
        if (!player.isOnline()) return;

        switch (result.status) {
            case VALID:
                if (!player.getName().equals(result.pseudo)) {
                    plugin.getLogger().warning("[Auth] " + player.getName()
                            + " a presente un jeton appartenant a " + result.pseudo);
                    kick(player, "auth.messages.wrong-account",
                         "&cCe compte ne correspond pas au pseudo utilise.");
                    return;
                }
                // Jeton bon, mais lancé hors launcher : le secret manque ou est
                // faux. C'est le verrou dur — un jeton copié dans un client
                // démarré à la main ne suffit plus à entrer.
                if (requireHandshake && !HandshakeVerifier.valid(token, handshake)) {
                    plugin.getLogger().warning("[Auth] " + player.getName()
                            + " a un jeton valide mais AUCUNE poignée de main de launcher"
                            + " valide (lancement hors launcher ?).");
                    kick(player, "auth.messages.no-handshake",
                         "&cLance le jeu depuis le launcher Red Conflict.\n\n"
                         + "&7Ton compte est bon, mais le client n'a pas prouvé qu'il venait du launcher.");
                    return;
                }
                accept(player);
                break;

            case INVALID:
                kick(player, "auth.messages.invalid-token",
                     "&cSession expiree.\n\n&7Reconnecte-toi dans le launcher.");
                break;

            case BANNED:
                plugin.getLogger().info("[Auth] " + player.getName()
                        + " est banni sur le site, connexion refusee."
                        + (result.detail == null ? "" : " Raison : " + result.detail));
                kick(player, "auth.messages.banned",
                     "&cTon compte est banni.\n\n&7Conteste sur le Discord si tu penses a une erreur.");
                break;

            case MISCONFIGURED:
                plugin.getLogger().severe("[Auth] Azuriom repond que l'API d'authentification "
                        + "est desactivee. Active-la dans Administration > Parametres > "
                        + "Authentification, sinon personne ne peut se connecter.");
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

        /** Reste-t-il des octets à lire ? Sert à traiter le secret comme optionnel. */
        boolean isReadable() {
            return index < data.length;
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
