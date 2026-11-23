package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.net.SafeUrls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente MCP por HTTP (transporte «streamable HTTP»): abre la sesión, lista las herramientas o llama a una y termina.
 * Solo llama a servidores públicos por HTTPS (ver {@link SafeUrls}), no sigue redirecciones y recorta respuestas enormes.
 */
@Component
class HttpMcpClient implements McpClient {

    private static final String PROTOCOL = "2025-06-18";
    private static final int MAX_BODY = 2_000_000;
    private static final int MAX_TOOLS = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final boolean allowPrivate;

    HttpMcpClient(@Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    @Override
    public List<Connection.ToolDef> listTools(String url, String header, String secret) {
        Session session = open(url, header, secret);
        List<Connection.ToolDef> tools = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = session.request("tools/list", params);
            for (JsonNode t : result.path("tools")) {
                String name = t.path("name").asString("");
                if (name.isBlank() || name.length() > 120) continue;
                Map<String, Object> schema = t.has("inputSchema") && t.get("inputSchema").isObject() ? JSON.convertValue(t.get("inputSchema"), MAP)
                        : Map.of("type", "object", "properties", Map.of());
                boolean readOnly = t.path("annotations").path("readOnlyHint").asBoolean(false);
                tools.add(new Connection.ToolDef(name, clip(t.path("description").asString(""), 1500), schema, readOnly));
                if (tools.size() >= MAX_TOOLS) return tools;
            }
            cursor = result.path("nextCursor").isString() ? result.path("nextCursor").asString() : null;
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    @Override
    public String callTool(String url, String header, String secret, String tool, Map<String, Object> arguments) {
        Session session = open(url, header, secret);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        JsonNode result = session.request("tools/call", params);
        StringBuilder text = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asString(""))) text.append(c.path("text").asString("")).append('\n');
            else text.append("[contenido de tipo ").append(c.path("type").asString("desconocido")).append(", no se muestra]\n");
        }
        if (text.isEmpty() && result.has("structuredContent")) text.append(result.get("structuredContent").toString());
        String out = text.toString().strip();
        if (result.path("isError").asBoolean(false)) throw new McpException(out.isBlank() ? "La herramienta respondió con un error." : clip(out, 2000));
        return out.isBlank() ? "(sin contenido)" : out;
    }

    private Session open(String url, String header, String secret) {
        URI uri;
        try {
            uri = SafeUrls.requirePublicHttps(url, allowPrivate);
        } catch (DomainException e) {
            throw new McpException(e.getMessage());
        }
        Session session = new Session(uri, header, secret);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "board", "version", "1"));
        JsonNode init = session.request("initialize", params);
        if (init.path("protocolVersion").isString()) session.version = init.path("protocolVersion").asString();
        session.notifyInitialized();
        return session;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Una conversación con el servidor: guarda el id de sesión que devuelve y la versión acordada. */
    private final class Session {
        private final URI uri;
        private final String header;
        private final String secret;
        private String sessionId;
        private String version;
        private int nextId = 1;

        Session(URI uri, String header, String secret) {
            this.uri = uri;
            this.header = header;
            this.secret = secret;
        }

        private HttpRequest.Builder base() {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
            if (secret != null && !secret.isBlank()) {
                if (header == null) b.header("Authorization", "Bearer " + secret);
                else b.header(header, secret);
            }
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
            if (version != null) b.header("MCP-Protocol-Version", version);
            return b;
        }

        void notifyInitialized() {
            try {
                String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
                http.send(base().POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                throw new McpException("No se pudo hablar con el servidor: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("Se interrumpió la llamada al servidor.");
            }
        }

        JsonNode request(String method, Map<String, Object> params) {
            int id = nextId++;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            HttpResponse<byte[]> response;
            try {
                response = http.send(base().POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message))).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new McpException("No se pudo hablar con el servidor: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("Se interrumpió la llamada al servidor.");
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new McpException("El servidor rechazó la credencial (" + status + ").");
            if (status >= 300 && status < 400) throw new McpException("El servidor redirige a otra dirección: usa la dirección final.");
            if (status < 200 || status >= 300) throw new McpException("El servidor respondió " + status + ".");
            response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
            byte[] bytes = response.body();
            if (bytes.length > MAX_BODY) throw new McpException("La respuesta del servidor es demasiado grande.");
            String body = new String(bytes, StandardCharsets.UTF_8);
            String type = response.headers().firstValue("Content-Type").orElse("");
            JsonNode reply = type.contains("text/event-stream") ? fromEvents(body, id) : parse(body);
            if (reply == null) throw new McpException("El servidor no respondió al pedido.");
            if (reply.has("error")) throw new McpException(clip(reply.path("error").path("message").asString("El servidor respondió un error."), 2000));
            return reply.path("result");
        }

        /** En un flujo de eventos, la respuesta es el mensaje con el mismo id. */
        private JsonNode fromEvents(String body, int id) {
            StringBuilder data = new StringBuilder();
            for (String line : (body + "\n\n").split("\n", -1)) {
                String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (l.startsWith("data:")) {
                    data.append(l.substring(5).stripLeading()).append('\n');
                } else if (l.isEmpty() && !data.isEmpty()) {
                    JsonNode node = parse(data.toString());
                    data.setLength(0);
                    if (node != null && node.path("id").asInt(-1) == id && (node.has("result") || node.has("error"))) return node;
                }
            }
            return null;
        }

        private JsonNode parse(String body) {
            try {
                return JSON.readTree(body);
            } catch (JacksonException e) {
                throw new McpException("El servidor respondió algo que no es MCP.");
            }
        }
    }
}
