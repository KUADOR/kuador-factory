package ec.kuador.board.auth.application;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.domain.AuthorizationCode;
import ec.kuador.board.auth.domain.OAuthClient;
import ec.kuador.board.auth.domain.OAuthStore;
import ec.kuador.board.auth.domain.Tokens;
import ec.kuador.board.shared.error.ForbiddenException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inicio de sesión de los clientes de IA en el servidor MCP: OAuth 2.1 con código de autorización y PKCE
 * (S256), clientes públicos con registro dinámico. La persona aprueba en la web con su sesión; el canje
 * devuelve una conexión de agentes locales suya (la misma que antes se copiaba a mano), que puede revocar.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    /** Esquema propio de una app de escritorio (RFC 8252 §7.1): con un punto, p. ej. com.ejemplo.app. */
    private static final Pattern PRIVATE_SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*\\.[a-z0-9+.-]+$");
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthStore store;
    private final AuthService auth;
    private final Clock clock;

    OAuthService(OAuthStore store, AuthService auth, Clock clock) {
        this.store = store;
        this.auth = auth;
        this.clock = clock;
    }

    /** Error OAuth (RFC 6749 §5.2): {@code code} va tal cual en la respuesta. */
    public static class OAuthException extends RuntimeException {
        public final String code;

        public OAuthException(String code, String description) {
            super(description);
            this.code = code;
        }
    }

    /** Lo que la web muestra en la pantalla de permiso: quién pide y a dónde vuelve. */
    public record ClientInfo(String clientId, String name, String redirectHost, boolean local) {}

    /** El permiso dado: a dónde vuelve el navegador (con el código, o con el error si se negó). */
    public record Decision(String redirect) {}

    public record Issued(String accessToken, String scope) {}

    // ---- Registro dinámico (RFC 7591) ----

    @Transactional
    public OAuthClient register(String rawName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > 10) {
            throw new OAuthException("invalid_redirect_uri", "Indica entre 1 y 10 redirect_uris.");
        }
        redirectUris.forEach(OAuthService::checkRedirect);
        String name = rawName == null || rawName.isBlank() ? "Cliente MCP" : rawName.strip();
        if (name.length() > 100) name = name.substring(0, 100);
        OAuthClient client = new OAuthClient("kfc_" + random(18), name, List.copyOf(redirectUris), clock.instant());
        store.saveClient(client);
        log.info("Cliente OAuth registrado: {} ({})", client.name(), client.clientId());
        return client;
    }

    // ---- Autorización (la pantalla de permiso de la web) ----

    @Transactional(readOnly = true)
    public ClientInfo describe(String clientId, String redirectUri) {
        OAuthClient client = client(clientId);
        String redirect = registeredRedirect(client, redirectUri);
        URI uri = URI.create(redirect);
        boolean local = uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
        return new ClientInfo(client.clientId(), client.name(), uri.getHost() == null ? uri.getScheme() : uri.getHost(), local);
    }

    /**
     * La persona aprobó (o negó) el acceso. Solo personas con edición: la conexión pone agentes a trabajar.
     * {@code state} vuelve tal cual al cliente.
     */
    @Transactional
    public Decision decide(AuthenticatedMember who, String clientId, String redirectUri, String codeChallenge, String method, String state, boolean approved) {
        OAuthClient client = client(clientId);
        String redirect = registeredRedirect(client, redirectUri);
        if (!approved) return new Decision(withParams(redirect, "error", "access_denied", "state", state));
        if (who.agent() || who.runner()) throw new ForbiddenException("Solo una persona puede conectar su cliente de IA.");
        if (who.viewOnly()) throw new ForbiddenException("Con acceso de solo lectura no se pueden poner agentes a trabajar.");
        if (!"S256".equals(method) || codeChallenge == null || !CHALLENGE.matcher(codeChallenge).matches()) {
            return new Decision(withParams(redirect, "error", "invalid_request", "error_description", "Se requiere PKCE con S256.", "state", state));
        }
        String code = "kfo_" + random(32);
        store.saveCode(new AuthorizationCode(Tokens.hash(code), client.clientId(), who.workspaceId(), who.memberId(), redirect, codeChallenge,
                clock.instant().plus(CODE_TTL), null));
        return new Decision(withParams(redirect, "code", code, "state", state));
    }

    // ---- Canje del código (token endpoint) ----

    /**
     * Sin transacción propia: llega sin sesión (sin espacio en contexto) y la conexión se emite en el espacio del
     * código, en su propia transacción. Marcar el código como usado es atómico.
     */
    public Issued exchange(String clientId, String code, String redirectUri, String verifier) {
        Instant now = clock.instant();
        if (code == null || code.isBlank() || verifier == null || verifier.isBlank()) {
            throw new OAuthException("invalid_request", "Faltan code o code_verifier.");
        }
        String hash = Tokens.hash(code.strip());
        AuthorizationCode found = store.findCode(hash).filter(c -> c.usable(now))
                .orElseThrow(() -> new OAuthException("invalid_grant", "El código no existe, venció o ya se usó."));
        if (!found.clientId().equals(clientId)) throw new OAuthException("invalid_grant", "El código es de otro cliente.");
        if (!found.redirectUri().equals(redirectUri)) throw new OAuthException("invalid_grant", "redirect_uri no coincide con el de la autorización.");
        if (!found.codeChallenge().equals(s256(verifier.strip()))) throw new OAuthException("invalid_grant", "code_verifier no corresponde.");
        if (!store.markUsed(hash, now)) throw new OAuthException("invalid_grant", "El código ya se usó.");
        String name = client(clientId).name();
        AuthService.IssuedToken token;
        try {
            token = auth.issueRunnerToken(found.workspaceId(), found.memberId(), name + " · inicio de sesión");
        } catch (ForbiddenException e) {
            throw new OAuthException("invalid_grant", e.getMessage());
        }
        return new Issued(token.token(), "mcp");
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional
    public void purgeExpiredCodes() {
        store.deleteExpiredCodes(clock.instant());
    }

    // ---- Apoyo ----

    private OAuthClient client(String clientId) {
        return Optional.ofNullable(clientId).flatMap(store::findClient)
                .orElseThrow(() -> new OAuthException("invalid_client", "Cliente desconocido: vuelve a conectar el servidor desde tu cliente de IA."));
    }

    /**
     * El redirect pedido debe ser uno de los registrados. En loopback el puerto puede cambiar (RFC 8252 §7.3):
     * los clientes de terminal abren un puerto libre en cada inicio de sesión.
     */
    static String registeredRedirect(OAuthClient client, String requested) {
        if (requested == null || requested.isBlank()) {
            if (client.redirectUris().size() == 1) return client.redirectUris().getFirst();
            throw new OAuthException("invalid_request", "Falta redirect_uri.");
        }
        URI asked = parse(requested);
        for (String registered : client.redirectUris()) {
            if (registered.equals(requested)) return requested;
            URI r = parse(registered);
            if (loopback(asked) && loopback(r) && asked.getScheme().equals(r.getScheme()) && asked.getHost().equalsIgnoreCase(r.getHost())
                    && java.util.Objects.equals(asked.getRawPath(), r.getRawPath())) {
                return requested;
            }
        }
        throw new OAuthException("invalid_request", "redirect_uri no está registrado para este cliente.");
    }

    static void checkRedirect(String uri) {
        URI u = parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = u.getFragment() == null && (scheme.equals("https") && u.getHost() != null
                || scheme.equals("http") && loopback(u)
                || PRIVATE_SCHEME.matcher(scheme).matches());
        if (!ok) throw new OAuthException("invalid_redirect_uri", "redirect_uri debe ser https, http a localhost o el esquema propio de una app: " + uri);
    }

    private static boolean loopback(URI u) {
        return "http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && LOOPBACK.contains(u.getHost().toLowerCase(Locale.ROOT));
    }

    private static URI parse(String uri) {
        try {
            URI u = new URI(uri);
            if (u.getScheme() == null) throw new URISyntaxException(uri, "sin esquema");
            return u;
        } catch (URISyntaxException e) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uri no es una URL válida: " + uri);
        }
    }

    private static String withParams(String redirect, String... pairs) {
        StringBuilder sb = new StringBuilder(redirect).append(redirect.contains("?") ? '&' : '?');
        boolean first = true;
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) continue;
            if (!first) sb.append('&');
            sb.append(pairs[i]).append('=').append(java.net.URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
