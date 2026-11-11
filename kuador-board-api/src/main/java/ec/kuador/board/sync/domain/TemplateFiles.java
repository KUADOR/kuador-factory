package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto: lee el código base de una plantilla de pieza (su repo en una versión fija). */
public interface TemplateFiles {

    /** Todos los archivos de {@code repository} en {@code ref}, con la conexión de GitHub del espacio. */
    List<RepositoryCreator.File> read(long installationId, String repository, String ref);
}
