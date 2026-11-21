package ec.kuador.board.issue;

import java.util.UUID;

/** El runner actualizó la rama de la tarjeta y su PR ya no tiene conflictos: la revisión sigue donde estaba. */
public record BranchSynced(UUID workspaceId, String issueKey, String projectKey) {}
