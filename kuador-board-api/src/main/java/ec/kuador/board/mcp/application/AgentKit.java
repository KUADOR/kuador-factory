package ec.kuador.board.mcp.application;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.AgentRules;
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
            "ask_human", "read_document", "write_document", "read_attachment", "create_tasks");

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
    private final FactoryRules rules;

    AgentKit(CatalogApi catalog, WorkspaceApi workspace, ObjectMapper json, @Value("${board.public-url:http://localhost:8091}") String publicUrl,
            FactoryRules rules) {
        this.rules = rules;
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
        // Cadena básica de TOML: además de \\ y \", los saltos de línea y otros controles van escapados.
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : v.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (Character.isISOControl(ch)) sb.append(String.format("\\u%04X", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---- Instrucciones (plantillas de Catálogo → Reglas de agentes) ----

    /** Para clientes sin subagentes: una sola sesión trabaja los issues de a uno, tomando el rol que pide cada uno. */
    private String agentsMd(Identity ws, Connection c) {
        return AgentRules.fill(catalog.agentPolicy().repo().agentsMd(),
                Map.of("empresa", ws.name(), "servidor", c.server(), "reglas", AgentRules.bullets(rules.allWithCode())));
    }

    private String claudeCommand(Identity ws, Connection c) {
        return """
                ---
                description: Trabaja los issues del tablero de %1$s que esperan a tus agentes
                argument-hint: "[PROYECTO o ROL] [--paralelo]"
                ---
                %2$s""".formatted(ws.name(), workSteps(ws, c, "$ARGUMENTS", true));
    }

    /** Los pasos de «trabaja mis pendientes»; los usan el comando de Claude Code y el prompt del servidor MCP. */
    private String workSteps(Identity ws, Connection c, String filter, boolean subagents) {
        String each = subagents
                ? "Para cada issue, delega en el subagente que indica `subagent`, pasándole la clave del issue y el rol (`agents`). Espera a que termine antes de seguir con el siguiente "
                        + "(salvo en modo paralelo, abajo)."
                : "Para cada issue: si tienes el subagente que indica `subagent`, delega en él y espera a que termine. Si no, actúa tú con el primer rol "
                        + "de `agents`: `role_manual` (tu manual: síguelo), `get_issue`, `take_issue` si está «por tomar», haz el trabajo de la etapa y "
                        + "termina con `deliver`, `send_back` o `ask_human`.";
        return AgentRules.fill(catalog.agentPolicy().repo().workSteps(),
                Map.of("empresa", ws.name(), "servidor", c.server(), "pedido", filter, "cada_issue", each));
    }

    private String claudeSubagent(Identity ws, Connection c, AgentRole role) {
        List<String> allowed = Stream.concat(role.tools().stream(), WORK_TOOLS.stream().map(t -> "mcp__" + c.server() + "__" + t)).toList();
        String description = (role.description() + " Atiende los issues del tablero de " + ws.name() + " que esperan al rol «" + role.name() + "» ("
                + role.key() + ").").replace("\n", " ").replace("\"", "'");
        List<String> all = rules.forRole(role);
        // El encabezado (nombre, descripción y herramientas) lo arma el tablero: de él depende que el cliente encuentre al subagente.
        String body = AgentRules.fill(catalog.agentPolicy().repo().subagent(), Map.of("rol", role.name(), "empresa", ws.name(),
                "herramientas_tablero", "mcp__" + c.server() + "__*", "clave_rol", role.key(), "reglas", String.join(" ", all.subList(1, all.size()))));
        return """
                ---
                name: %1$s
                description: "%2$s"
                tools: %3$s
                ---
                """.formatted(subagentName(ws.slug(), role.key()), description, String.join(", ", allowed)) + body;
    }

    /**
     * Lo que necesita el runner para que el cliente de IA trabaje un issue con un rol sin archivos en la máquina: las
     * instrucciones del subagente (las mismas del kit, siempre las vigentes del catálogo) y las herramientas permitidas.
     */
    public record RunPlan(String server, String instructions, List<String> allowedTools) {}

    public RunPlan runPlan(String roleKey) {
        Identity ws = workspace.identity();
        Connection c = connection();
        AgentRole role = catalog.role(roleKey).orElseThrow(() -> new IllegalArgumentException("No existe el rol " + roleKey + "."));
        List<String> allowed = Stream.concat(role.tools().stream(), WORK_TOOLS.stream().map(t -> "mcp__" + c.server() + "__" + t)).toList();
        List<String> all = rules.forRole(role);
        String body = AgentRules.fill(catalog.agentPolicy().repo().subagent(), Map.of("rol", role.name(), "empresa", ws.name(),
                "herramientas_tablero", "mcp__" + c.server() + "__*", "clave_rol", role.key(), "reglas", String.join(" ", all.subList(1, all.size()))));
        return new RunPlan(c.server(), body, allowed);
    }

    /** Las instrucciones del servidor MCP (las ve el cliente al conectarse). */
    public String mcpInstructions() {
        return AgentRules.fill(catalog.agentPolicy().repo().mcpInstructions(), Map.of("empresa", workspace.identity().name()));
    }
}
