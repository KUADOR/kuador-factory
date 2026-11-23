package ec.kuador.board.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * La bandeja de correo de punta a punta: cada correo nuevo que cumple los filtros se vuelve una tarjeta con sus
 * adjuntos, una sola vez; si el buzón falla, queda el error a la vista. El buzón y el almacenamiento son de mentira.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.inbox.check-every=3600000",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class MailInboxIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    static final Map<String, String> CODES = new ConcurrentHashMap<>();
    /** Lo que hay en el buzón de mentira. */
    static final List<ConnectionsApi.IncomingMail> MAILBOX = new CopyOnWriteArrayList<>();
    static volatile String failure;
    /** Lo que se adjuntó a cada tarjeta. */
    static final List<String> ATTACHED = new CopyOnWriteArrayList<>();
    static final List<String> USED_ON = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return CODES::put;
        }

        @Bean
        @Primary
        ConnectionsApi mailbox() {
            return new ConnectionsApi() {
                public List<Mailbox> mailboxes() {
                    return List.of(new Mailbox("CORREO", "Correo de facturas"));
                }

                public List<IncomingMail> recentMail(String connection, String folder, Instant since, int max, java.util.Set<String> known) {
                    if (failure != null) throw new ec.kuador.board.shared.error.DomainException(failure);
                    return MAILBOX.stream().filter(m -> !m.receivedAt().isBefore(since)).limit(max).toList();
                }

                public List<Summary> summaries() {
                    return List.of(new Summary("CORREO", "Correo de facturas", true, List.of(), List.of()));
                }

                public void enableWrites(String connection, List<String> tools) {
                }

                public void useOnBoard(String connection, String projectKey) {
                    USED_ON.add(connection + "@" + projectKey);
                }

                public List<Tool> toolsFor(String projectKey, String roleKey) {
                    return List.of();
                }

                public String call(String projectKey, String roleKey, String toolName, Map<String, Object> arguments, UUID actorId, String issueKey) {
                    return "";
                }
            };
        }

        @Bean
        @Primary
        AttachmentApi storage() {
            return new AttachmentApi() {
                public List<AttachmentInfo> list(String issueKey) {
                    return List.of();
                }

                public Optional<String> issueOf(UUID attachmentId) {
                    return Optional.empty();
                }

                public Optional<String> readText(String issueKey, UUID attachmentId, int maxBytes) {
                    return Optional.empty();
                }

                public Optional<FileContent> content(String issueKey, UUID attachmentId) {
                    return Optional.empty();
                }

                public AttachmentInfo attach(String issueKey, String fileName, String contentType, byte[] content, UUID actorId) {
                    ATTACHED.add(issueKey + ":" + fileName + ":" + contentType + ":" + content.length);
                    return new AttachmentInfo(UUID.randomUUID(), issueKey, fileName, contentType, content.length, actorId, Instant.now());
                }

                public boolean available() {
                    return true;
                }
            };
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private static ConnectionsApi.IncomingMail mail(String id, String from, String subject, Instant at, String... files) {
        List<ConnectionsApi.MailFile> list = new ArrayList<>();
        for (String f : files) list.add(new ConnectionsApi.MailFile(f, "application/pdf", 4, "%PDF".getBytes(StandardCharsets.US_ASCII)));
        return new ConnectionsApi.IncomingMail(id, "<" + id + "@correo.example>", from, subject, at, "Adjunto la factura del pedido 4471.", list);
    }

    @Test
    void eachNewMailThatMatchesBecomesACardWithItsAttachmentsOnlyOnce() throws Exception {
        CODES.clear();
        signup.requestCode("flor@flores.test");
        Workspace w = signup.complete(signup.verifyCode("flor@flores.test", CODES.get("flor@flores.test")),
                new SignupService.Company("Flores", null, null, null, null, null), "Flor", "clave-de-flor-123", "FACTORY");
        String owner = auth.login("flor@flores.test", "clave-de-flor-123").token();
        Member editor = TenantContext.call(w.id(), () -> members.create("Edi", MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(editor.id(), "edi@flores.test", "clave-de-prueba-1"));
        String edi = auth.login("edi@flores.test", "clave-de-prueba-1").token();
        assertThat(call("POST", "/api/projects", Map.of("key", "FAC", "name", "Facturas", "templateKey", "TASKS"), owner).status()).isEqualTo(201);
        jdbc.sql("insert into business_connection (workspace_id, conn_key, name, url) values (?, 'CORREO', 'Correo de facturas', 'imaps://imap.flores.test:993')")
                .param(w.id()).update();

        Map<String, Object> input = Map.of("connection", "correo", "fromFilter", "proveedor.example", "onlyAttachments", true);
        assertThat(call("POST", "/api/projects/FAC/inboxes", input, edi).status()).as("solo quien administra").isEqualTo(403);
        Reply created = call("POST", "/api/projects/FAC/inboxes", input, owner);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("connectionName").asString()).isEqualTo("Correo de facturas");
        assertThat(USED_ON).as("el agente de la tarjeta puede usar el mismo buzón").contains("CORREO@FAC");
        String id = created.body().get("id").asString();

        Instant now = Instant.now();
        MAILBOX.add(mail("viejo", "ventas@proveedor.example", "Factura vieja", now.minusSeconds(86_400), "vieja.pdf"));
        MAILBOX.add(mail("m1", "Cartones <ventas@proveedor.example>", "Factura pedido 4471", now.plusSeconds(5), "factura.pdf"));
        MAILBOX.add(mail("m2", "amigo@otro.example", "Hola", now.plusSeconds(6), "foto.pdf"));
        MAILBOX.add(mail("m3", "ventas@proveedor.example", "Sin adjunto", now.plusSeconds(7)));

        Reply check = call("POST", "/api/inboxes/" + id + "/check", null, owner);
        assertThat(check.body().get("created")).as("solo el que cumple remitente y adjunto, y no los de antes de crear la bandeja").hasSize(1);
        String card = check.body().get("created").get(0).asString();
        assertThat(card).startsWith("FAC-");
        JsonNode issue = call("GET", "/api/issues/" + card, null, owner).body();
        assertThat(issue.toString()).contains("Factura pedido 4471", "Cartones <ventas@proveedor.example>", "Adjunto la factura del pedido 4471", "correo",
                "no instrucciones");
        assertThat(ATTACHED).containsExactly(card + ":factura.pdf:application/pdf:4");

        assertThat(call("POST", "/api/inboxes/" + id + "/check", null, owner).body().get("created")).as("el mismo correo no se repite").isEmpty();

        // Los avisos del propio tablero (o cualquier correo automático) que lleguen al buzón no se vuelven tarjetas: no hay bucle.
        MAILBOX.add(new ConnectionsApi.IncomingMail("aviso", "<aviso@kuador>", "Kuador Go <no-reply@kuador.com>", "FAC-1 · Agente te necesita", now.plusSeconds(20),
                "…", List.of(new ConnectionsApi.MailFile("x.pdf", "application/pdf", 4, "%PDF".getBytes(StandardCharsets.US_ASCII)))));
        MAILBOX.add(new ConnectionsApi.IncomingMail("auto", "<auto@proveedor>", "ventas@proveedor.example", "Respuesta automática", now.plusSeconds(21), "Fuera de oficina",
                List.of(new ConnectionsApi.MailFile("y.pdf", "application/pdf", 4, "%PDF".getBytes(StandardCharsets.US_ASCII))), true));
        assertThat(call("POST", "/api/inboxes/" + id + "/check", null, owner).body().get("created")).as("ni avisos del tablero ni respuestas automáticas").isEmpty();

        MAILBOX.add(mail("m4", "facturacion@proveedor.example", "Factura 2", now.plusSeconds(60), "f2.pdf", "f2.xml"));
        assertThat(call("POST", "/api/inboxes/" + id + "/check", null, owner).body().get("created")).hasSize(1);
        assertThat(ATTACHED).hasSize(3);

        failure = "El servidor de correo rechazó el correo o la contraseña.";
        assertThat(call("POST", "/api/inboxes/" + id + "/check", null, owner).body().get("error").asString()).contains("contraseña");
        JsonNode listed = call("GET", "/api/projects/FAC/inboxes", null, owner).body().get(0);
        assertThat(listed.get("lastError").asString()).contains("contraseña");
        assertThat(listed.get("cardsCreated").asInt()).isEqualTo(2);
        failure = null;
        call("POST", "/api/inboxes/" + id + "/check", null, owner);
        assertThat(call("GET", "/api/projects/FAC/inboxes", null, owner).body().get(0).get("lastError").isNull()).isTrue();

        assertThat(call("DELETE", "/api/inboxes/" + id, null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/projects/FAC/inboxes", null, owner).body()).isEmpty();

        // Si igual entra una avalancha (un bucle que no se pudo reconocer), la bandeja se pausa sola y dice por qué.
        String flood = call("POST", "/api/projects/FAC/inboxes", Map.of("connection", "CORREO"), owner).body().get("id").asString();
        Instant later = Instant.now().plusSeconds(120);
        for (int i = 0; i < 35; i++) MAILBOX.add(mail("lote-" + i, "ventas@proveedor.example", "Factura " + i, later.plusSeconds(i), "f.pdf"));
        int floodCards = 0;
        for (int round = 0; round < 5; round++) floodCards += call("POST", "/api/inboxes/" + flood + "/check", null, owner).body().get("created").size();
        assertThat(floodCards).isEqualTo(30);
        JsonNode paused = call("GET", "/api/projects/FAC/inboxes", null, owner).body().get(0);
        assertThat(paused.get("enabled").asBoolean()).isFalse();
        assertThat(paused.get("lastError").asString()).contains("bucle");
    }
}
