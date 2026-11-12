package ec.kuador.board.auth.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.application.OAuthService;
import ec.kuador.board.auth.application.OAuthService.OAuthException;
import ec.kuador.board.auth.domain.OAuthClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth del servidor MCP. Públicos (por el dominio de la API): los metadatos ({@code /.well-known/...}), el
 * registro de clientes y el canje del código. La pantalla de permiso vive en la web, que llama a
 * {@code /api/oauth/...} con la sesión de la persona.
 */
@RestController
class OAuthController {

    private final OAuthService oauth;
    private final String publicUrl;
    private final String webUrl;

    private final ec.kuador.board.shared.web.RateLimiter registerPerIp = new ec.kuador.board.shared.web.RateLimiter(20, java.time.Duration.ofHours(1));
    private final ec.kuador.board.shared.web.WebSecret web;

    OAuthController(OAuthService oauth, @Value("${board.public-url:http://localhost:8091}") String publicUrl,
            @Value("${board.web-url:http://localhost:3600}") String webUrl, ec.kuador.board.shared.web.WebSecret web) {
        this.web = web;
        this.oauth = oauth;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    // ---- Metadatos ----

    /** Recurso protegido (RFC 9728): el servidor MCP y quién emite sus tokens. */
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    ResponseEntity<Map<String, Object>> protectedResource() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", publicUrl + "/mcp");
        m.put("authorization_servers", List.of(publicUrl));
        m.put("bearer_methods_supported", List.of("header"));
        m.put("scopes_supported", List.of("mcp"));
        return cached(m);
    }

    /** Servidor de autorización (RFC 8414). La autorización la da una persona en la web. */
    @GetMapping("/.well-known/oauth-authorization-server")
    ResponseEntity<Map<String, Object>> authorizationServer() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", publicUrl);
        m.put("authorization_endpoint", webUrl + "/oauth/authorize");
        m.put("token_endpoint", publicUrl + "/oauth/token");
        m.put("registration_endpoint", publicUrl + "/oauth/register");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        m.put("scopes_supported", List.of("mcp"));
        return cached(m);
    }

    // ---- Registro y canje (clientes de IA) ----

    record RegistrationRequest(String client_name, List<String> redirect_uris) {}

    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest r, jakarta.servlet.http.HttpServletRequest http) {
        // Registro abierto (lo pide el protocolo de los clientes de IA), pero con tope por IP para no llenar la tabla.
        if (registerPerIp.hit(web.clientIp(http), java.time.Instant.now())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados registros desde esta IP; reintenta más tarde.");
        }
        OAuthClient c = oauth.register(r.client_name(), r.redirect_uris());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_id", c.clientId());
        m.put("client_id_issued_at", c.createdAt().getEpochSecond());
        m.put("client_name", c.name());
        m.put("redirect_uris", c.redirectUris());
        m.put("grant_types", List.of("authorization_code"));
        m.put("response_types", List.of("code"));
        m.put("token_endpoint_auth_method", "none");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(m);
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> token(@RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(required = false) String code, @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "client_id", required = false) String clientId, @RequestParam(name = "code_verifier", required = false) String verifier) {
        if (!"authorization_code".equals(grantType)) throw new OAuthException("unsupported_grant_type", "Solo authorization_code.");
        OAuthService.Issued t = oauth.exchange(clientId, code, redirectUri, verifier);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("access_token", t.accessToken(), "token_type", "Bearer", "scope", t.scope()));
    }

    // ---- Pantalla de permiso (la web, con la sesión de la persona) ----

    @GetMapping("/api/oauth/client")
    OAuthService.ClientInfo client(@RequestParam("client_id") String clientId, @RequestParam(name = "redirect_uri", required = false) String redirectUri) {
        return oauth.describe(clientId, redirectUri);
    }

    record DecisionRequest(@NotBlank @Size(max = 64) String clientId, @Size(max = 2000) String redirectUri, @Size(max = 128) String codeChallenge,
            @Size(max = 10) String codeChallengeMethod, @Size(max = 1000) String state, boolean approved) {}

    @PostMapping("/api/oauth/authorize")
    OAuthService.Decision authorize(@AuthenticationPrincipal AuthenticatedMember who, @Valid @RequestBody DecisionRequest r) {
        return oauth.decide(who, r.clientId(), r.redirectUri(), r.codeChallenge(), r.codeChallengeMethod(), r.state(), r.approved());
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, String>> oauthError(OAuthException e) {
        HttpStatus status = "invalid_client".equals(e.code) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", e.code, "error_description", e.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> cached(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1))).body(body);
    }
}
