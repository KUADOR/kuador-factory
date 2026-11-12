package ec.kuador.board.catalog.application;

import ec.kuador.board.catalog.FactoryCatalogPublished;
import ec.kuador.board.catalog.domain.CatalogKeys;
import ec.kuador.board.catalog.domain.CatalogPayloads;
import ec.kuador.board.catalog.domain.CatalogRules;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.FactoryCatalog;
import ec.kuador.board.catalog.domain.PlatformCatalogStore;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El catálogo de la plataforma, que edita quien administra Kuador Go: plantillas de flujo, metodologías, roles de
 * agentes, servidores MCP y piezas, más los paquetes por industria. Los cambios quedan en borrador hasta publicar
 * una versión; al publicar, los espacios reciben lo nuevo y, en lo actualizado, la versión nueva si no la habían
 * modificado.
 */
@Service
public class PlatformCatalogService {

    private static final Pattern PACKAGE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final Pattern BOARD_KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

    private final PlatformCatalogStore store;
    private final FactoryCatalog factory;
    private final CatalogPayloads payloads;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PlatformCatalogService(PlatformCatalogStore store, FactoryCatalog factory, CatalogPayloads payloads, ApplicationEventPublisher events, Clock clock) {
        this.store = store;
        this.factory = factory;
        this.payloads = payloads;
        this.events = events;
        this.clock = clock;
    }

    public enum Status {
        /** Igual a lo publicado. */
        PUBLISHED,
        /** Con cambios sin publicar. */
        MODIFIED,
        /** Nunca publicada: los espacios todavía no la reciben. */
        NEW
    }

    /** Entrada del catálogo con su estado; {@code memberName} y {@code color} solo en roles (el agente que nace con cada espacio). */
    public record EntryView(String key, Status status, Object entry, String memberName, String color) {}

    public record Summary(int version, int pending, List<PlatformCatalogStore.Release> releases) {}

    @Transactional(readOnly = true)
    public List<EntryView> list(CatalogType type) {
        factory.version(); // asegura la siembra inicial
        return store.rows().stream().filter(r -> r.type() == type).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        factory.version();
        List<PlatformCatalogStore.Release> releases = store.releases();
        int pending = (int) store.rows().stream().filter(r -> !r.payload().equals(r.published())).count();
        return new Summary(releases.stream().mapToInt(PlatformCatalogStore.Release::version).max().orElse(0), pending,
                releases.reversed().stream().limit(20).toList());
    }

