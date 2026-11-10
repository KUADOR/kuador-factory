package ec.kuador.board.shared.tenant;

import java.util.UUID;

/** Evento que pertenece a un espacio: sus listeners trabajan dentro de ese espacio. */
public interface WorkspaceScoped {

    UUID workspaceId();
}
