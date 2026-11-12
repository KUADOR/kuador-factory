package ec.kuador.board.catalog.domain;

/** Puerto: convertir una entrada del catálogo a su forma guardada (JSON canónico) y de vuelta. */
public interface CatalogPayloads {

    String payload(Object entry);

    Object parse(CatalogType type, String key, String payload);
}
