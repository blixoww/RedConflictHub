package fr.redconflict.hub.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Envoie l'entete / le pied de la tablist via le paquet NMS
 * {@code PacketPlayOutPlayerListHeaderFooter}, par reflexion pour rester compatible
 * avec la version CraftBukkit du serveur (cible 1.8.8).
 *
 * <p>Toute erreur de reflexion est silencieusement ignoree : la tablist est un
 * agrement, son absence ne doit jamais casser le HUB.
 */
public final class TabListUtil {

    private TabListUtil() {
    }

    /** Version du package NMS/OBC, ex. "v1_8_R3". */
    private static final String VERSION =
            Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

    private static boolean unavailable = false;

    // Membres reflexion mis en cache apres la premiere resolution.
    private static Method getHandle;
    private static Field playerConnection;
    private static Method sendPacket;
    private static Constructor<?> chatTextCons;
    private static Constructor<?> packetCons;
    private static Field footerField;

    /**
     * Definit l'entete et le pied de la tablist pour {@code player}.
     * Les codes couleur '&amp;' doivent deja avoir ete traduits par l'appelant.
     */
    public static void send(Player player, String header, String footer) {
        if (unavailable) return;
        try {
            resolve();
            Object headerComp = chatTextCons.newInstance(header == null ? "" : header);
            Object footerComp = chatTextCons.newInstance(footer == null ? "" : footer);

            Object packet = packetCons.newInstance(headerComp);
            footerField.set(packet, footerComp);

            Object handle = getHandle.invoke(player);
            Object connection = playerConnection.get(handle);
            sendPacket.invoke(connection, packet);
        } catch (Throwable t) {
            // Une seule tentative : si la reflexion echoue, on desactive definitivement.
            unavailable = true;
        }
    }

    private static void resolve() throws Exception {
        if (packetCons != null) return;

        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit." + VERSION + ".entity.CraftPlayer");
        Class<?> entityPlayer = nms("EntityPlayer");
        Class<?> playerConnectionClass = nms("PlayerConnection");
        Class<?> packetClass = nms("Packet");
        Class<?> baseComponent = nms("IChatBaseComponent");
        Class<?> chatText = nms("ChatComponentText");
        Class<?> headerFooterPacket = nms("PacketPlayOutPlayerListHeaderFooter");

        getHandle = craftPlayer.getMethod("getHandle");
        playerConnection = entityPlayer.getField("playerConnection");
        sendPacket = playerConnectionClass.getMethod("sendPacket", packetClass);
        chatTextCons = chatText.getConstructor(String.class);
        packetCons = headerFooterPacket.getConstructor(baseComponent);
        footerField = headerFooterPacket.getDeclaredField("b");
        footerField.setAccessible(true);
    }

    private static Class<?> nms(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + VERSION + "." + name);
    }
}
