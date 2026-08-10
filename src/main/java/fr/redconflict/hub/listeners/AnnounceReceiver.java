package fr.redconflict.hub.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

/**
 * Reçoit les annonces inter-serveurs diffusées depuis OriginsFightCore (via le proxy, canal
 * {@code BungeeCord}, sous-canal {@value #SUBCHANNEL}) et les ré-affiche sur le HUB telles quelles.
 *
 * <p>Le HUB n'émet pas d'annonce ; il se contente de les afficher (le texte arrive déjà formaté).
 */
public class AnnounceReceiver implements PluginMessageListener {

    public static final String SUBCHANNEL = "RC_ANNOUNCE";
    private static final String BUNGEE = "BungeeCord";

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BUNGEE.equals(channel)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String sub = in.readUTF();
            if (!SUBCHANNEL.equals(sub)) return; // autres messages BungeeCord : ignorés
            short len = in.readShort();
            byte[] data = new byte[len];
            in.readFully(data);
            String fullText = new DataInputStream(new ByteArrayInputStream(data)).readUTF();
            for (String line : fullText.split("\n", -1)) {
                Bukkit.broadcastMessage(line);
            }
        } catch (Exception ignored) {
            // payload BungeeCord non destiné aux annonces : on ignore.
        }
    }
}