    /** Guarda el borrador (crea o actualiza). En roles, también el nombre y color del agente que nace con cada espacio. */
    @Transactional
    public EntryView save(CatalogType type, String key, Object entry, String memberName, String color) {
        factory.version();
        if (!key.equals(CatalogKeys.of(entry))) {
            throw new DomainException("La clave de la URL (" + key + ") no coincide con la de la entrada (" + CatalogKeys.of(entry) + ").");
        }
        CatalogRules.validate(type, key, entry);
        Optional<PlatformCatalogStore.Row> before = store.row(type, key);
        String extra = before.map(PlatformCatalogStore.Row::extra).orElse(null);
        if (type == CatalogType.AGENT_ROLE && (memberName != null || color != null)) {
            if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) throw new DomainException("El color va en formato #RRGGBB.");
            tools.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
            if (memberName != null && !memberName.isBlank()) node.put("memberName", memberName.strip());
            if (color != null && !color.isBlank()) node.put("color", color.strip());
            extra = node.toString();
        }
        PlatformCatalogStore.Row row = new PlatformCatalogStore.Row(type, key, payloads.payload(entry), before.map(PlatformCatalogStore.Row::published).orElse(null), extra);
        store.save(row, clock.instant());
        return view(row);
    }

    /** Vuelve el borrador a lo publicado (o lo quita si nunca se publicó). */
    @Transactional
    public void discard(CatalogType type, String key) {
        PlatformCatalogStore.Row row = store.row(type, key).orElseThrow(() -> new NotFoundException("No existe " + key + " en el catálogo de la plataforma."));
        if (row.published() == null) store.delete(type, key);
        else store.save(new PlatformCatalogStore.Row(type, key, row.published(), row.published(), row.extra()), clock.instant());
    }

    /** Quita la entrada del catálogo de la plataforma: los espacios nuevos ya no la reciben; los que la tienen la conservan. */
    @Transactional
    public void remove(CatalogType type, String key) {
        store.row(type, key).orElseThrow(() -> new NotFoundException("No existe " + key + " en el catálogo de la plataforma."));
        if (type == CatalogType.WORKFLOW_TEMPLATE && store.packages(false).stream().anyMatch(p -> p.templates().contains(key))) {
            throw new DomainException("Hay paquetes que usan la plantilla " + key + ": quítala de ellos primero.");
        }
        store.delete(type, key);
        factory.reload();
    }

    /** Publica una versión con todos los cambios en borrador. */
    @Transactional
    public Summary publish() {
        int current = factory.version();
        List<Map.Entry<CatalogType, String>> added = new ArrayList<>();
        List<Map.Entry<CatalogType, String>> updated = new ArrayList<>();
        List<FactoryCatalogPublished.Update> updates = new ArrayList<>();
        Instant now = clock.instant();
        for (PlatformCatalogStore.Row row : store.rows()) {
            if (row.payload().equals(row.published())) continue;
            if (row.published() == null) added.add(Map.entry(row.type(), row.key()));
            else {
                updated.add(Map.entry(row.type(), row.key()));
                updates.add(new FactoryCatalogPublished.Update(row.type().name(), row.key(), row.published()));
            }
            store.save(new PlatformCatalogStore.Row(row.type(), row.key(), row.payload(), row.payload(), row.extra()), now);
        }
        if (added.isEmpty() && updated.isEmpty()) throw new DomainException("No hay cambios para publicar.");
        int version = current + 1;
        store.saveRelease(new PlatformCatalogStore.Release(version, added, updated, now));
        factory.reload();
        events.publishEvent(new FactoryCatalogPublished(version, updates));
        return summary();
    }

    // ---- Paquetes por industria ----

    @Transactional(readOnly = true)
    public List<PlatformCatalogStore.Package> packages(boolean onlyActive) {
        return store.packages(onlyActive);
    }

    @Transactional(readOnly = true)
    public Optional<PlatformCatalogStore.Package> pkg(String code) {
        return code == null || code.isBlank() ? Optional.empty() : store.pkg(code.strip().toUpperCase());
    }

    @Transactional
    public PlatformCatalogStore.Package savePackage(PlatformCatalogStore.Package p) {
        if (p.code() == null || !PACKAGE_CODE.matcher(p.code()).matches()) throw new DomainException("El código va en mayúsculas, ej. COMMERCE.");
        if (p.name() == null || p.name().isBlank() || p.name().length() > 60) throw new DomainException("El nombre es obligatorio (hasta 60 caracteres).");
        if (p.description() == null || p.description().isBlank() || p.description().length() > 300) {
            throw new DomainException("La descripción es obligatoria (hasta 300 caracteres).");
        }
        List<String> templates = p.templates() == null ? List.of() : p.templates().stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().toList();
        if (templates.isEmpty()) throw new DomainException("Elige al menos una plantilla.");
        for (String t : templates) {
            if (factory.entry(CatalogType.WORKFLOW_TEMPLATE, t).isEmpty()) throw new DomainException("La plantilla " + t + " no está publicada en el catálogo.");
        }
        String starter = p.starterTemplate() == null || p.starterTemplate().isBlank() ? null : p.starterTemplate().strip();
        if (starter != null) {
            if (!templates.contains(starter)) throw new DomainException("El tablero inicial usa una de las plantillas del paquete.");
            if (p.starterKey() == null || !BOARD_KEY.matcher(p.starterKey()).matches()) throw new DomainException("La clave del tablero inicial va en mayúsculas, ej. VEN.");
            if (p.starterName() == null || p.starterName().isBlank()) throw new DomainException("Ponle nombre al tablero inicial.");
        }
        PlatformCatalogStore.Package clean = new PlatformCatalogStore.Package(p.code(), p.name().strip(), p.description().strip(), templates, starter,
                starter == null ? null : p.starterKey(), starter == null ? null : p.starterName().strip(), p.sortOrder(), p.active());
        store.savePackage(clean, clock.instant());
        return clean;
    }

    private EntryView view(PlatformCatalogStore.Row row) {
        Status status = row.published() == null ? Status.NEW : row.payload().equals(row.published()) ? Status.PUBLISHED : Status.MODIFIED;
        String memberName = null;
        String color = null;
        if (row.extra() != null) {
            tools.jackson.databind.JsonNode node = JSON.readTree(row.extra());
            memberName = node.path("memberName").asString(null);
            color = node.path("color").asString(null);
        }
        return new EntryView(row.key(), status, payloads.parse(row.type(), row.key(), row.payload()), memberName, color);
    }
}
