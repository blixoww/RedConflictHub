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
 * Appel a l'API d'authentification pour valider un jeton de session.
 *
 * <p>Toutes les methodes sont bloquantes : elles doivent etre appelees depuis
 * un thread asynchrone, jamais depuis le thread principal du serveur — une
 * requete reseau sur le thread principal ferait laguer tout le monde.
 *
 * <p>Gson est fourni par Spigot 1.8.8 : aucune dependance a ajouter.
 */
public final class ApiClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private final String baseUrl;
    private final String internalKey;

    public ApiClient(String baseUrl, String internalKey) {
        // Retire un eventuel slash final : les chemins commencent par "/".
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalKey = internalKey;
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
            conn = (HttpURLConnection) new URL(baseUrl + "/api/session/verify").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Internal-Key", internalKey);
            conn.setDoOutput(true);

            JsonObject body = new JsonObject();
            body.addProperty("token", token);

            OutputStream out = conn.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            String payload = read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (status == 401) {
                // Cle interne refusee : c'est une erreur de configuration du
                // serveur, pas la faute du joueur.
                return Verification.misconfigured();
            }
            if (status != 200) return Verification.unreachable();

            JsonObject json = new JsonParser().parse(payload).getAsJsonObject();
            if (!json.has("valid") || !json.get("valid").getAsBoolean()) {
                return Verification.invalid();
            }
            return Verification.valid(
                    json.get("pseudo").getAsString(),
                    json.get("uuid").getAsString());

        } catch (Exception e) {
            return Verification.unreachable();
        } finally {
            if (conn != null) conn.disconnect();
        }
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

        public enum Status { VALID, INVALID, UNREACHABLE, MISCONFIGURED }

        public final Status status;
        public final String pseudo;
        public final String uuid;

        private Verification(Status status, String pseudo, String uuid) {
            this.status = status;
            this.pseudo = pseudo;
            this.uuid = uuid;
        }

        static Verification valid(String pseudo, String uuid) {
            return new Verification(Status.VALID, pseudo, uuid);
        }
        static Verification invalid()      { return new Verification(Status.INVALID, null, null); }
        static Verification unreachable()  { return new Verification(Status.UNREACHABLE, null, null); }
        static Verification misconfigured(){ return new Verification(Status.MISCONFIGURED, null, null); }
    }
}
