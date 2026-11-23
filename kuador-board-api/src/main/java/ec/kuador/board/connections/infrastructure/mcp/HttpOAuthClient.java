package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.OAuthClient;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.net.SafeUrls;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El inicio de sesión de un servidor MCP según su especificación de autorización: el servidor dice dónde está su
 * servidor de autorización (RFC 9728), este publica sus direcciones (RFC 8414 u OpenID), el tablero se registra solo si
 * se lo permiten (RFC 7591) y canjea el código con PKCE. Toda dirección que llega del servidor pasa por {@link SafeUrls}.
 */
@Component
class HttpOAuthClient implements OAuthClient {

    private static final int MAX_BODY = 200_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final Pattern SCOPE = Pattern.compile("scope=\"([^\"]*)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final boolean allowPrivate;

    HttpOAuthClient(@Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    @Override
    public Discovery discover(String mcpUrl) {
        URI mcp = safe(mcpUrl);
        String origin = origin(mcp);
        String path = mcp.getRawPath() == null || mcp.getRawPath().equals("/") ? "" : mcp.getRawPath();

        // 1. Sin credencial, el servidor responde 401 y dice dónde está su hoja de datos (y a veces qué permisos pedir).
        String challenge = challenge(mcp);
        Set<String> candidates = new LinkedHashSet<>();
        Matcher m = RESOURCE_METADATA.matcher(challenge);
        if (m.find()) candidates.add(m.group(1));
        if (!path.isEmpty()) candidates.add(origin + "/.well-known/oauth-protected-resource" + path);
        candidates.add(origin + "/.well-known/oauth-protected-resource");
        JsonNode resource = firstJson(candidates);

        String issuer = origin;
        String resourceId = mcpUrl.strip();
        List<String> scopes = new ArrayList<>();
        Matcher s = SCOPE.matcher(challenge);
        if (s.find() && !s.group(1).isBlank()) scopes.addAll(List.of(s.group(1).strip().split("\\s+")));
        if (resource != null) {
            if (resource.path("authorization_servers").isArray() && !resource.path("authorization_servers").isEmpty()) {
                issuer = resource.path("authorization_servers").get(0).asString(origin);
            }
            if (resource.path("resource").isString()) resourceId = resource.path("resource").asString();
            if (scopes.isEmpty()) for (JsonNode sc : resource.path("scopes_supported")) scopes.add(sc.asString(""));
        }

        // 2. El servidor de autorización publica sus direcciones.
        URI iss = safe(issuer);
        String issOrigin = origin(iss);
        String issPath = iss.getRawPath() == null || iss.getRawPath().equals("/") ? "" : iss.getRawPath().replaceAll("/+$", "");
        Set<String> metadata = new LinkedHashSet<>();
        if (issPath.isEmpty()) {
            metadata.add(issOrigin + "/.well-known/oauth-authorization-server");
            metadata.add(issOrigin + "/.well-known/openid-configuration");
        } else {
            metadata.add(issOrigin + "/.well-known/oauth-authorization-server" + issPath);
            metadata.add(issOrigin + "/.well-known/openid-configuration" + issPath);
            metadata.add(issOrigin + issPath + "/.well-known/openid-configuration");
        }
        JsonNode server = firstJson(metadata);
        if (server == null || !server.path("authorization_endpoint").isString() || !server.path("token_endpoint").isString()) {
            throw new OAuthException("Este servidor no publica cómo iniciar sesión. Si usa una clave o no pide nada, conéctalo sin esa opción.");
        }
        JsonNode methods = server.path("code_challenge_methods_supported");
        if (methods.isArray() && !methods.isEmpty() && !contains(methods, "S256")) {
            throw new OAuthException("El servidor no admite el inicio de sesión seguro (PKCE) que el tablero exige.");
        }
        String authorization = server.path("authorization_endpoint").asString();
        String token = server.path("token_endpoint").asString();
        String registration = server.path("registration_endpoint").isString() ? server.path("registration_endpoint").asString() : null;
        safe(authorization);
        safe(token);
        if (registration != null) safe(registration);
        return new Discovery(authorization, token, registration, resourceId, scopes.stream().filter(x -> !x.isBlank()).distinct().toList());
    }

    @Override
    public Client register(String registrationEndpoint, String redirectUri, String clientName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", clientName);
        body.put("redirect_uris", List.of(redirectUri));
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", "none");
        HttpResponse<String> res = send(HttpRequest.newBuilder(safe(registrationEndpoint)).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        JsonNode reply = parse(res.body());
        if (res.statusCode() / 100 != 2 || reply == null || !reply.path("client_id").isString()) {
            throw new OAuthException("El servidor no dejó registrar al tablero" + reason(reply) + ". Registra un cliente en ese sistema y pega aquí su identificador.");
        }
        return new Client(reply.path("client_id").asString(), reply.path("client_secret").isString() ? reply.path("client_secret").asString() : null);
    }

    @Override
    public Tokens exchange(Discovery where, Client client, String code, String verifier, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        form.put("resource", where.resource());
        return tokens(where.tokenEndpoint(), client, form);
    }

    @Override
    public Tokens refresh(String tokenEndpoint, Client client, String refreshToken, String resource) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("resource", resource);
        return tokens(tokenEndpoint, client, form);
    }

    private Tokens tokens(String endpoint, Client client, Map<String, String> form) {
        form.put("client_id", client.id());
        if (client.secret() != null && !client.secret().isBlank()) form.put("client_secret", client.secret());
        String body = form.entrySet().stream().filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpResponse<String> res = send(HttpRequest.newBuilder(safe(endpoint)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        JsonNode reply = parse(res.body());
        if (res.statusCode() / 100 != 2 || reply == null || !reply.path("access_token").isString()) {
            throw new OAuthException("El servidor no entregó el acceso" + reason(reply) + ".");
        }
        return new Tokens(reply.path("access_token").asString(), reply.path("refresh_token").isString() ? reply.path("refresh_token").asString() : null,
                reply.path("expires_in").asLong(0));
    }

    /**
     * La cabecera WWW-Authenticate de un pedido sin credencial; vacía si el servidor no la envía (algunos, como los de
     * Google, dejan saludar sin credencial y la piden recién al usar una herramienta).
     */
    private String challenge(URI mcp) {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"board\",\"version\":\"1\"}}}";
        HttpResponse<String> res = send(HttpRequest.newBuilder(mcp).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(init)));
        return String.join(", ", res.headers().allValues("WWW-Authenticate"));
    }

    private JsonNode firstJson(Set<String> urls) {
        for (String url : urls) {
            URI uri;
            try {
                uri = SafeUrls.requirePublicHttps(url, allowPrivate);
            } catch (DomainException e) {
                continue;
            }
            HttpResponse<String> res;
            try {
                res = send(HttpRequest.newBuilder(uri).header("Accept", "application/json").GET());
            } catch (OAuthException e) {
                continue;
            }
            if (res.statusCode() != 200) continue;
            JsonNode node = parse(res.body());
            if (node != null && node.isObject()) return node;
        }
        return null;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> res = http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.body() != null && res.body().length() > MAX_BODY) throw new OAuthException("La respuesta del servidor es demasiado grande.");
            return res;
        } catch (IOException e) {
            throw new OAuthException("No se pudo hablar con el servidor: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("Se interrumpió la llamada al servidor.");
        }
    }

    private URI safe(String url) {
        try {
            return SafeUrls.requirePublicHttps(url, allowPrivate);
        } catch (DomainException e) {
            throw new OAuthException(e.getMessage());
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) if (value.equals(n.asString(""))) return true;
        return false;
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String reason(JsonNode reply) {
        if (reply == null) return "";
        String text = reply.path("error_description").asString(reply.path("error").asString(""));
        return text.isBlank() ? "" : " (" + (text.length() > 300 ? text.substring(0, 300) + "…" : text) + ")";
    }
}
