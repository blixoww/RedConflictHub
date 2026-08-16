package fr.redconflict.hub.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Validation d'un jeton de session aupres de l'API AzAuth d'Azuriom.
 *
 * <p>Remplace l'ancien appel a RedConflictAPI ({@code /api/session/verify}).
 * Azuriom est desormais la source de verite des comptes ; le plugin interroge
 * {@code POST /api/auth/verify} avec le jeton presente par le client.
 *
 * <p>Toutes les methodes sont bloquantes : elles doivent etre appelees depuis
 * un thread asynchrone, jamais depuis le thread principal du serveur — une
 * requete reseau sur le thread principal ferait laguer tout le monde.
 *
 * <p>Gson est fourni par Spigot 1.8.8 : aucune dependance a ajouter.
 *
 * <p><b>Prerequis cote site :</b> l'API doit etre activee dans
 * Administration &rarr; Parametres &rarr; Authentification. Tant qu'elle ne
 * l'est pas, Azuriom repond {@code 400 {"status":"error","message":"Auth API
 * is not enabled"}} — traduit ici en {@link Verification.Status#MISCONFIGURED}
 * plutot qu'en jeton invalide, pour que le journal designe la vraie cause.
 */
public final class ApiClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private final String baseUrl;

    /**
     * @param baseUrl racine du site Azuriom, par exemple
     *                {@code https://redconflict.fr}. Contrairement a l'ancienne
     *                API, AzAuth n'utilise aucune cle partagee : la route est
     *                publique, seul un jeton valide en tire quelque chose.
     */
    public ApiClient(String baseUrl) {
        // Retire un eventuel slash final : les chemins commencent par "/".
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Valide un jeton de session.
     *
     * @return le resultat, jamais null. En cas de panne reseau, le resultat
     *         est {@link Verification#unreachable()} : l'appelant doit alors
     *         decider s'il expulse ou s'il laisse passer, ce qui n'est pas la
     *         meme decision qu'un jeton invalide.
     */
    public Verification verify(String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/api/auth/verify").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            JsonObject body = new JsonObject();
            body.addProperty("access_token", token);

            OutputStream out = conn.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            String payload = read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (status == 200) {
                JsonObject json = new JsonParser().parse(payload).getAsJsonObject();

                return Verification.valid(
                        text(json, "username"),
                        dashed(text(json, "uuid")));
            }

            // Azuriom decrit ses refus par un champ "reason" stable. On s'y fie
            // plutot qu'au seul code HTTP : le libelle "message" suit la langue
            // du site, "reason" non.
            String reason = null, banReason = null;
            try {
                JsonObject err = new JsonParser().parse(payload).getAsJsonObject();
                reason = text(err, "reason");
                banReason = text(err, "ban_reason");
            } catch (Exception ignored) {
                // Reponse non-JSON : on retombera sur le code HTTP plus bas.
            }

            // 403 user_banned : le jeton est parfaitement valide, c'est le
            // compte qui est banni sur le site. Ce n'est pas une erreur
            // d'authentification, et sans message distinct le joueur relance
            // son launcher en boucle sans jamais comprendre.
            if ("user_banned".equals(reason)) return Verification.banned(banReason);

            if ("invalid_token".equals(reason)) return Verification.invalid();

            // 400 "Auth API is not enabled" : l'API existe mais le site ne
            // l'expose pas. Erreur de configuration, pas jeton perime — le
            // distinguer evite de chercher du cote du launcher.
            if (status == 400) return Verification.misconfigured();

            // 5xx : le site est en vrac, pas le jeton. On traite comme une
            // panne pour que kick-when-api-down garde son sens.
            if (status >= 500) return Verification.unreachable();

            return Verification.invalid();

        } catch (Exception e) {
            return Verification.unreachable();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull()
                ? json.get(field).getAsString() : null;
    }

    /**
     * Azuriom stocke et renvoie les UUID <b>sans tirets</b>
     * ({@code 8069ba805bce11e49359bc305c61a49a}), la ou Bukkit et le launcher
     * manipulent la forme canonique {@code 8069ba80-5bce-11e4-9359-bc305c61a49a}.
     *
     * <p>C'est la meme valeur — Azuriom en mode offline calcule exactement
     * {@code uuid3(NIL, "OfflinePlayer:" + pseudo)}, soit le meme UUID que le
     * serveur en offline mode. Seule l'ecriture differe, et une comparaison de
     * chaines echouerait sans cette normalisation.
     */
    static String dashed(String uuid) {
        if (uuid == null) return null;
        String raw = uuid.replace("-", "");
        if (raw.length() != 32) return uuid;   // format inattendu : on n'invente rien
        return raw.substring(0, 8) + '-' + raw.substring(8, 12) + '-'
             + raw.substring(12, 16) + '-' + raw.substring(16, 20) + '-'
             + raw.substring(20);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF8));
        try {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            br.close();
        }
        return sb.toString();
    }

    /** Resultat d'une verification de jeton. */
    public static final class Verification {

        public enum Status { VALID, INVALID, BANNED, UNREACHABLE, MISCONFIGURED }

        public final Status status;
        public final String pseudo;
        public final String uuid;
        /** Raison du bannissement quand {@code status} vaut BANNED, sinon nul. */
        public final String detail;

        private Verification(Status status, String pseudo, String uuid, String detail) {
            this.status = status;
            this.pseudo = pseudo;
            this.uuid = uuid;
            this.detail = detail;
        }

        static Verification valid(String pseudo, String uuid) {
            return new Verification(Status.VALID, pseudo, uuid, null);
        }
        /** Compte banni sur le site : le jeton, lui, est valide. */
        static Verification banned(String banReason) {
            return new Verification(Status.BANNED, null, null, banReason);
        }
        static Verification invalid()      { return new Verification(Status.INVALID, null, null, null); }
        static Verification unreachable()  { return new Verification(Status.UNREACHABLE, null, null, null); }
        static Verification misconfigured(){ return new Verification(Status.MISCONFIGURED, null, null, null); }
    }
}
