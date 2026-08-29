package fr.redconflict.hub.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.util.Base64;

/**
 * Vérification, CÔTÉ SERVEUR, de la poignée de main du launcher.
 *
 * <p><b>Pourquoi ça change tout.</b> Jusqu'ici, la preuve que le client venait
 * du launcher n'était contrôlée que sur la machine du joueur ({@code LaunchGate}
 * côté client). Un client instrumenté pouvait donc se déclarer « lancé par le
 * launcher » sans l'être. Ici, c'est le SERVEUR qui recalcule le HMAC : le
 * client joint son secret {@code <nonce>.<hmac>} au jeton, et le serveur, seul
 * détenteur de la clé côté serveur, vérifie que {@code hmac == HMAC(clé, nonce
 * || jeton)}. Un client démarré hors launcher n'a pas de secret valide et ne
 * peut pas l'inventer sans la clé.
 *
 * <p><b>Le plafond, à dire clairement.</b> La même clé vit dans le binaire du
 * launcher, distribué aux joueurs : quelqu'un qui l'extrait peut forger un
 * secret. Ce n'est donc pas une preuve absolue — c'est un coût. Ce coût tient à
 * une chose : <b>la clé change à chaque version publiée</b>. Il faut donc
 * ré-extraire à chaque mise à jour, et sans jamais savoir si ça a marché quand
 * le serveur reste muet. Face à un contournement qui se partage, c'est ce qui
 * compte.
 *
 * <p><b>Les clés doivent être IDENTIQUES</b> à celles du launcher
 * ({@code fr.launcher.config.Handshake}) et du client
 * ({@code net.minecraft.client.redconflict.LaunchGate}). Les trois se déploient
 * ensemble, et la première clé est celle de la version courante.
 */
final class HandshakeVerifier {

    /**
     * Clés acceptées. <b>À garder synchronisées avec le launcher et le client,
     * et à faire tourner à chaque version.</b> Les entrées suivantes couvrent la
     * fenêtre où un joueur a déjà le nouveau client mais encore l'ancien
     * launcher.
     */
    private static final String[] KEYS = {
            "6xPFjQx7RVZwzsihTwzhRojA7w8xFlyOQBxUhZOCiIo=",
    };

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HandshakeVerifier() { }

    /**
     * Vrai si {@code secret} est une poignée de main authentique pour ce jeton.
     *
     * @param token  jeton de session présenté par le client
     * @param secret ligne {@code <nonce base64>.<hmac base64>} transmise, ou
     *               null/vide si le client n'en a pas fourni
     */
    static boolean valid(String token, String secret) {
        if (secret == null) {
            return false;
        }
        int dot = secret.indexOf('.');
        if (dot <= 0 || dot == secret.length() - 1) {
            return false;
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(secret.substring(0, dot));
            byte[] mac = Base64.getDecoder().decode(secret.substring(dot + 1));
            if (nonce.length == 0 || mac.length == 0) {
                return false;
            }
            for (String encoded : KEYS) {
                byte[] key = Base64.getDecoder().decode(encoded);
                byte[] expected = hmac(key, nonce, token);
                if (expected != null && constantTimeEquals(expected, mac)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Secret illisible : traité comme absent.
        }
        return false;
    }

    /** HMAC-SHA256 de {@code nonce || jeton}. */
    private static byte[] hmac(byte[] key, byte[] nonce, String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(nonce);
            mac.update((token == null ? "" : token).getBytes(UTF8));
            return mac.doFinal();
        } catch (Exception e) {
            return null;
        }
    }

    /** Comparaison à temps constant : ne rien apprendre par la durée. */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
