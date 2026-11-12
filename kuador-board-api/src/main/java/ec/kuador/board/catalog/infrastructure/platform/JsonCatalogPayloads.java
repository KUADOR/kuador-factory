package ec.kuador.board.catalog.infrastructure.platform;

import ec.kuador.board.catalog.domain.CatalogPayloads;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.infrastructure.CatalogJson;
import org.springframework.stereotype.Component;

@Component
class JsonCatalogPayloads implements CatalogPayloads {

    @Override
    public String payload(Object entry) {
        return CatalogJson.payload(entry);
    }

    @Override
    public Object parse(CatalogType type, String key, String payload) {
        return DbFactoryCatalog.parse(type, key, payload);
    }
}
