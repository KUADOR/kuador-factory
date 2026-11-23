package ec.kuador.board.connections.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.connections.application.ConnectionService;
import ec.kuador.board.connections.domain.ConnectionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    record MailRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 254) String email, @Size(max = 500) String password,
            @NotBlank @Size(max = 253) String imapHost, int imapPort, @NotBlank @Size(max = 253) String smtpHost, int smtpPort, Boolean smtpSsl) {}

    /** Un buzón por IMAP y SMTP (Hostinger, cPanel…). {@code password} vacía al editar: se conserva la guardada. */
    @PutMapping("/api/connections/{key}/mail")
    ConnectionService.View saveMail(@PathVariable String key, @Valid @RequestBody MailRequest r) {
        String password = r.password() == null || r.password().isEmpty() ? service.savedMailPassword(key) : r.password();
        return service.saveMail(key, r.name(), new ec.kuador.board.connections.domain.MailAccount(r.email(), password, r.imapHost(), r.imapPort(), r.smtpHost(),
                r.smtpPort(), r.smtpSsl()));
    }

    /** Con qué conector se conecta un correo, según dónde está alojado su dominio. */
    @GetMapping("/api/connection-directory/detect")
    ec.kuador.board.connections.application.MailDetector.Detected detect(@org.springframework.web.bind.annotation.RequestParam String email) {
        return service.detectMail(email);
    }

    /** Los sistemas conocidos que se conectan eligiéndolos de una lista. */
    @GetMapping("/api/connection-directory")
    List<ec.kuador.board.connections.application.ConnectorDirectory.Entry> directory() {
        return service.directory();
    }

    @GetMapping("/api/connections")
    List<ConnectionService.View> list() {
        return service.list();
    }

    @PutMapping("/api/connections/{key}")
    ConnectionService.View save(@PathVariable String key, @Valid @RequestBody SaveRequest r) {
        return service.save(key, r.name(), r.url(), r.header(), r.secret());
    }

    /** {@code clientId} vacío: el tablero se registra solo en el servidor. {@code scope} vacío: los permisos que sugiere el servidor. */
    record LoginRequest(@Size(max = 80) String name, @Size(max = 500) String url, @Size(max = 300) String clientId,
            @Size(max = 1000) String clientSecret, @Size(max = 1000) String scope, @Size(max = 40) String connector) {}

    record LoginStarted(String authorizationUrl) {}

    record LoginCallback(@NotBlank @Size(max = 500) String state, @NotBlank @Size(max = 4000) String code) {}

    /** Conexión que pide iniciar sesión (OAuth): devuelve la dirección a la que debe ir el navegador. */
    @PostMapping("/api/connections/{key}/login")
    LoginStarted startLogin(@PathVariable String key, @Valid @RequestBody LoginRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return new LoginStarted(service.startLogin(key, r.name(), r.url(), r.clientId(), r.clientSecret(), r.scope(), r.connector(), who.memberId()));
    }

    /** El sistema devolvió a la persona con su código: se cierra el inicio de sesión. */
    @PostMapping("/api/connection-logins")
    ConnectionService.View finishLogin(@Valid @RequestBody LoginCallback r, @AuthenticationPrincipal AuthenticatedMember who) {
        return service.finishLogin(r.state(), r.code(), who.memberId());
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
