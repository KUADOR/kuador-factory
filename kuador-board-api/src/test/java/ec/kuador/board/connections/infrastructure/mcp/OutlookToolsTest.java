package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** El conector de Outlook contra un Microsoft Graph de mentira: mismas rutas y mismas formas de respuesta. */
class OutlookToolsTest {

    private HttpServer graph;
    private HttpMcpClient client;
    private String url;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        String invoice = Base64.getEncoder().encodeToString(ToolFilesTest.pdf("Factura 001-001-000012345", "Pedido 4471", "Total: 1840.50 USD"));
        graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/v1.0", x -> {
            String path = x.getRequestURI().getRawPath();
            String query = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(x.getRequestMethod() + " " + path + " ? " + query + " | " + body);
            int status = 200;
            String reply = "{}";
            if (!"Bearer acceso".equals(x.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                reply = "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"Access token has expired.\"}}";
            } else if (path.equals("/v1.0/me")) {
                reply = "{\"id\":\"u1\"}";
            } else if (path.equals("/v1.0/me/mailFolders/inbox/messages")) {
                reply = "{\"value\":[{\"id\":\"AAMk=1\",\"subject\":\"Factura pedido 4471\",\"from\":{\"emailAddress\":{\"name\":\"Cartones del Sur\",\"address\":\"ventas@cartones.example\"}},"
                        + "\"receivedDateTime\":\"2026-11-23T10:00:00Z\",\"isRead\":false,\"hasAttachments\":true,\"bodyPreview\":\"Adjunto la factura\"}]}";
            } else if (path.equals("/v1.0/me/messages/AAMk%3D1/attachments")) {
                reply = "{\"value\":[{\"id\":\"ADJ1\",\"name\":\"factura.pdf\",\"contentType\":\"application/pdf\",\"size\":20480,\"isInline\":false},"
                        + "{\"id\":\"ADJ2\",\"name\":\"logo.png\",\"contentType\":\"image/png\",\"size\":900,\"isInline\":true}]}";
            } else if (path.equals("/v1.0/me/messages/AAMk%3D1/attachments/ADJ1")) {
                reply = "{\"@odata.type\":\"#microsoft.graph.fileAttachment\",\"id\":\"ADJ1\",\"name\":\"factura.pdf\",\"contentType\":\"application/pdf\",\"contentBytes\":\"" + invoice + "\"}";
            } else if (path.equals("/v1.0/me/messages/AAMk%3D1") && x.getRequestMethod().equals("GET")) {
                reply = "{\"subject\":\"Factura pedido 4471\",\"from\":{\"emailAddress\":{\"name\":\"Cartones del Sur\",\"address\":\"ventas@cartones.example\"}},"
                        + "\"toRecipients\":[{\"emailAddress\":{\"name\":\"compras@flores.example\",\"address\":\"compras@flores.example\"}}],\"ccRecipients\":[],"
                        + "\"receivedDateTime\":\"2026-11-23T10:00:00Z\",\"body\":{\"contentType\":\"text\",\"content\":\"Estimados, adjunto la factura del pedido 4471.\"},"
                        + "\"hasAttachments\":true,\"isRead\":false}";
            } else if (path.equals("/v1.0/me/sendMail") || path.endsWith("/reply")) {
                status = 202;
                reply = "";
            } else if (path.equals("/v1.0/me/messages/no-existe")) {
                status = 404;
                reply = "{\"error\":{\"code\":\"ErrorItemNotFound\",\"message\":\"The specified object was not found in the store.\"}}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) x.getResponseBody().write(out);
            x.close();
        });
        graph.start();
        url = "http://127.0.0.1:" + graph.getAddress().getPort() + "/v1.0";
        client = new HttpMcpClient(true, List.of(new OutlookTools(url)));
    }

    @AfterEach
    void stop() {
        graph.stop(0);
    }

    @Test
    void offersMailToolsAndOnlyReadingOnesAreOpenByDefault() {
        List<Connection.ToolDef> tools = client.listTools(url, null, "acceso");

        assertThat(tools).filteredOn(Connection.ToolDef::readOnly).extracting(Connection.ToolDef::name).containsExactly("buscar_correos", "leer_correo", "leer_adjunto");
        assertThat(tools).filteredOn(t -> !t.readOnly()).extracting(Connection.ToolDef::name).containsExactly("marcar_leido", "responder_correo", "enviar_correo");
        assertThatThrownBy(() -> client.listTools(url, null, "vencido")).as("una sesión vencida se avisa para que el tablero la renueve")
                .isInstanceOf(McpClient.UnauthorizedException.class);
    }

    @Test
    void findsTheInvoiceMailAndReadsItsPdf() {
        String found = client.callTool(url, null, "acceso", "buscar_correos", Map.of("solo_no_leidos", true, "con_adjuntos", true, "desde", "2026-11-20"));
        assertThat(found).contains("\"id\":\"AAMk=1\"", "Cartones del Sur <ventas@cartones.example>", "\"adjuntos\":true");
        assertThat(seen.getLast()).as("el campo del orden va primero en el filtro, como exige Graph")
                .contains("$orderby=receivedDateTime desc", "$filter=receivedDateTime ge 2026-11-20T00:00:00Z and isRead eq false and hasAttachments eq true");

        String mail = client.callTool(url, null, "acceso", "leer_correo", Map.of("id", "AAMk=1"));
        assertThat(mail).contains("De: Cartones del Sur <ventas@cartones.example>", "Asunto: Factura pedido 4471", "adjunto la factura del pedido 4471",
                "- factura.pdf (application/pdf, 20 KB) id: ADJ1").as("las imágenes de la firma no son adjuntos").doesNotContain("logo.png");

        assertThat(client.callTool(url, null, "acceso", "leer_adjunto", Map.of("correo", "AAMk=1", "adjunto", "ADJ1")))
                .startsWith("[Archivo «factura.pdf» (PDF, 1 página)]").contains("Pedido 4471", "Total: 1840.50 USD");

        client.callTool(url, null, "acceso", "buscar_correos", Map.of("texto", "factura 4471"));
        assertThat(seen.getLast()).contains("$search=\"factura 4471\"").doesNotContain("$filter", "$orderby");
    }

    @Test
    void writesOnlyWhatItIsAskedTo() {
        assertThat(client.callTool(url, null, "acceso", "marcar_leido", Map.of("id", "AAMk=1"))).isEqualTo("Marcado como leído.");
        assertThat(seen.getLast()).startsWith("PATCH /v1.0/me/messages/AAMk%3D1").contains("{\"isRead\":true}");

        assertThat(client.callTool(url, null, "acceso", "responder_correo", Map.of("id", "AAMk=1", "cuerpo", "Recibida, gracias."))).isEqualTo("Respuesta enviada.");
        assertThat(seen.getLast()).startsWith("POST /v1.0/me/messages/AAMk%3D1/reply").contains("\"comment\":\"Recibida, gracias.\"");

        assertThat(client.callTool(url, null, "acceso", "enviar_correo", Map.of("para", List.of("ana@flores.example"), "asunto", "Precio distinto", "cuerpo", "Revisar.")))
                .isEqualTo("Correo enviado a 1 destinatario.");
        assertThat(seen.getLast()).startsWith("POST /v1.0/me/sendMail").contains("\"address\":\"ana@flores.example\"", "\"contentType\":\"Text\"");

        assertThatThrownBy(() -> client.callTool(url, null, "acceso", "leer_correo", Map.of("id", "no-existe"))).isInstanceOf(McpClient.McpException.class)
                .hasMessageContaining("no encontró");
        assertThatThrownBy(() -> client.callTool(url, null, "acceso", "enviar_correo", Map.of("asunto", "x", "cuerpo", "y"))).hasMessageContaining("para");
        assertThatThrownBy(() -> client.callTool(url, null, "acceso", "buscar_correos", Map.of("carpeta", "inbox/../../users"))).hasMessageContaining("Carpeta no válida");
    }
}
