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

    /** Versiones que se guardan por entrada; las más viejas se borran. */
    static final int KEEP = 30;

    private final SpringDataCatalogEntries jpa;
    private final Clock clock;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    JpaCatalogRepository(SpringDataCatalogEntries jpa, Clock clock, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jpa = jpa;
        this.clock = clock;
        this.jdbc = jdbc;
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
        // Las entradas de antes del historial no tienen versión base: la que había queda guardada antes de pisarla.
        if (entity.payload != null && revisions(type, key).isEmpty()) baseline(type, key, entity.version, entity.payload, entity.updatedAt);
        entity.payload = CatalogJson.payload(entry);
        entity.builtIn = builtIn;
        entity.updatedAt = now;
        CatalogEntryEntity saved = jpa.saveAndFlush(entity);
        revision(type, key, saved.version, saved.payload, now);
        return toRecord(saved, recordType);
    }

    /** Deja la versión en el historial, con quién la guardó (si fue una persona; si no, «de fábrica» o el sistema). */
    private void revision(CatalogType type, String key, long version, String payload, java.time.Instant now) {
        java.util.UUID ws = ec.kuador.board.shared.tenant.TenantContext.current();
        if (ws == null || ec.kuador.board.shared.tenant.TenantContext.NONE.equals(ws) || ec.kuador.board.shared.tenant.TenantContext.ROOT.equals(ws)) return;
        // Quién hizo la petición (lo deja el filtro de seguridad para la auditoría); fuera de una petición, nadie.
        ec.kuador.board.shared.web.AuditHints.Actor who = null;
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(ec.kuador.board.shared.web.AuditHints.ACTOR,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST) instanceof ec.kuador.board.shared.web.AuditHints.Actor a) who = a;
        jdbc.sql("""
                insert into catalog_revision (id, workspace_id, type, entry_key, version, payload, saved_by, saved_by_name, saved_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(java.util.UUID.randomUUID(), ws, type.name(), key, version, payload, who == null ? null : who.memberId(), who == null ? null : who.name(),
                        java.sql.Timestamp.from(now))
                .update();
        jdbc.sql("""
                delete from catalog_revision where workspace_id = ? and type = ? and entry_key = ? and id not in (
                    select id from catalog_revision where workspace_id = ? and type = ? and entry_key = ? order by saved_at desc limit ?)""")
                .params(ws, type.name(), key, ws, type.name(), key, KEEP).update();
    }

    private void baseline(CatalogType type, String key, long version, String payload, java.time.Instant at) {
        java.util.UUID ws = ec.kuador.board.shared.tenant.TenantContext.current();
        if (ws == null || ec.kuador.board.shared.tenant.TenantContext.NONE.equals(ws) || ec.kuador.board.shared.tenant.TenantContext.ROOT.equals(ws)) return;
        jdbc.sql("""
                insert into catalog_revision (id, workspace_id, type, entry_key, version, payload, saved_by, saved_by_name, saved_at)
                values (?, ?, ?, ?, ?, ?, null, null, ?)""")
                .params(java.util.UUID.randomUUID(), ws, type.name(), key, version, payload, java.sql.Timestamp.from(at)).update();
    }

    @Override
    public List<Revision> revisions(CatalogType type, String key) {
        return jdbc.sql("""
                select id, version, saved_by_name, saved_at from catalog_revision
                where workspace_id = ? and type = ? and entry_key = ? order by saved_at desc""")
                .params(ec.kuador.board.shared.tenant.TenantContext.require(), type.name(), key)
                .query((rs, n) -> new Revision(rs.getObject(1, java.util.UUID.class), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();
    }

    @Override
    public <T> Optional<T> revision(CatalogType type, String key, java.util.UUID id, Class<T> recordType) {
        return jdbc.sql("select payload from catalog_revision where workspace_id = ? and type = ? and entry_key = ? and id = ?")
                .params(ec.kuador.board.shared.tenant.TenantContext.require(), type.name(), key, id)
                .query(String.class).optional()
                // Con el id y la versión de la entrada vigente: así restaurarla es un guardado normal (y detecta cambios ajenos).
                .flatMap(payload -> jpa.findByTypeAndKey(type, key).map(e -> CatalogJson.read(payload, e.id, e.builtIn, e.version, recordType)));
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
