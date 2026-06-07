package fr.redconflict.hub;

import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Envoie un joueur vers un autre serveur de la grappe via le canal plugin "BungeeCord"
 * (compatible Velocity). C'est le proxy (et VelocityHUB pour la file d'attente) qui traite
 * effectivement la demande de connexion.
 */
public final class ServerConnector {

    private final RedConflictHub plugin;

    public ServerConnector(RedConflictHub plugin) {
        this.plugin = plugin;
    }

    /** Demande au proxy d'envoyer {@code player} vers le serveur nomme {@code server}. */
    public void send(Player player, String server) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            plugin.getLogger().warning("Echec de l'envoi vers " + server + " : " + e.getMessage());
            return;
        }
        player.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
    }
}
