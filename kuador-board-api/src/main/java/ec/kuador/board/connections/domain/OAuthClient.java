package ec.kuador.board.connections.domain;

import java.util.List;

/** Puerto: el lado OAuth de un servidor MCP que pide iniciar sesión (descubrir, registrarse, canjear y renovar tokens). */
public interface OAuthClient {

    /** No se pudo completar un paso del inicio de sesión. El mensaje se le puede mostrar a una persona. */
    class OAuthException extends RuntimeException {
        public OAuthException(String message) {
            super(message);
        }
    }

    /**
     * Lo que el servidor MCP publica sobre cómo entrar. {@code registrationEndpoint}: nulo si no deja registrarse solo
     * (hay que traer un cliente ya registrado). {@code resource}: el identificador del servidor que acompaña cada pedido
     * de token. {@code scopes}: los permisos que sugiere pedir.
     */
    record Discovery(String authorizationEndpoint, String tokenEndpoint, String registrationEndpoint, String resource, List<String> scopes) {}

    /** El cliente con el que el tablero se presenta. {@code secret} nulo = cliente público (solo PKCE). */
    record Client(String id, String secret) {}

    /** {@code expiresIn}: segundos de vida del token de acceso (0 = no lo dijo). {@code refreshToken} nulo = no se puede renovar. */
    record Tokens(String accessToken, String refreshToken, long expiresIn) {}

    Discovery discover(String mcpUrl);

    Client register(String registrationEndpoint, String redirectUri, String clientName);

    Tokens exchange(Discovery where, Client client, String code, String verifier, String redirectUri);

    Tokens refresh(String tokenEndpoint, Client client, String refreshToken, String resource);
}
