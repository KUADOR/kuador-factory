package ec.kuador.board.connections.application;

import ec.kuador.board.connections.domain.OAuthClient;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.json.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La sesión OAuth de una conexión: cómo se abre (PKCE, con un estado de un solo uso atado a quien la pidió), cómo se
 * cierra el inicio y cómo se mantiene viva renovando el token. Todo viaja en un solo texto cifrado que guarda la conexión.
 */
@Component
class OAuthSessions {

    /** Lo que se le da al navegador para iniciar sesión y lo que se guarda mientras tanto. */
    record Started(String session, String authorizationUrl) {}

    /** El token para llamar al servidor. {@code session}: la sesión a guardar si cambió (se renovó); nula si sigue igual. */
    record Bearer(String token, String session) {}

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(10);
    /** Se renueva un poco antes de vencer, para que no venza a mitad de una llamada. */
    private static final long EARLY_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthClient oauth;
    private final SecretBox secrets;
    private final Clock clock;
    private final String redirectUri;

    OAuthSessions(OAuthClient oauth, SecretBox secrets, Clock clock, @Value("${board.web-url}") String webUrl) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.clock = clock;
        this.redirectUri = webUrl.replaceAll("/+$", "") + "/connections/oauth/callback";
    }

    /**
     * Prepara el inicio de sesión. {@code clientId} vacío: se reutiliza el cliente de {@code previous} (misma conexión) o
     * el tablero se registra solo en el servidor. {@code scope} vacío: los permisos que sugiere el servidor.
     */
    Started start(String mcpUrl, String clientId, String clientSecret, String scope, String previous, UUID memberId) {
        return start(mcpUrl, clientId, clientSecret, scope, null, previous, memberId);
    }

    /** {@code extra}: parámetros propios del sistema para el inicio de sesión (nulo = los de {@code previous}, si los había). */
    Started start(String mcpUrl, String clientId, String clientSecret, String scope, Map<String, String> extra, String previous, UUID memberId) {
        OAuthClient.Discovery found = call(() -> oauth.discover(mcpUrl));
        Map<String, String> old = previous == null ? Map.of() : read(previous);
        OAuthClient.Client client;
        if (clientId != null && !clientId.isBlank()) {
            client = new OAuthClient.Client(clientId.strip(), clientSecret == null || clientSecret.isBlank() ? null : clientSecret.strip());
        } else if (old.get("client") != null && found.tokenEndpoint().equals(old.get("token"))) {
            client = new OAuthClient.Client(old.get("client"), old.get("clientSecret"));
        } else if (found.registrationEndpoint() != null) {
            client = call(() -> oauth.register(found.registrationEndpoint(), redirectUri, "Kuador Go"));
        } else {
            throw new DomainException("Este sistema no deja que el tablero se registre solo: registra un cliente allí (con la dirección de retorno "
                    + redirectUri + ") y pega su identificador.");
        }
        String wanted = scope != null && !scope.isBlank() ? scope.strip().replaceAll("\\s+", " ")
                : old.get("scope") != null && (clientId == null || clientId.isBlank()) ? old.get("scope") : String.join(" ", found.scopes());
        String state = random(32);
        String verifier = random(48);
        Map<String, String> s = new LinkedHashMap<>();
        s.put("authz", found.authorizationEndpoint());
        s.put("token", found.tokenEndpoint());
        s.put("client", client.id());
        if (client.secret() != null) s.put("clientSecret", client.secret());
        s.put("resource", found.resource());
        if (!wanted.isBlank()) s.put("scope", wanted);
        s.put("state", sha256Hex(state));
        s.put("verifier", verifier);
        s.put("stateExpires", String.valueOf(clock.instant().plus(LOGIN_WINDOW).getEpochSecond()));
        s.put("by", memberId.toString());
        Map<String, String> params = extra != null ? extra : Json.readMap(old.get("params"));
        if (!params.isEmpty()) s.put("params", Json.write(params));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", client.id());
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        query.put("resource", found.resource());
        if (!wanted.isBlank()) query.put("scope", wanted);
        params.forEach(query::putIfAbsent);
        StringBuilder url = new StringBuilder(found.authorizationEndpoint()).append(found.authorizationEndpoint().contains("?") ? '&' : '?');
        query.forEach((k, v) -> url.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        url.setLength(url.length() - 1);
        return new Started(write(s), url.toString());
    }

    /** Si este es el inicio de sesión que espera la conexión (el estado coincide, no venció y lo pidió la misma persona). */
    boolean awaits(String session, String state, UUID memberId) {
        if (session == null || state == null || state.isBlank()) return false;
        Map<String, String> s = read(session);
        return MessageDigest.isEqual(sha256Hex(state).getBytes(StandardCharsets.UTF_8), s.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))
                && memberId.toString().equals(s.get("by")) && Long.parseLong(s.getOrDefault("stateExpires", "0")) >= clock.instant().getEpochSecond();
    }

    /** Canjea el código por los tokens; el estado queda gastado. Devuelve la sesión ya iniciada. */
    String finish(String session, String code) {
        Map<String, String> s = new LinkedHashMap<>(read(session));
        OAuthClient.Discovery where = new OAuthClient.Discovery(s.get("authz"), s.get("token"), null, s.get("resource"), java.util.List.of());
        OAuthClient.Tokens tokens = call(() -> oauth.exchange(where, client(s), code, s.get("verifier"), redirectUri));
        s.remove("state");
        s.remove("verifier");
        s.remove("stateExpires");
        store(s, tokens);
        return write(s);
    }

    boolean connected(String session) {
        return session != null && read(session).get("access") != null;
    }

    /** El token vigente. {@code force}: renovarlo aunque no parezca vencido (el servidor acaba de rechazarlo). */
    Bearer bearer(String session, boolean force) {
        Map<String, String> s = new LinkedHashMap<>(read(session));
        String access = s.get("access");
        if (access == null) throw new DomainException("Falta iniciar sesión en esta conexión.");
        long expires = Long.parseLong(s.getOrDefault("expires", "0"));
        boolean stale = force || (expires > 0 && expires - EARLY_SECONDS <= clock.instant().getEpochSecond());
        if (!stale) return new Bearer(access, null);
        String refresh = s.get("refresh");
        if (refresh == null) {
            if (force) throw new DomainException("La sesión venció y el sistema no permite renovarla: vuelve a iniciar sesión en Conexiones.");
            return new Bearer(access, null);
        }
        OAuthClient.Tokens tokens;
        try {
            tokens = oauth.refresh(s.get("token"), client(s), refresh, s.get("resource"));
        } catch (OAuthClient.OAuthException e) {
            throw new DomainException("La sesión venció y no se pudo renovar: vuelve a iniciar sesión en Conexiones.");
        }
        store(s, tokens);
        return new Bearer(tokens.accessToken(), write(s));
    }

    private void store(Map<String, String> s, OAuthClient.Tokens tokens) {
        s.put("access", tokens.accessToken());
        // Algunos servidores rotan el token de renovación y otros conservan el mismo sin repetirlo.
        if (tokens.refreshToken() != null) s.put("refresh", tokens.refreshToken());
        if (tokens.expiresIn() > 0) s.put("expires", String.valueOf(clock.instant().getEpochSecond() + tokens.expiresIn()));
        else s.remove("expires");
    }

    private static OAuthClient.Client client(Map<String, String> s) {
        return new OAuthClient.Client(s.get("client"), s.get("clientSecret"));
    }

    private Map<String, String> read(String session) {
        return Json.readMap(secrets.decrypt(session));
    }

    private String write(Map<String, String> s) {
        return secrets.encrypt(Json.write(s));
    }

    private static <T> T call(java.util.function.Supplier<T> step) {
        try {
            return step.get();
        } catch (OAuthClient.OAuthException e) {
            throw new DomainException(e.getMessage());
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
