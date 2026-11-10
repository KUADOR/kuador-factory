package ec.kuador.board.workspace;

/** API pública del módulo: identidad del espacio de trabajo actual. */
public interface WorkspaceApi {

    /** Identificador (slug) y nombre visible de la empresa. */
    record Identity(String slug, String name) {}

    Identity identity();
}
