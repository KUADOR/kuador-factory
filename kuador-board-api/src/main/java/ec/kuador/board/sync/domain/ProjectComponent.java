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
        Long installationId, UUID createdBy, Instant createdAt, String notes) {

    /**
     * La pieza con lo que el equipo ajustó después de crearla: stack (versiones), comandos y notas para los agentes.
     * La plantilla de origen no cambia; esto es lo que leen los agentes y el kit.
     */
    public ProjectComponent adjusted(String stack, String build, String test, String run, String notes) {
        return new ProjectComponent(id, projectKey, name, templateKey, templateVersion, templateRepo, templateRef, kind, stack, build, test, run,
                repoLocation, path, installationId, createdBy, createdAt, notes);
    }

    /** Monorepo: la pieza vive en una carpeta; en un repo por pieza, en la raíz. */
    public boolean inFolder() {
        return path != null && !path.isEmpty();
    }
}
