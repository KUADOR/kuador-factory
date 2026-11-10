package ec.kuador.board.sync.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Repositorio vinculado a un proyecto. LOCAL: {@code location} es una carpeta absoluta. GITHUB:
 * {@code location} es "owner/repo", {@code branch} la rama principal y {@code secret} el token
 * cifrado (nunca sale de la API).
 */
public record RepositoryLink(
        String projectKey, String kind, String location, String branch, String secret, Instant linkedAt, UUID linkedBy) {}
