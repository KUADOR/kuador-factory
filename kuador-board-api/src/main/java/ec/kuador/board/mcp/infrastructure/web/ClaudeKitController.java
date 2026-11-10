package ec.kuador.board.mcp.infrastructure.web;

import ec.kuador.board.mcp.application.ClaudeKit;
import java.util.List;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kit de Claude Code para un repositorio: como lista de archivos (para verlos) o como .zip. */
@RestController
class ClaudeKitController {

    private final ClaudeKit kit;

    ClaudeKitController(ClaudeKit kit) {
        this.kit = kit;
    }

    @GetMapping("/api/claude-kit")
    List<ClaudeKit.KitFile> files(@RequestParam(defaultValue = "") Set<String> mcp) {
        return kit.files(mcp);
    }

    @GetMapping(value = "/api/claude-kit.zip", produces = "application/zip")
    ResponseEntity<byte[]> zip(@RequestParam(defaultValue = "") Set<String> mcp) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("kuador-claude-kit.zip").build().toString())
                .body(kit.zip(mcp));
    }
}
