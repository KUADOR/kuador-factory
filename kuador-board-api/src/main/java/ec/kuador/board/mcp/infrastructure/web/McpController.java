package ec.kuador.board.mcp.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.mcp.application.McpProtocol;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Punto de entrada MCP ({@code /mcp}). Solo POST: el servidor no abre flujos SSE. Rechaza las
 * peticiones de navegadores (cabecera Origin), como pide la especificación contra el «DNS rebinding».
 */
@RestController
class McpController {

    private final McpProtocol protocol;
    private final ObjectMapper json;

    McpController(McpProtocol protocol, ObjectMapper json) {
        this.protocol = protocol;
        this.json = json;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> post(@RequestBody String body, @AuthenticationPrincipal AuthenticatedMember who,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        if (origin != null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(Map.of("jsonrpc", "2.0", "error", Map.of("code", -32700, "message", "JSON inválido.")));
        }
        return protocol.handle(message, who.memberId())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    @GetMapping("/mcp")
    ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @DeleteMapping("/mcp")
    ResponseEntity<Void> endSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }
}
