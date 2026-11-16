package ec.kuador.board.github.domain;

import java.time.Instant;
import java.util.Set;

/** Puerto: lo que el tablero le pide a GitHub como app. Recibe los secretos ya descifrados. */
public interface GitHubAppGateway {

    /** Lo que devuelve GitHub al confirmar el manifiesto: la app creada y sus credenciales. */
    record ConvertedApp(long appId, String slug, String name, String ownerLogin, String htmlUrl, String clientId, String clientSecret,
            String privateKeyPem, String webhookSecret) {}

    record Account(String login, String type) {}

    record Token(String value, Instant expiresAt) {}

    /** Credenciales de la app para firmar (JWT) y para el intercambio OAuth. */
    record AppKeys(long appId, String clientId, String clientSecret, String privateKeyPem) {}

    /** GitHub no conoce la instalación: la app se desinstaló de esa cuenta o la app se volvió a crear. */
    final class InstallationGone extends ec.kuador.board.shared.error.DomainException {
        public InstallationGone(long installationId) {
            super("GitHub ya no tiene la instalación " + installationId + ".");
        }
    }

    ConvertedApp convertManifest(String code);

    /** Cuenta donde está instalada la app (con el JWT de la app). */
    Account installationAccount(AppKeys app, long installationId);

    Token installationToken(AppKeys app, long installationId);

    /**
     * Instalaciones que la persona que volvió de GitHub puede administrar: canjea el {@code code} OAuth por
     * un token de usuario (que no se guarda) y lista sus instalaciones de esta app.
     */
    Set<Long> userInstallations(AppKeys app, String oauthCode);
}
