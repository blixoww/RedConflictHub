package fr.redconflict.hub.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Envoie des titles (TITLE + SUBTITLE + TIMES) et des actionbar via NMS par reflexion,
 * compatible Spigot 1.8.8. Toute erreur est silencieuse : ce sont des agréments visuels.
 */
public final class TitleUtil {

    private TitleUtil() {}

    private static final String VERSION =
            Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

    private static boolean titleUnavailable    = false;
    private static boolean actionBarUnavailable = false;

    // ── Cache reflexion commun ────────────────────────────────────────────────
    private static Method getHandle;
    private static Field  playerConnectionField;
    private static Method sendPacketMethod;
    private static Method chatSerializerA; // IChatBaseComponent$ChatSerializer#a(String)

    // ── Cache title ───────────────────────────────────────────────────────────
    private static Constructor<?> titleMsgCons;  // PacketPlayOutTitle(EnumTitleAction, IChatBaseComponent)
    private static Constructor<?> timesCons;      // PacketPlayOutTitle(int fadeIn, int stay, int fadeOut)
    private static Object TITLE_ENUM;
    private static Object SUBTITLE_ENUM;

    // ── Cache actionbar ───────────────────────────────────────────────────────
    private static Constructor<?> actionBarCons; // PacketPlayOutChat(IChatBaseComponent, byte)

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envoie un title + subtitle avec des temps de fondu au joueur.
     *
     * @param fadeIn  durée d'apparition (ticks)
     * @param stay    durée d'affichage  (ticks)
     * @param fadeOut durée de disparition (ticks)
     */
    public static void sendTitle(Player player, String title, String subtitle,
                                  int fadeIn, int stay, int fadeOut) {
        if (titleUnavailable) return;
        try {
            resolveBase();
            resolveTitlePacket();

            Object handle = getHandle.invoke(player);
            Object conn   = playerConnectionField.get(handle);

            // 1) TIMES
            Object timesPacket = timesCons.newInstance(fadeIn, stay, fadeOut);
            sendPacketMethod.invoke(conn, timesPacket);

            // 2) TITLE
            if (title != null && !title.isEmpty()) {
                Object pkt = titleMsgCons.newInstance(TITLE_ENUM, toChat(title));
                sendPacketMethod.invoke(conn, pkt);
            }

            // 3) SUBTITLE
            if (subtitle != null && !subtitle.isEmpty()) {
                Object pkt = titleMsgCons.newInstance(SUBTITLE_ENUM, toChat(subtitle));
                sendPacketMethod.invoke(conn, pkt);
            }
        } catch (Throwable t) {
            titleUnavailable = true;
        }
    }

    /**
     * Envoie un message dans la barre d'action (au-dessus de la barre de vie).
     */
    public static void sendActionBar(Player player, String message) {
        if (actionBarUnavailable) return;
        try {
            resolveBase();
            resolveActionBarPacket();

            Object comp = toChat(message);
            Object pkt  = actionBarCons.newInstance(comp, (byte) 2);

            Object handle = getHandle.invoke(player);
            Object conn   = playerConnectionField.get(handle);
            sendPacketMethod.invoke(conn, pkt);
        } catch (Throwable t) {
            actionBarUnavailable = true;
        }
    }

    // ── Réflexion interne ─────────────────────────────────────────────────────

    private static Object toChat(String text) throws Exception {
        // Échappe le texte pour un JSON minimal {"text":"..."}
        String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return chatSerializerA.invoke(null, "{\"text\":\"" + escaped + "\"}");
    }

    private static void resolveBase() throws Exception {
        if (getHandle != null) return;

        Class<?> craftPlayer  = Class.forName("org.bukkit.craftbukkit." + VERSION + ".entity.CraftPlayer");
        Class<?> entityPlayer = nms("EntityPlayer");
        Class<?> playerConn   = nms("PlayerConnection");
        Class<?> packetClass  = nms("Packet");
        Class<?> iChatBase    = nms("IChatBaseComponent");

        // IChatBaseComponent$ChatSerializer (inner class)
        Class<?> chatSer;
        try {
            chatSer = Class.forName(iChatBase.getName() + "$ChatSerializer");
        } catch (ClassNotFoundException e) {
            chatSer = nms("ChatSerializer");
        }

        getHandle            = craftPlayer.getMethod("getHandle");
        playerConnectionField = entityPlayer.getField("playerConnection");
        sendPacketMethod     = playerConn.getMethod("sendPacket", packetClass);
        chatSerializerA      = chatSer.getMethod("a", String.class);
    }

    private static void resolveTitlePacket() throws Exception {
        if (timesCons != null) return;

        Class<?> packetTitle = nms("PacketPlayOutTitle");
        Class<?> iChatBase   = nms("IChatBaseComponent");

        // Cherche l'enum EnumTitleAction (inner class)
        Class<?> enumClass = null;
        for (Class<?> c : packetTitle.getDeclaredClasses()) {
            if (c.isEnum()) { enumClass = c; break; }
        }
        if (enumClass == null) throw new Exception("EnumTitleAction introuvable");

        for (Object e : enumClass.getEnumConstants()) {
            String n = e.toString();
            if ("TITLE".equals(n))    TITLE_ENUM    = e;
            else if ("SUBTITLE".equals(n)) SUBTITLE_ENUM = e;
        }

        titleMsgCons = packetTitle.getConstructor(enumClass, iChatBase);
        timesCons    = packetTitle.getConstructor(int.class, int.class, int.class);
    }

    private static void resolveActionBarPacket() throws Exception {
        if (actionBarCons != null) return;
        Class<?> packetChat = nms("PacketPlayOutChat");
        Class<?> iChatBase  = nms("IChatBaseComponent");
        actionBarCons = packetChat.getConstructor(iChatBase, byte.class);
    }

    private static Class<?> nms(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server." + VERSION + "." + name);
    }
}

