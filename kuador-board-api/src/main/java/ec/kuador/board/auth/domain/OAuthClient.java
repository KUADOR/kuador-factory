package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.List;

/** Un cliente de IA registrado para iniciar sesión en el servidor MCP (registro dinámico, RFC 7591). */
public record OAuthClient(String clientId, String name, List<String> redirectUris, Instant createdAt) {}
