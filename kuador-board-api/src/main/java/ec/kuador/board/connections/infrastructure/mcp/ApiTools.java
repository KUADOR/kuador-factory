package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.ApiAccount;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient.McpException;
import ec.kuador.board.connections.domain.McpClient.UnauthorizedException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.net.SafeUrls;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Un sistema conectado por su API (OpenAPI/Swagger): cada operación elegida es una herramienta del agente. Arma el
 * pedido con los argumentos (ruta, consulta, cabeceras y cuerpo), agrega la credencial y devuelve la respuesta como
 * texto (o el texto del archivo, si la API devuelve un PDF). Solo hacia Internet, sin seguir redirecciones y con tope de
 * tamaño.
 */
@Component
class ApiTools implements BuiltInConnector {

    private static final int MAX_RESPONSE = 10 * 1024 * 1024;
    private static final Set<String> RESERVED = Set.of("host", "content-length", "content-type", "accept", "cookie", "connection", "transfer-encoding");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final boolean allowPrivate;

    ApiTools(@Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    static ApiAccount account(String secret) {
        if (secret == null || secret.isBlank()) throw new McpException("Falta la configuración de la API: vuelve a conectarla.");
        try {
            return NativeConnector.JSON.readValue(secret, ApiAccount.class);
        } catch (JacksonException | DomainException e) {
            throw new McpException("La configuración guardada no es de una API: vuelve a conectarla.");
        }
    }

    @Override
    public boolean handles(String url) {
        return url != null && url.startsWith(ApiAccount.SCHEME + "http");
    }

    @Override
    public List<Connection.ToolDef> tools(String secret) {
        ApiAccount a = account(secret);
        safe(a.baseUrl());
        return a.operations().stream().map(o -> new Connection.ToolDef(o.name(), o.description(), o.schema(), o.readOnly())).toList();
    }

    @Override
    public String call(String secret, String tool, Map<String, Object> arguments) {
        ApiAccount a = account(secret);
        ApiAccount.Operation op = a.operations().stream().filter(o -> o.name().equals(tool)).findFirst()
                .orElseThrow(() -> new McpException("La API no tiene la operación «" + tool + "»."));
        Map<String, Object> args = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);

        String path = op.path();
        List<String> query = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        for (ApiAccount.Param p : op.params()) {
            Object value = args.remove(p.name());
            if (value == null || String.valueOf(value).isBlank()) {
                if (p.required()) throw new McpException("Falta «" + p.name() + "».");
                continue;
            }
            switch (p.in()) {
                case "path" -> path = path.replace("{" + p.name() + "}", segment(String.valueOf(value)));
                case "query" -> {
                    for (Object v : value instanceof List<?> list ? list : List.of(value)) query.add(enc(p.name()) + "=" + enc(String.valueOf(v)));
                }
                case "header" -> {
                    String h = String.valueOf(value);
                    if (!RESERVED.contains(p.name().toLowerCase(Locale.ROOT)) && !h.contains("\r") && !h.contains("\n")) headers.put(p.name(), h);
                }
                default -> {
                    // No pasa: el lector solo deja path, query y header.
                }
            }
        }
        if (path.contains("{")) throw new McpException("Faltan datos para armar la ruta " + op.path() + ".");
        String body = null;
        if (op.body()) {
            Object b = args.containsKey("body") ? args.get("body") : args.isEmpty() ? null : args;
            if (b instanceof String s) {
                body = s;
            } else if (b != null) {
                body = NativeConnector.JSON.writeValueAsString(b);
            }
        }
        URI uri = safe(a.baseUrl() + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json, */*;q=0.5");
        headers.forEach(req::header);
        if (a.credential() != null && !a.credential().isBlank()) req.header(a.header(), a.prefix() + a.credential());
        if (body != null) req.header("Content-Type", "application/json");
        req.method(op.method(), body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<InputStream> res;
        byte[] bytes;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                bytes = in.readNBytes(MAX_RESPONSE + 1);
            }
        } catch (IOException e) {
            throw new McpException("No se pudo hablar con la API: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Se interrumpió la llamada a la API.");
        }
        if (bytes.length > MAX_RESPONSE) throw new McpException("La respuesta de la API es demasiado grande: pide menos datos o filtra.");
        String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        int status = res.statusCode();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (status == 401) throw new UnauthorizedException("La API rechazó la credencial (401).");
        if (status == 403) throw new McpException("La API no permite esa operación con esta credencial (403).");
        if (status / 100 == 3) throw new McpException("La API redirige a otra dirección: revisa la dirección base.");
        if (status / 100 != 2) throw new McpException("La API respondió " + status + (text.isBlank() ? "." : ": " + NativeConnector.clip(text.strip())));
        if (!type.contains("json") && !type.startsWith("text/") && bytes.length > 0) {
            return ToolFiles.read(res.headers().firstValue("Content-Disposition").map(ApiTools::fileName).orElse(op.name()), type, bytes);
        }
        return text.isBlank() ? "Listo (" + status + ", sin contenido)." : text;
    }

    private URI safe(String url) {
        try {
            return SafeUrls.requirePublicHttps(url, allowPrivate);
        } catch (DomainException e) {
            throw new McpException(e.getMessage());
        }
    }

    private static String fileName(String disposition) {
        int i = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
        return i < 0 ? "archivo" : disposition.substring(i + 9).replace("\"", "").replaceAll(";.*$", "").strip();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Un valor dentro de la ruta: sin barras ni nada que cambie a qué recurso se llama. */
    private static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
