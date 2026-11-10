package ec.kuador.board.mcp.infrastructure.web;

import ec.kuador.board.mcp.application.AgentKit;
import java.util.List;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kit para conectar un repo con el tablero desde un cliente de IA: para verlo (JSON) o descargarlo (.zip). */
@RestController
class AgentKitController {

    private final AgentKit kit;

    AgentKitController(AgentKit kit) {
        this.kit = kit;
    }

    record KitResponse(AgentKit.Connection connection, List<AgentKit.KitFile> files) {}

    @GetMapping("/api/agent-kit")
    KitResponse files(@RequestParam(defaultValue = "claude-code") String client, @RequestParam(defaultValue = "") Set<String> mcp) {
        return new KitResponse(kit.connection(), kit.files(AgentKit.Client.of(client), mcp));
    }

    @GetMapping(value = "/api/agent-kit.zip", produces = "application/zip")
    ResponseEntity<byte[]> zip(@RequestParam(defaultValue = "claude-code") String client, @RequestParam(defaultValue = "") Set<String> mcp) {
        AgentKit.Client c = AgentKit.Client.of(client);
        String file = "tablero-" + kit.connection().server() + "-" + c.id + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(kit.zip(c, mcp));
    }
}
