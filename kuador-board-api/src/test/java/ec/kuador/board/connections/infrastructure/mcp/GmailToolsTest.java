package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** El conector de Gmail contra una API de Gmail de mentira: mismas rutas y formas de respuesta, mismas herramientas que Outlook. */
class GmailToolsTest {

    private HttpServer gmail;
    private HttpMcpClient client;
    private String url;
    private final List<String> seen = new ArrayList<>();

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void start() throws Exception {
        String invoice = Base64.getUrlEncoder().encodeToString(ToolFilesTest.pdf("Factura 001-001-000012345", "Pedido 4471", "Total: 1840.50 USD"));
        String message = "{\"id\":\"m1\",\"threadId\":\"t1\",\"labelIds\":[\"INBOX\",\"UNREAD\"],\"snippet\":\"Adjunto la factura\",\"payload\":{\"mimeType\":\"multipart/mixed\","
                + "\"headers\":[{\"name\":\"From\",\"value\":\"Cartones del Sur <ventas@cartones.example>\"},{\"name\":\"To\",\"value\":\"compras@flores.example\"},"
                + "{\"name\":\"Subject\",\"value\":\"Factura pedido 4471\"},{\"name\":\"Date\",\"value\":\"Mon, 23 Nov 2026 10:00:00 -0500\"},"
                + "{\"name\":\"Message-ID\",\"value\":\"<abc@cartones.example>\"}],\"parts\":["
                + "{\"partId\":\"0\",\"mimeType\":\"multipart/alternative\",\"filename\":\"\",\"parts\":["
                + "{\"partId\":\"0.0\",\"mimeType\":\"text/plain\",\"filename\":\"\",\"body\":{\"size\":40,\"data\":\"" + b64("Estimados, adjunto la factura del pedido 4471.") + "\"}},"
                + "{\"partId\":\"0.1\",\"mimeType\":\"text/html\",\"filename\":\"\",\"body\":{\"size\":60,\"data\":\"" + b64("<p>Estimados</p>") + "\"}}]},"
                + "{\"partId\":\"1\",\"mimeType\":\"application/pdf\",\"filename\":\"factura.pdf\",\"headers\":[{\"name\":\"Content-Disposition\",\"value\":\"attachment\"}],"
                + "\"body\":{\"size\":20480,\"attachmentId\":\"ANGjdJ-cambia-cada-vez\"}},"
                + "{\"partId\":\"2\",\"mimeType\":\"image/png\",\"filename\":\"logo.png\",\"headers\":[{\"name\":\"Content-Disposition\",\"value\":\"inline\"},"
                + "{\"name\":\"Content-ID\",\"value\":\"<logo>\"}],\"body\":{\"size\":900,\"attachmentId\":\"logo-id\"}}]}}";
        gmail = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gmail.createContext("/gmail/v1", x -> {
            String path = x.getRequestURI().getRawPath();
            String query = x.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(x.getRequestMethod() + " " + path + " ? " + query + " | " + body);
            int status = 200;
            String reply = "{}";
            if (!"Bearer acceso".equals(x.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                reply = "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\"}}";
            } else if (path.equals("/gmail/v1/users/me/profile")) {
                reply = "{\"emailAddress\":\"compras@flores.example\"}";
            } else if (path.equals("/gmail/v1/users/me/messages")) {
                reply = "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"}],\"resultSizeEstimate\":1}";
            } else if (path.equals("/gmail/v1/users/me/messages/m1") && x.getRequestMethod().equals("GET")) {
                reply = message;
            } else if (path.equals("/gmail/v1/users/me/messages/m1/attachments/ANGjdJ-cambia-cada-vez")) {
                reply = "{\"size\":20480,\"data\":\"" + invoice + "\"}";
            } else if (path.equals("/gmail/v1/users/me/messages/no-existe")) {
                status = 404;
                reply = "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\"}}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(status, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        gmail.start();
        url = "http://127.0.0.1:" + gmail.getAddress().getPort() + "/gmail/v1";
        client = new HttpMcpClient(true, List.of(new OutlookTools("https://graph.microsoft.com/v1.0"), new GmailTools(url)));
    }

    @AfterEach
    void stop() {
        gmail.stop(0);
    }

    @Test
    void offersTheSameToolsAsOutlook() {
        assertThat(client.listTools(url, null, "acceso")).extracting(Connection.ToolDef::name)
                .containsExactlyElementsOf(NativeConnector.mailTools().stream().map(Connection.ToolDef::name).toList());
        assertThatThrownBy(() -> client.listTools(url, null, "vencido")).isInstanceOf(McpClient.UnauthorizedException.class);
    }

    @Test
    void findsTheInvoiceMailAndReadsItsPdf() {
        String found = client.callTool(url, null, "acceso", "buscar_correos", Map.of("solo_no_leidos", true, "con_adjuntos", true, "desde", "2026-11-20"));
        assertThat(found).contains("\"id\":\"m1\"", "Cartones del Sur <ventas@cartones.example>", "\"leido\":false", "\"adjuntos\":true");
        assertThat(seen).anyMatch(s -> s.contains("q=in:inbox after:2026/11/20 is:unread has:attachment"));

        String mail = client.callTool(url, null, "acceso", "leer_correo", Map.of("id", "m1"));
        assertThat(mail).contains("De: Cartones del Sur <ventas@cartones.example>", "Asunto: Factura pedido 4471", "adjunto la factura del pedido 4471",
                "- factura.pdf (application/pdf, 20 KB) id: 1").doesNotContain("logo.png", "<p>");

        assertThat(client.callTool(url, null, "acceso", "leer_adjunto", Map.of("correo", "m1", "adjunto", "1")))
                .startsWith("[Archivo «factura.pdf» (PDF, 1 página)]").contains("Pedido 4471", "Total: 1840.50 USD");
    }

    @Test
    void repliesInTheSameConversationAndSendsPlainText() throws Exception {
        assertThat(client.callTool(url, null, "acceso", "marcar_leido", Map.of("id", "m1"))).isEqualTo("Marcado como leído.");
        assertThat(seen.getLast()).startsWith("POST /gmail/v1/users/me/messages/m1/modify").contains("\"removeLabelIds\":[\"UNREAD\"]");

        assertThat(client.callTool(url, null, "acceso", "responder_correo", Map.of("id", "m1", "cuerpo", "Recibida, gracias. Revisamos el precio."))).isEqualTo("Respuesta enviada.");
        var sent = JsonMapper.builder().build().readTree(seen.getLast().substring(seen.getLast().indexOf("| ") + 2));
        assertThat(sent.path("threadId").asString()).isEqualTo("t1");
        String raw = new String(Base64.getUrlDecoder().decode(sent.path("raw").asString()), StandardCharsets.UTF_8);
        assertThat(raw).contains("To: ventas@cartones.example\r\n", "In-Reply-To: <abc@cartones.example>", "References: <abc@cartones.example>",
                "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Re: Factura pedido 4471".getBytes(StandardCharsets.UTF_8)) + "?=");
        String text = new String(Base64.getMimeDecoder().decode(raw.substring(raw.indexOf("\r\n\r\n") + 4)), StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("Recibida, gracias. Revisamos el precio.");

        client.callTool(url, null, "acceso", "enviar_correo", Map.of("para", List.of("ana@flores.example"), "asunto", "Precio distinto\r\nBcc: otro@malo.example", "cuerpo", "Revisar."));
        String newMail = new String(Base64.getUrlDecoder().decode(JsonMapper.builder().build().readTree(seen.getLast().substring(seen.getLast().indexOf("| ") + 2))
                .path("raw").asString()), StandardCharsets.UTF_8);
        assertThat(newMail).startsWith("To: ana@flores.example\r\n").as("un asunto no puede agregar cabeceras").doesNotContain("\r\nBcc:");

        assertThatThrownBy(() -> client.callTool(url, null, "acceso", "enviar_correo", Map.of("para", "no es correo", "asunto", "x", "cuerpo", "y")))
                .hasMessageContaining("no es una dirección");
        assertThatThrownBy(() -> client.callTool(url, null, "acceso", "leer_correo", Map.of("id", "no-existe"))).hasMessageContaining("Gmail no encontró");
    }
}
