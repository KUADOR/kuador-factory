package ec.kuador.board.sync.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Una pieza del proyecto. Guarda una foto de su plantilla (clave, versión del catálogo, repo y ref)
 * para saber siempre con qué nació, aunque la plantilla cambie después.
 */
public record ProjectComponent(
        UUID id, String projectKey, String name, String templateKey, Long templateVersion, String templateRepo, String templateRef,
        String kind, String stack, String buildCommand, String testCommand, String runCommand, String repoLocation, String path,
        Long installationId, UUID createdBy, Instant createdAt) {

    /** Monorepo: la pieza vive en una carpeta; en un repo por pieza, en la raíz. */
    public boolean inFolder() {
        return path != null && !path.isEmpty();
    }
}
