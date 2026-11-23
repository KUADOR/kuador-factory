package ec.kuador.board.connections.infrastructure.web;

import ec.kuador.board.connections.application.ConnectionService;
import ec.kuador.board.connections.domain.ConnectionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Conexiones del negocio: las administra quien administra el espacio; la credencial nunca se devuelve. */
@RestController
class ConnectionController {

    private final ConnectionService service;

    ConnectionController(ConnectionService service) {
        this.service = service;
    }

    /** {@code secret} nulo conserva la credencial guardada; vacío la quita. */
    record SaveRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 500) String url, @Size(max = 64) String header,
            @Size(max = 4000) String secret) {}

    record WritesRequest(@Size(max = 200) List<String> tools) {}

    record BoardUsesRequest(@Size(max = 20) List<ConnectionService.BoardUseRequest> connections) {}

    @GetMapping("/api/connections")
    List<ConnectionService.View> list() {
        return service.list();
    }

    @PutMapping("/api/connections/{key}")
    ConnectionService.View save(@PathVariable String key, @Valid @RequestBody SaveRequest r) {
        return service.save(key, r.name(), r.url(), r.header(), r.secret());
    }

    @PostMapping("/api/connections/{key}/refresh")
    ConnectionService.View refresh(@PathVariable String key) {
        return service.refresh(key);
    }

    @PutMapping("/api/connections/{key}/writes")
    ConnectionService.View allowWrites(@PathVariable String key, @RequestBody WritesRequest r) {
        return service.allowWrites(key, r.tools());
    }

    record BoardsRequest(@Size(max = 50) List<ConnectionService.BoardUse> boards) {}

    @PutMapping("/api/connections/{key}/boards")
    ConnectionService.View setBoards(@PathVariable String key, @RequestBody BoardsRequest r) {
        return service.setBoards(key, r.boards());
    }

    @DeleteMapping("/api/connections/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key) {
        service.delete(key);
    }

    @GetMapping("/api/projects/{projectKey}/connections")
    List<ConnectionStore.Use> boardUses(@PathVariable String projectKey) {
        return service.boardUses(projectKey);
    }

    @PutMapping("/api/projects/{projectKey}/connections")
    List<ConnectionStore.Use> setBoardUses(@PathVariable String projectKey, @Valid @RequestBody BoardUsesRequest r) {
        return service.setBoardUses(projectKey, r.connections());
    }
}
