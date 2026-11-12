package ec.kuador.board.catalog.infrastructure.web;

import ec.kuador.board.catalog.application.CatalogService;
import ec.kuador.board.catalog.application.PlatformCatalogService;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.PlatformCatalogStore;
import ec.kuador.board.catalog.infrastructure.CatalogJson;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Catálogo de la plataforma (solo quien administra Kuador Go, por /api/platform) y los paquetes por industria
 * (públicos para el alta; el del espacio actual para recomendar plantillas).
 */
@RestController
class PlatformCatalogController {

    private final PlatformCatalogService platform;
    private final CatalogService catalog;

    PlatformCatalogController(PlatformCatalogService platform, CatalogService catalog) {
        this.platform = platform;
        this.catalog = catalog;
    }

    @GetMapping("/api/platform/catalog")
    PlatformCatalogService.Summary summary() {
        return platform.summary();
    }

    @GetMapping("/api/platform/catalog/{type}")
    List<PlatformCatalogService.EntryView> list(@PathVariable String type) {
        return platform.list(type(type));
    }

    /** Guarda el borrador: {@code entry} es la entrada; en roles, {@code memberName} y {@code color} del agente de cada espacio. */
    @PutMapping("/api/platform/catalog/{type}/{key}")
    PlatformCatalogService.EntryView save(@PathVariable String type, @PathVariable String key, @RequestBody JsonNode body) {
        CatalogType t = type(type);
        Object entry = CatalogJson.fromRequest(body.path("entry"), t.recordType());
        return platform.save(t, key, entry, text(body, "memberName"), text(body, "color"));
    }

    @PostMapping("/api/platform/catalog/{type}/{key}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable String type, @PathVariable String key) {
        platform.discard(type(type), key);
    }

    @DeleteMapping("/api/platform/catalog/{type}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String type, @PathVariable String key) {
        platform.remove(type(type), key);
    }

    @PostMapping("/api/platform/catalog/publish")
    PlatformCatalogService.Summary publish() {
        return platform.publish();
    }

    // ---- Paquetes ----

    @GetMapping("/api/platform/packages")
    List<PlatformCatalogStore.Package> packages() {
        return platform.packages(false);
    }

    @PutMapping("/api/platform/packages/{code}")
    PlatformCatalogStore.Package savePackage(@PathVariable String code, @RequestBody PlatformCatalogStore.Package body) {
        return platform.savePackage(new PlatformCatalogStore.Package(code.toUpperCase(), body.name(), body.description(), body.templates(),
                body.starterTemplate(), body.starterKey(), body.starterName(), body.sortOrder(), body.active()));
    }

    @GetMapping("/api/public/packages")
    List<PlatformCatalogStore.Package> publicPackages() {
        return platform.packages(true);
    }

    /** 204 si el espacio no eligió paquete. */
    @GetMapping("/api/workspace/package")
    ResponseEntity<PlatformCatalogStore.Package> current() {
        return catalog.currentPackage().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    private static CatalogType type(String path) {
        return CatalogType.ofPath(path).orElseThrow(() -> new NotFoundException("Catálogo desconocido: " + path + "."));
    }
}
