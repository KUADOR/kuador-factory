package ec.kuador.board.mcp.application;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.workspace.WorkspaceApi;
import ec.kuador.board.workspace.WorkspaceApi.Identity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Kit para conectar un repositorio con el tablero desde el cliente de IA de cada persona. El servidor
 * MCP es estándar, así que sirve cualquier cliente con MCP; el kit trae los archivos en el formato de
 * cada uno. Todo lleva el nombre del espacio de trabajo (la empresa), no el del producto, y ningún
 * secreto: el token sale de una variable de entorno de cada persona.
 */
@Service
public class AgentKit implements ec.kuador.board.mcp.AgentInstructionsApi {

    /** Clientes con kit propio. {@code GENERIC}: cualquier otro cliente con MCP (Cursor, OpenCode, Cline...). */
    public enum Client {
        CLAUDE_CODE("claude-code", "Claude Code"),
        CODEX("codex", "Codex"),
        GENERIC("mcp", "Otro cliente MCP");

        public final String id;
        public final String label;

        Client(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Client of(String id) {
            return Arrays.stream(values()).filter(c -> c.id.equalsIgnoreCase(id == null ? "" : id.strip())).findFirst().orElse(CLAUDE_CODE);
        }
    }

    /** Herramientas del tablero que usa quien trabaja un issue (la cola la consulta el comando o la sesión principal). */
    private static final List<String> WORK_TOOLS = List.of("ver_issue", "instrucciones_rol", "tomar", "comentar", "estimar", "entregar", "devolver",
            "consultar", "leer_documento", "escribir_documento");

    public record KitFile(String path, String content) {}

    /** Cómo se conecta este espacio: nombre del servidor MCP, variables de entorno y URL. */
    public record Connection(String server, String tokenVar, String urlVar, String url) {}

    private final CatalogApi catalog;
    private final WorkspaceApi workspace;
    private final ObjectMapper json;
    private final String publicUrl;

