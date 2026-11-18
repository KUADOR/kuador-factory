package ec.kuador.board.catalog.infrastructure.web;

import ec.kuador.board.catalog.application.CatalogService;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.infrastructure.CatalogJson;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Una sola API para los cuatro catálogos: {@code /api/catalog/{tipo}} con tipo workflow-templates,
 * methodologies, roles o mcp-servers. Leer: cualquiera; editar: solo personas (SecurityConfiguration).
 */
@RestController
@RequestMapping("/api/catalog/{type}")
class CatalogController {

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    List<?> list(@PathVariable String type) {
        return service.list(type(type));
    }

    @GetMapping("/{key}")
    Object get(@PathVariable String type, @PathVariable String key) {
        return service.get(type(type), key);
    }

    /** Crea o actualiza; al actualizar se envía la {@code version} leída (bloqueo optimista). */
    @PutMapping("/{key}")
    Object save(@PathVariable String type, @PathVariable String key, @RequestBody JsonNode body) {
        CatalogType t = type(type);
        return service.save(t, key, CatalogJson.fromRequest(body, t.recordType()));
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String type, @PathVariable String key) {
        service.delete(type(type), key);
    }

    /** El historial de una entrada: cada versión con quién la guardó y cuándo. */
    @GetMapping("/{key}/revisions")
    List<ec.kuador.board.catalog.domain.CatalogRepository.Revision> revisions(@PathVariable String type, @PathVariable String key) {
        return service.revisions(type(type), key);
    }

    @GetMapping("/{key}/revisions/{id}")
    Object revision(@PathVariable String type, @PathVariable String key, @PathVariable java.util.UUID id) {
        return service.revision(type(type), key, id);
    }

    @PostMapping("/{key}/revisions/{id}/restore")
    Object restoreRevision(@PathVariable String type, @PathVariable String key, @PathVariable java.util.UUID id) {
        return service.restoreRevision(type(type), key, id);
    }

    @PostMapping("/{key}/restore")
    Object restore(@PathVariable String type, @PathVariable String key) {
        return service.restore(type(type), key);
    }

    private static CatalogType type(String path) {
        return CatalogType.ofPath(path).orElseThrow(() -> new NotFoundException(
                "Catálogo desconocido: " + path + ". Usa workflow-templates, methodologies, roles o mcp-servers."));
    }
}
