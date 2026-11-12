package ec.kuador.board.catalog.infrastructure.persistence;

import ec.kuador.board.catalog.domain.CatalogRepository;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.infrastructure.CatalogJson;
import ec.kuador.board.shared.error.ConflictException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataCatalogEntries extends JpaRepository<CatalogEntryEntity, UUID> {

    List<CatalogEntryEntity> findByTypeOrderByKey(CatalogType type);

    Optional<CatalogEntryEntity> findByTypeAndKey(CatalogType type, String key);
}

@Repository
class JpaCatalogRepository implements CatalogRepository {

    private final SpringDataCatalogEntries jpa;
    private final Clock clock;

    JpaCatalogRepository(SpringDataCatalogEntries jpa, Clock clock) {
        this.jpa = jpa;
        this.clock = clock;
    }

    @Override
    public <T> List<T> list(CatalogType type, Class<T> recordType) {
        return jpa.findByTypeOrderByKey(type).stream().map(e -> toRecord(e, recordType)).toList();
    }

    @Override
    public <T> Optional<T> find(CatalogType type, String key, Class<T> recordType) {
        return jpa.findByTypeAndKey(type, key).map(e -> toRecord(e, recordType));
    }

    @Override
    public <T> T save(CatalogType type, String key, T entry, boolean builtIn, Long expectedVersion, Class<T> recordType) {
        var now = clock.instant();
        CatalogEntryEntity entity = jpa.findByTypeAndKey(type, key).orElse(null);
        if (entity == null) {
            entity = new CatalogEntryEntity(type, key, now);
        } else if (expectedVersion == null || expectedVersion != entity.version) {
            throw new ConflictException("Otra persona cambió " + key + " mientras lo editabas (versión " + entity.version
                    + "). Recarga y vuelve a aplicar tus cambios.");
        }
        entity.payload = CatalogJson.payload(entry);
        entity.builtIn = builtIn;
        entity.updatedAt = now;
        return toRecord(jpa.saveAndFlush(entity), recordType);
    }

    @Override
    public void delete(CatalogType type, String key) {
        jpa.findByTypeAndKey(type, key).ifPresent(jpa::delete);
    }

    @Override
    public String payloadOf(Object entry) {
        return CatalogJson.payload(entry);
    }

    private static <T> T toRecord(CatalogEntryEntity e, Class<T> recordType) {
        return CatalogJson.read(e.payload, e.id, e.builtIn, e.version, recordType);
    }
}
