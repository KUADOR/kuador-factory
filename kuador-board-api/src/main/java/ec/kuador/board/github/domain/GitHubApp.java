package ec.kuador.board.github.domain;

import java.time.Instant;
import java.util.UUID;

/** La GitHub App de la plataforma. {@code clientSecret}, {@code privateKey} y {@code webhookSecret} van cifrados. */
public record GitHubApp(
        long appId, String slug, String name, String ownerLogin, String htmlUrl, String clientId, String clientSecret, String privateKey,
        String webhookSecret, UUID registeredBy, Instant registeredAt) {}
