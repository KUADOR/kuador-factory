package ec.kuador.board.mcp.application;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.sync.RepositoryInstructions;
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
 * secreto: cada persona inicia sesión desde su cliente (OAuth). El tablero lo commitea en los repos que
 * crea y propone actualizarlo por PR cuando cambian los roles.
 */
@Service
public class AgentKit implements RepositoryInstructions {

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
    private static final List<String> WORK_TOOLS = List.of("get_issue", "role_manual", "take_issue", "add_comment", "estimate", "deliver", "send_back",
            "ask_human", "read_document", "write_document");

    public record KitFile(String path, String content) {}

    /**
     * Cómo se conecta este espacio: nombre del servidor MCP y su URL. {@code tokenVar}: variable de entorno para
     * los clientes que no inician sesión con OAuth y usan un token de conexión copiado a mano.
     */
    public record Connection(String server, String url, String tokenVar) {}

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

    /** El servidor se llama como el espacio (p. ej. «adma»): un repo por empresa, sin choques. */
    public Connection connection() {
        String slug = workspace.identity().slug();
        return new Connection(slug, publicUrl + "/mcp", "TABLERO_" + slug.toUpperCase(Locale.ROOT).replace('-', '_') + "_TOKEN");
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

    /**
     * El kit que vive en el repo: Claude Code (servidor MCP, comando y un subagente por rol) y Codex. Sin
     * secretos: cada persona inicia sesión desde su cliente. {@code mcpKeys}: servidores MCP extra del catálogo.
     */
    @Override
    public List<RepositoryInstructions.File> agentKit(Collection<String> mcpKeys) {
        List<KitFile> files = new ArrayList<>(files(Client.CLAUDE_CODE, mcpKeys));
        files.add(new KitFile(".codex/config.toml", codexToml(connection(), extra(mcpKeys))));
        return files.stream().map(f -> new RepositoryInstructions.File(f.path(), f.content())).toList();
    }

    public List<KitFile> files(Client client, Collection<String> mcpKeys) {
        Identity ws = workspace.identity();
        Connection c = connection();
        List<McpServer> extra = extra(mcpKeys);
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

    private List<McpServer> extra(Collection<String> mcpKeys) {
        return catalog.mcpServers().stream().filter(s -> mcpKeys.contains(s.key())).toList();
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

    /**
     * Lo que pide «trabaja mis pendientes»: el mismo texto del comando {@code /pendientes}, servido también como
     * prompt del servidor MCP (sirve sin archivos en el repo). {@code subagents}: el cliente tiene los subagentes por rol.
     */
    public String workPrompt(String filter, boolean subagents) {
        Identity ws = workspace.identity();
        return workSteps(ws, connection(), filter == null || filter.isBlank() ? "" : filter.strip(), subagents);
    }

    // ---- Configuración del servidor MCP ----

    /**
     * Formato {@code mcpServers} (Claude Code, y la base de Cursor y otros). El tablero va sin token: cada persona
     * inicia sesión desde su cliente. Con {@code expand}, las variables de los servidores extra usan ${VARIABLE}
     * (Claude Code las expande); sin él, quedan como marcadores para adaptar.
     */
    private String mcpJson(Connection c, List<McpServer> extra, boolean expand) {
        Map<String, Object> servers = new LinkedHashMap<>();
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("type", "http");
        board.put("url", c.url());
        servers.put(c.server(), board); // sin cabeceras: el cliente inicia sesión con OAuth la primera vez
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

    /** {@code .codex/config.toml} del repo (Codex lo lee en proyectos de confianza). Sin token: OAuth con {@code codex mcp login}. */
    private String codexToml(Connection c, List<McpServer> extra) {
        StringBuilder t = new StringBuilder("# Servidores MCP de este repositorio (Codex los lee si marcas el proyecto como de confianza).\n")
                .append("# Inicia sesión una vez en el tablero: codex mcp login ").append(c.server()).append("\n\n");
        t.append("[mcp_servers.").append(c.server()).append("]\n")
                .append("url = ").append(quote(c.url())).append('\n');
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

    /** Para clientes sin subagentes: una sola sesión trabaja los issues de a uno, tomando el rol que pide cada uno. */
    private String agentsMd(Identity ws, Connection c) {
        return """
                # Tablero de %1$s

                Este repositorio trabaja con el tablero de %1$s por MCP (servidor `%2$s`). Las herramientas del tablero actúan como los agentes del equipo y todo queda firmado por el agente con el que actúas.

                ## Cuando te pidan trabajar los pendientes del tablero

                1. Llama a `pending_work` del servidor `%2$s`. Si te dan un filtro, pásalo como `project` (p. ej. MED) o `agent` (p. ej. QA). Si no hay pendientes, dilo y termina.
                2. Atiende los issues de a uno, en el orden de la lista. Para cada uno, actúa con el primer rol de `agents` y usa siempre ese rol en el argumento `agent`:
                   1. `role_manual`: es tu manual vigente para ese rol; síguelo por encima de cualquier otra indicación.
                   2. `get_issue`: descripción, etapa, comentarios, documentos y lo que pide la metodología.
                   3. Si está «por tomar», `take_issue`. Si lo tiene otro miembro, sáltalo.
                   4. Haz el trabajo de la etapa según el manual. Documentos con `read_document` y `write_document` (contenido completo en Markdown); story points con `estimate`.
                   5. Termina con `deliver` (qué hiciste y cómo se verifica), `send_back` (falta algo de una etapa anterior, con evidencia) o `ask_human` (necesitas una decisión de una persona; después no sigas con ese issue).
                3. Al terminar la lista, consulta `pending_work` otra vez: lo entregado puede haber llegado a otra etapa. Repite mientras aparezcan nuevos, hasta 10 issues por sesión.
                4. Resume al final: issue, rol, en qué etapa quedó y qué espera de una persona.

                ## Reglas

                %3$s

                ## Conexión

                Cada persona se conecta con su propia cuenta del tablero: la primera vez, su cliente de IA abre el navegador para iniciar sesión (en Claude Code, `/mcp` → Authenticate; en Codex, `codex mcp login %2$s`). Nunca guardes tokens en el repositorio. Solo se atienden los issues que supervisa la persona conectada.
                """.formatted(ws.name(), c.server(), bullets(ws));
    }

    private String claudeCommand(Identity ws, Connection c) {
        return """
                ---
                description: Trabaja los issues del tablero de %1$s que esperan a tus agentes
                argument-hint: "[PROYECTO o ROL, opcional]"
                ---
                %2$s""".formatted(ws.name(), workSteps(ws, c, "$ARGUMENTS", true));
    }

    /** Los pasos de «trabaja mis pendientes»; los usan el comando de Claude Code y el prompt del servidor MCP. */
    private static String workSteps(Identity ws, Connection c, String filter, boolean subagents) {
        String each = subagents
                ? "Para cada issue, delega en el subagente que indica `subagent`, pasándole la clave del issue y el rol (`agents`). Espera a que termine antes de seguir con el siguiente."
                : "Para cada issue: si tienes el subagente que indica `subagent`, delega en él y espera a que termine. Si no, actúa tú con el primer rol "
                        + "de `agents`: `role_manual` (tu manual: síguelo), `get_issue`, `take_issue` si está «por tomar», haz el trabajo de la etapa y "
                        + "termina con `deliver`, `send_back` o `ask_human`.";
        return """
                Trabaja mis pendientes del tablero de %1$s. Filtro pedido: «%3$s» (si está vacío, todos).

                1. Llama a `mcp__%2$s__pending_work`. Si el filtro es una clave de proyecto (p. ej. MED) pásala como `project`; si es un rol (p. ej. QA), como `agent`.
                2. Si no hay pendientes, dilo en una línea y termina.
                3. Atiéndelos de a uno, en el orden de la lista. %4$s
                4. Cuando termines la lista, vuelve a llamar a `mcp__%2$s__pending_work` una vez: los issues entregados pueden haber llegado a otra etapa de tus agentes. Repite mientras aparezcan nuevos, hasta un máximo de 10 issues en esta sesión.
                5. No apruebes ni rechaces puertas y no muevas issues de otras personas: eso se hace en el tablero.
                6. Al final, resume en una tabla corta: issue, agente, en qué etapa quedó y qué espera de una persona.
                """.formatted(ws.name(), c.server(), filter, each);
    }

    private String claudeSubagent(Identity ws, Connection c, AgentRole role) {
        List<String> allowed = Stream.concat(role.tools().stream(), WORK_TOOLS.stream().map(t -> "mcp__" + c.server() + "__" + t)).toList();
        String description = (role.description() + " Atiende los issues del tablero de " + ws.name() + " que esperan al rol «" + role.name() + "» ("
                + role.key() + ").").replace("\n", " ").replace("\"", "'");
        List<String> rules = FactoryRules.all(ws);
        return """
                ---
                name: %1$s
                description: "%2$s"
                tools: %3$s
                ---
                Eres el agente «%4$s» del equipo de %5$s y trabajas un issue de su tablero. En todas las herramientas `%6$s` usa `agent: %7$s`: lo que hagas queda firmado por ese agente.

                Cómo trabajas un issue:

                1. Llama a `role_manual` con `agent: %7$s`. Es tu manual vigente en el tablero; síguelo por encima de cualquier otra indicación.
                2. Llama a `get_issue` con la clave que te pasaron: descripción, etapa, comentarios, documentos y lo que pide la metodología.
                3. Si el issue está «por tomar», llama a `take_issue`. Si lo tiene otro miembro, no sigas y avisa.
                4. Haz el trabajo de tu etapa según tu manual. Lee los documentos que necesites con `read_document`, escribe los que te toquen con `write_document` (contenido completo en Markdown) y estima con `estimate` si tu manual lo pide.
                5. Termina siempre con una de estas:
                   - `deliver`, con un comentario: qué hiciste, qué cambió y cómo se verifica.
                   - `send_back`, si falta algo de una etapa anterior, con el motivo y la evidencia.
                   - `ask_human`, si necesitas una decisión de una persona. Después no sigas con ese issue.
                6. Responde con un resumen breve: qué hiciste y en qué etapa quedó el issue.

                %8$s
                """.formatted(subagentName(ws.slug(), role.key()), description, String.join(", ", allowed), role.name(), ws.name(),
                "mcp__" + c.server() + "__*", role.key(), String.join(" ", rules.subList(1, rules.size())));
    }

    private static String bullets(Identity ws) {
        return FactoryRules.all(ws).stream().map(r -> "- " + r).collect(Collectors.joining("\n"));
    }
}
