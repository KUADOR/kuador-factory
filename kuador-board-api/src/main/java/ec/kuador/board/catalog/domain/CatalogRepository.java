package ec.kuador.board.catalog.domain;

import java.util.List;
import java.util.Optional;

/**
 * Puerto: entradas del catálogo del espacio actual. Las entradas son los registros públicos
 * ({@code WorkflowTemplate}, {@code Methodology}...); el adaptador completa id, builtIn y version.
 */
public interface CatalogRepository {

    <T> List<T> list(CatalogType type, Class<T> recordType);

    <T> Optional<T> find(CatalogType type, String key, Class<T> recordType);

    /**
     * Inserta (expectedVersion nulo) o actualiza si la versión coincide; si otra persona guardó
     * antes, falla con conflicto.
     */
    <T> T save(CatalogType type, String key, T entry, boolean builtIn, Long expectedVersion, Class<T> recordType);

    void delete(CatalogType type, String key);

    /** La entrada en su forma guardada (JSON canónico, sin id, builtIn ni versión): para comparar contenidos. */
    String payloadOf(Object entry);
}
