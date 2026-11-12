package ec.kuador.board.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Puerto: el catálogo de la plataforma (borradores y lo publicado), sus versiones y los paquetes por industria. */
public interface PlatformCatalogStore {

    /** {@code payload}: borrador; {@code published}: lo publicado (nulo = nunca); {@code extra}: datos que la acompañan (JSON). */
    record Row(CatalogType type, String key, String payload, String published, String extra) {}

    record Release(int version, List<Map.Entry<CatalogType, String>> added, List<Map.Entry<CatalogType, String>> updated, Instant publishedAt) {}

    boolean isEmpty();

    List<Row> rows();

    Optional<Row> row(CatalogType type, String key);

    void save(Row row, Instant now);

    void delete(CatalogType type, String key);

    List<Release> releases();

    void saveRelease(Release release);

    // ---- Paquetes ----

    record Package(String code, String name, String description, List<String> templates, String starterTemplate, String starterKey,
            String starterName, int sortOrder, boolean active) {}

    List<Package> packages(boolean onlyActive);

    Optional<Package> pkg(String code);

    void savePackage(Package p, Instant now);

    void choosePackage(java.util.UUID workspaceId, String code, Instant now);

    Optional<String> packageOf(java.util.UUID workspaceId);
}
