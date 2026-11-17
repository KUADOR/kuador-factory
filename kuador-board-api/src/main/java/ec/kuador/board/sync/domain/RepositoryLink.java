package ec.kuador.board.sync.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Repositorio vinculado a un proyecto. GITHUB:
 * {@code location} es "owner/repo", {@code branch} la rama principal y {@code secret} el token
 * cifrado (nunca sale de la API). GITHUB_APP: igual, pero {@code secret} es el número de la conexión
 * de GitHub del espacio (no es un secreto: el token de corta duración lo pide en cada uso).
 */
public record RepositoryLink(
        String projectKey, String kind, String location, String branch, String secret, Instant linkedAt, UUID linkedBy) {}