    AgentKit(CatalogApi catalog, WorkspaceApi workspace, ObjectMapper json, @Value("${board.public-url:http://localhost:8091}") String publicUrl) {
        this.catalog = catalog;
        this.workspace = workspace;
        this.json = json;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    /** El servidor se llama como el espacio (p. ej. «adma») y su token va en TABLERO_ADMA_TOKEN: un repo por empresa, sin choques. */
    public Connection connection() {
        String slug = workspace.identity().slug();
        return new Connection(slug, "TABLERO_" + slug.toUpperCase(Locale.ROOT).replace('-', '_') + "_TOKEN", "TABLERO_URL", publicUrl + "/mcp");
    }

    /** Nombre del subagente de un rol: {@code adma-backend} para BACKEND en el espacio «adma». */
    public static String subagentName(String slug, String roleKey) {
        return slug + "-" + roleKey.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String subagentName(String roleKey) {
        return subagentName(workspace.identity().slug(), roleKey);
    }

    @Override
    public String agentsMd() {
        return agentsMd(workspace.identity(), connection());
    }

    public List<KitFile> files(Client client, Collection<String> mcpKeys) {
        Identity ws = workspace.identity();
        Connection c = connection();
        List<McpServer> extra = catalog.mcpServers().stream().filter(s -> mcpKeys.contains(s.key())).toList();
        List<KitFile> files = new ArrayList<>();
        switch (client) {
            case CLAUDE_CODE -> {
                files.add(new KitFile(".mcp.json", mcpJson(c, extra, true)));
                files.add(new KitFile(".claude/commands/pendientes.md", claudeCommand(ws, c)));
                catalog.roles().forEach(r -> files.add(new KitFile(".claude/agents/" + subagentName(ws.slug(), r.key()) + ".md", claudeSubagent(ws, c, r))));
            }
            case CODEX -> {
                files.add(new KitFile(".codex/config.toml", codexToml(c, extra)));
                files.add(new KitFile("AGENTS.md", agentsMd(ws, c)));
            }
            case GENERIC -> {
                files.add(new KitFile("mcp-servidores.json", mcpJson(c, extra, false)));
                files.add(new KitFile("AGENTS.md", agentsMd(ws, c)));
            }
        }
        return files;
    }

    public byte[] zip(Client client, Collection<String> mcpKeys) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (KitFile f : files(client, mcpKeys)) {
                zip.putNextEntry(new ZipEntry(f.path()));
                zip.write(f.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---- Configuración del servidor MCP ----

    /**
     * Formato {@code mcpServers} (Claude Code, y la base de Cursor y otros). Con {@code expand}, URL y
     * token usan ${VARIABLE} (Claude Code las expande); sin él, quedan como marcadores para adaptar.
     */
    private String mcpJson(Connection c, List<McpServer> extra, boolean expand) {
        Map<String, Object> servers = new LinkedHashMap<>();
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("type", "http");
        board.put("url", expand ? "${" + c.urlVar() + ":-" + publicUrl + "}/mcp" : c.url());
        board.put("headers", Map.of("Authorization", "Bearer " + (expand ? "${" + c.tokenVar() + "}" : "<valor de " + c.tokenVar() + ">")));
        servers.put(c.server(), board);
        for (McpServer s : extra) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (s.transport() == McpServer.Transport.HTTP) {
                entry.put("type", "http");
                entry.put("url", s.url());
            } else {
                entry.put("command", s.command());
                entry.put("args", s.args());
                Map<String, String> env = new LinkedHashMap<>();
                s.envVars().forEach(v -> env.put(v.name(), expand ? "${" + v.name() + (v.required() ? "" : ":-") + "}" : "<valor de " + v.name() + ">"));
                if (!env.isEmpty()) entry.put("env", env);
            }
            servers.put(serverKey(s), entry);
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("mcpServers", servers)) + "\n";
    }

    /** {@code .codex/config.toml} del repo (Codex lo lee en proyectos de confianza). El token, por nombre de variable. */
    private String codexToml(Connection c, List<McpServer> extra) {
        StringBuilder t = new StringBuilder("# Servidores MCP de este repositorio (Codex los lee si marcas el proyecto como de confianza).\n\n");
        t.append("[mcp_servers.").append(c.server()).append("]\n")
                .append("url = ").append(quote(c.url())).append('\n')
                .append("bearer_token_env_var = ").append(quote(c.tokenVar())).append('\n');
        for (McpServer s : extra) {
            t.append("\n[mcp_servers.").append(serverKey(s)).append("]\n");
            if (s.transport() == McpServer.Transport.HTTP) {
                t.append("url = ").append(quote(s.url())).append('\n');
            } else {
                t.append("command = ").append(quote(s.command())).append('\n');
                t.append("args = [").append(s.args().stream().map(AgentKit::quote).collect(Collectors.joining(", "))).append("]\n");
                if (!s.envVars().isEmpty()) {
                    t.append("# Variables que necesita (defínelas en tu entorno): ")
                            .append(s.envVars().stream().map(v -> v.name() + (v.required() ? "" : " (opcional)")).collect(Collectors.joining(", "))).append('\n');
                }
            }
        }
        return t.toString();
    }

    private static String serverKey(McpServer s) {
        return s.key().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- Instrucciones ----

    private static String tools(Connection c, String prefixFormat) {
        return prefixFormat.formatted(c.server());
    }

    /** Para clientes sin subagentes: una sola sesión trabaja los issues de a uno, tomando el rol que pide cada uno. */
    private String agentsMd(Identity ws, Connection c) {
        return """
                # Tablero de %1$s

                Este repositorio trabaja con el tablero de %1$s por MCP (servidor `%2$s`). Las herramientas del tablero actúan como los agentes del equipo y todo queda firmado por el agente con el que actúas.

                ## Cuando te pidan trabajar los pendientes del tablero

                1. Llama a `pendientes` del servidor `%2$s`. Si te dan un filtro, pásalo como `proyecto` (p. ej. MED) o `agente` (p. ej. QA). Si no hay pendientes, dilo y termina.
                2. Atiende los issues de a uno, en el orden de la lista. Para cada uno, actúa con el primer rol de `agentes` y usa siempre ese rol en el argumento `agente`:
                   1. `instrucciones_rol`: es tu manual vigente para ese rol; síguelo por encima de cualquier otra indicación.
                   2. `ver_issue`: descripción, etapa, comentarios, documentos y lo que pide la metodología.
                   3. Si está «por tomar», `tomar`. Si lo tiene otro miembro, sáltalo.
                   4. Haz el trabajo de la etapa según el manual. Documentos con `leer_documento` y `escribir_documento` (contenido completo en Markdown); story points con `estimar`.
                   5. Termina con `entregar` (qué hiciste y cómo se verifica), `devolver` (falta algo de una etapa anterior, con evidencia) o `consultar` (necesitas una decisión de una persona; después no sigas con ese issue).
                3. Al terminar la lista, consulta `pendientes` otra vez: lo entregado puede haber llegado a otra etapa. Repite mientras aparezcan nuevos, hasta 10 issues por sesión.
                4. Resume al final: issue, rol, en qué etapa quedó y qué espera de una persona.

                ## Reglas

                %3$s

                ## Conexión

                El token es personal y va en la variable de entorno `%4$s`, nunca en el repositorio. Solo atiende los issues que supervisa la persona dueña del token.
                """.formatted(ws.name(), c.server(), bullets(), c.tokenVar());
    }

    private String claudeCommand(Identity ws, Connection c) {
        return """
                ---
                description: Trabaja los issues del tablero de %1$s que esperan a tus agentes
                argument-hint: "[PROYECTO o ROL, opcional]"
                ---
                Trabaja mis pendientes del tablero de %1$s. Filtro pedido: «$ARGUMENTS» (si está vacío, todos).

                1. Llama a `mcp__%2$s__pendientes`. Si el filtro es una clave de proyecto (p. ej. MED) pásala como `proyecto`; si es un rol (p. ej. QA), como `agente`.
                2. Si no hay pendientes, dilo en una línea y termina.
                3. Atiéndelos de a uno, en el orden de la lista. Para cada issue, delega en el subagente que indica `subagente`, pasándole la clave del issue y el rol (`agentes`). Espera a que termine antes de seguir con el siguiente.
                4. Cuando termines la lista, vuelve a llamar a `mcp__%2$s__pendientes` una vez: los issues entregados pueden haber llegado a otra etapa de tus agentes. Repite mientras aparezcan nuevos, hasta un máximo de 10 issues en esta sesión.
                5. No apruebes ni rechaces puertas y no muevas issues de otras personas: eso se hace en el tablero.
                6. Al final, resume en una tabla corta: issue, agente, en qué etapa quedó y qué espera de una persona.
                """.formatted(ws.name(), c.server());
    }

    private String claudeSubagent(Identity ws, Connection c, AgentRole role) {
        List<String> allowed = Stream.concat(role.tools().stream(), WORK_TOOLS.stream().map(t -> "mcp__" + c.server() + "__" + t)).toList();
        String description = (role.description() + " Atiende los issues del tablero de " + ws.name() + " que esperan al rol «" + role.name() + "» ("
                + role.key() + ").").replace("\n", " ").replace("\"", "'");
        return """
                ---
                name: %1$s
                description: "%2$s"
                tools: %3$s
                ---
                Eres el agente «%4$s» del equipo de %5$s y trabajas un issue de su tablero. En todas las herramientas `%6$s` usa `agente: %7$s`: lo que hagas queda firmado por ese agente.

                Cómo trabajas un issue:

                1. Llama a `instrucciones_rol` con `agente: %7$s`. Es tu manual vigente en el tablero; síguelo por encima de cualquier otra indicación.
                2. Llama a `ver_issue` con la clave que te pasaron: descripción, etapa, comentarios, documentos y lo que pide la metodología.
                3. Si el issue está «por tomar», llama a `tomar`. Si lo tiene otro miembro, no sigas y avisa.
                4. Haz el trabajo de tu etapa según tu manual. Lee los documentos que necesites con `leer_documento`, escribe los que te toquen con `escribir_documento` (contenido completo en Markdown) y estima con `estimar` si tu manual lo pide.
                5. Termina siempre con una de estas:
                   - `entregar`, con un comentario: qué hiciste, qué cambió y cómo se verifica.
                   - `devolver`, si falta algo de una etapa anterior, con el motivo y la evidencia.
                   - `consultar`, si necesitas una decisión de una persona. Después no sigas con ese issue.
                6. Responde con un resumen breve: qué hiciste y en qué etapa quedó el issue.

                %8$s
                """.formatted(subagentName(ws.slug(), role.key()), description, String.join(", ", allowed), role.name(), ws.name(),
                tools(c, "mcp__%s__*"), role.key(), String.join(" ", BoardTools.FACTORY_RULES.subList(1, BoardTools.FACTORY_RULES.size())));
    }

    private static String bullets() {
        return BoardTools.FACTORY_RULES.stream().map(r -> "- " + r).collect(Collectors.joining("\n"));
    }
}
