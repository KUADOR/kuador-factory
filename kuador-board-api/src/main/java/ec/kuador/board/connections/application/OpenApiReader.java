package ec.kuador.board.connections.application;

import ec.kuador.board.connections.domain.ApiAccount;
import ec.kuador.board.shared.error.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Lee la documentación de una API (OpenAPI 3 o Swagger 2, en JSON o YAML) y la convierte en operaciones que un agente
 * puede usar: nombre, qué hace, qué parámetros lleva y si solo consulta. Las referencias ($ref) se resuelven hasta cierta
 * profundidad para que el esquema que ve el modelo sea completo pero no infinito.
 */
public final class OpenApiReader {

    /** Lo que se le muestra a quien conecta: de dónde sale la dirección base y todas las operaciones para elegir. */
    public record Spec(String title, String baseUrl, List<ApiAccount.Operation> operations) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head");
    private static final int MAX_DEPTH = 6;
    private static final int MAX_DESCRIPTION = 600;

    private OpenApiReader() {}

    public static Spec read(String text, String specUrl) {
        JsonNode root = parse(text);
        boolean swagger = root.path("swagger").asString("").startsWith("2");
        if (!swagger && !root.path("openapi").asString("").startsWith("3")) {
            throw new DomainException("No parece una documentación OpenAPI o Swagger: falta «openapi: 3…» o «swagger: 2…».");
        }
        String base = swagger ? swaggerBase(root, specUrl) : openApiBase(root, specUrl);
        List<ApiAccount.Operation> ops = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        JsonNode paths = root.path("paths");
        for (Map.Entry<String, JsonNode> p : paths.properties()) {
            String path = p.getKey();
            JsonNode item = p.getValue();
            List<JsonNode> shared = new ArrayList<>();
            item.path("parameters").forEach(shared::add);
            for (Map.Entry<String, JsonNode> m : item.properties()) {
                String method = m.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) continue;
                JsonNode op = m.getValue();
                List<JsonNode> params = new ArrayList<>(shared);
                op.path("parameters").forEach(params::add);
                ops.add(operation(root, swagger, method, path, op, params, names));
                if (ops.size() >= 500) break;
            }
        }
        if (ops.isEmpty()) throw new DomainException("La documentación no tiene operaciones.");
        return new Spec(root.path("info").path("title").asString("API"), base, ops);
    }

    private static ApiAccount.Operation operation(JsonNode root, boolean swagger, String method, String path, JsonNode op, List<JsonNode> rawParams,
            Set<String> names) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<ApiAccount.Param> params = new ArrayList<>();
        boolean body = false;
        for (JsonNode raw : rawParams) {
            JsonNode p = resolve(root, raw, 0);
            String in = p.path("in").asString("");
            String name = p.path("name").asString("");
            if (name.isBlank()) continue;
            if (in.equals("body") && swagger) {
                body = true;
                properties.put("body", schema(root, p.path("schema"), 0));
                if (p.path("required").asBoolean(false)) required.add("body");
                continue;
            }
            if (!in.equals("path") && !in.equals("query") && !in.equals("header")) continue;
            if (in.equals("header") && name.equalsIgnoreCase("authorization")) continue;
            boolean req = in.equals("path") || p.path("required").asBoolean(false);
            params.add(new ApiAccount.Param(name, in, req));
            Map<String, Object> s = swagger ? swaggerParamSchema(p) : schema(root, p.path("schema"), 0);
            String d = p.path("description").asString("");
            if (!d.isBlank()) s.put("description", clip(d, 300));
            properties.put(name, s);
            if (req) required.add(name);
        }
        if (!swagger && op.has("requestBody")) {
            JsonNode rb = resolve(root, op.path("requestBody"), 0);
            JsonNode content = rb.path("content");
            JsonNode media = content.has("application/json") ? content.path("application/json") : content.properties().stream().findFirst().map(Map.Entry::getValue).orElse(null);
            if (media != null) {
                body = true;
                Map<String, Object> s = schema(root, media.path("schema"), 0);
                s.putIfAbsent("description", "El cuerpo del pedido, como objeto JSON.");
                properties.put("body", s);
                if (rb.path("required").asBoolean(false)) required.add("body");
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        String summary = op.path("summary").asString("").strip();
        String description = op.path("description").asString("").strip();
        String text = (summary.isEmpty() ? method.toUpperCase(Locale.ROOT) + " " + path : summary)
                + (description.isEmpty() || description.equals(summary) ? "" : ". " + description);
        String name = uniqueName(op.path("operationId").asString(""), method, path, names);
        boolean readOnly = method.equals("get") || method.equals("head");
        return new ApiAccount.Operation(name, method.toUpperCase(Locale.ROOT), path, clip(text, MAX_DESCRIPTION) + " (" + method.toUpperCase(Locale.ROOT) + " " + path + ")",
                params, body, schema, readOnly);
    }

    /** Un esquema JSON con sus referencias resueltas (hasta cierta profundidad) y sin lo que el modelo no necesita. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(JsonNode root, JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull()) return new LinkedHashMap<>(Map.of("type", "string"));
        JsonNode s = resolve(root, node, depth);
        if (depth >= MAX_DEPTH) return new LinkedHashMap<>(Map.of("type", s.path("type").asString("object")));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : s.properties()) {
            String k = e.getKey();
            JsonNode v = e.getValue();
            switch (k) {
                case "type", "format", "enum", "description", "required", "minimum", "maximum", "maxLength", "minLength", "pattern", "example", "default", "nullable" ->
                        out.put(k, k.equals("description") ? clip(v.asString(""), 300) : JSON.convertValue(v, Object.class));
                case "items" -> out.put("items", schema(root, v, depth + 1));
                case "properties" -> {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonNode> p : v.properties()) {
                        if (props.size() >= 60) break;
                        props.put(p.getKey(), schema(root, p.getValue(), depth + 1));
                    }
                    out.put("properties", props);
                }
                case "allOf", "oneOf", "anyOf" -> {
                    List<Object> list = new ArrayList<>();
                    for (JsonNode x : v) list.add(schema(root, x, depth + 1));
                    if (k.equals("allOf")) {
                        // Se aplanan: el modelo entiende mejor un solo objeto con todas las propiedades.
                        Map<String, Object> props = new LinkedHashMap<>();
                        for (Object x : list) {
                            Object p = ((Map<String, Object>) x).get("properties");
                            if (p instanceof Map<?, ?> m) props.putAll((Map<String, Object>) m);
                        }
                        out.put("type", "object");
                        out.put("properties", props);
                    } else {
                        out.put(k, list);
                    }
                }
                default -> {
                    // additionalProperties, xml, discriminator…: no le sirven al modelo.
                }
            }
        }
        if (out.get("nullable") != null) out.remove("nullable");
        if (!out.containsKey("type") && out.containsKey("properties")) out.put("type", "object");
        return out;
    }

    private static Map<String, Object> swaggerParamSchema(JsonNode p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", p.path("type").asString("string"));
        if (p.has("enum")) s.put("enum", JSON.convertValue(p.path("enum"), Object.class));
        if (p.has("format")) s.put("format", p.path("format").asString());
        return s;
    }

    private static JsonNode resolve(JsonNode root, JsonNode node, int depth) {
        JsonNode current = node;
        for (int i = 0; i < 10 && current.has("$ref"); i++) {
            String ref = current.path("$ref").asString("");
            if (!ref.startsWith("#/")) return current;
            JsonNode target = root;
            for (String part : ref.substring(2).split("/")) target = target.path(part.replace("~1", "/").replace("~0", "~"));
            if (target.isMissingNode()) return current;
            current = target;
        }
        return current;
    }

    private static String openApiBase(JsonNode root, String specUrl) {
        String server = root.path("servers").path(0).path("url").asString("");
        if (server.startsWith("http://") || server.startsWith("https://")) return server.replaceAll("/+$", "");
        String origin = origin(specUrl);
        if (origin == null) return server.isBlank() ? "" : server;
        return (origin + (server.startsWith("/") ? server : "")).replaceAll("/+$", "");
    }

    private static String swaggerBase(JsonNode root, String specUrl) {
        String host = root.path("host").asString("");
        String basePath = root.path("basePath").asString("");
        String scheme = root.path("schemes").path(0).asString("https");
        if (!host.isBlank()) return (scheme + "://" + host + basePath).replaceAll("/+$", "");
        String origin = origin(specUrl);
        return origin == null ? "" : (origin + basePath).replaceAll("/+$", "");
    }

    private static String origin(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(url.strip());
            return u.getScheme() == null || u.getRawAuthority() == null ? null : u.getScheme() + "://" + u.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String uniqueName(String operationId, String method, String path, Set<String> taken) {
        String raw = operationId.isBlank() ? method + "_" + path : operationId;
        String base = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "op_" + base;
        if (base.length() > 50) base = base.substring(0, 50).replaceAll("_+$", "");
        String name = base;
        for (int n = 2; !taken.add(name); n++) name = base + "_" + n;
        return name;
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) throw new DomainException("La documentación está vacía.");
        String t = text.strip();
        try {
            return t.startsWith("{") ? JSON.readTree(t) : YAML.readTree(t);
        } catch (JacksonException e) {
            throw new DomainException("No se pudo leer la documentación (JSON o YAML): " + clip(e.getOriginalMessage(), 200));
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
