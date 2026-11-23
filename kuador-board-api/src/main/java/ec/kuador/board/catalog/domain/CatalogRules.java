package ec.kuador.board.catalog.domain;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.TransitionDef;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.shared.error.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reglas de cada entrada del catálogo. Se aplican al guardar y al cargar los valores de fábrica. */
public final class CatalogRules {

    public static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.md$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]{0,63}$");
    private static final Pattern URL = Pattern.compile("^https?://[^\\s]+$");
    private static final int MAX_TEXT = 100_000;

    private CatalogRules() {}

    public static void validate(CatalogType type, String key, Object entry) {
        requireKey(key);
        switch (type) {
            case WORKFLOW_TEMPLATE -> workflowTemplate((WorkflowTemplate) entry);
            case METHODOLOGY -> methodology((Methodology) entry);
            case AGENT_ROLE -> role((AgentRole) entry);
            case MCP_SERVER -> mcp((McpServer) entry);
            case COMPONENT_TEMPLATE -> component((ComponentTemplate) entry);
            case AGENT_POLICY -> policy(key, (ec.kuador.board.catalog.AgentPolicy) entry);
        }
    }

    private static void workflowTemplate(WorkflowTemplate t) {
        name(t.name());
        if (t.capabilities() == null) throw new DomainException("Indica qué usa el tablero: documentos, repositorio de código o reparto en tareas (puede ser nada).");
        ColumnDef.validate(t.columns());
        StageDef.validate(t.stages(), t.columns());
        java.util.Set<String> keys = new java.util.HashSet<>(t.columns().stream().map(ColumnDef::key).toList());
        for (TransitionDef tr : t.transitions()) {
            if (!keys.contains(tr.from()) || !keys.contains(tr.to()) || tr.from().equals(tr.to())) {
                throw new DomainException("La transición " + tr.from() + " → " + tr.to() + " de la plantilla no es válida.");
            }
        }
        List<IssueTypeDef> types = t.issueTypes();
        if (types == null || types.isEmpty()) throw new DomainException("La plantilla necesita al menos un tipo de issue.");
        Set<IssueType> seen = new HashSet<>();
        for (IssueTypeDef d : types) {
            if (d.type() == null || !seen.add(d.type())) throw new DomainException("Tipo de issue repetido o vacío: " + d.type() + ".");
            if (d.label() == null || d.label().isBlank() || d.label().length() > 40) {
                throw new DomainException("Cada tipo de issue necesita un nombre de hasta 40 caracteres.");
            }
            ec.kuador.board.catalog.FieldDef.validate(d.fields(), d.label());
        }
        if (seen.stream().allMatch(x -> x == IssueType.EPIC)) {
            throw new DomainException("La plantilla necesita algún tipo de issue además de las épicas.");
        }
    }

    private static void methodology(Methodology m) {
        name(m.name());
        text(m.summary(), "El resumen");
        if (m.agentRules() == null || m.agentRules().size() > 30) throw new DomainException("Indica hasta 30 reglas para agentes.");
        m.agentRules().forEach(r -> text(r, "Cada regla"));
        if (m.documents() == null) throw new DomainException("Indica los documentos de la metodología (puede ser una lista vacía).");
        Set<String> kinds = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (DocumentDef d : m.documents()) {
            requireKey(d.kind());
            if (!kinds.add(d.kind())) throw new DomainException("El documento " + d.kind() + " aparece dos veces.");
            name(d.label());
            if (d.scope() == null) throw new DomainException("Indica si «" + d.label() + "» es del proyecto o de un issue.");
            if (d.condition() == null) throw new DomainException("Indica cuándo se escribe «" + d.label() + "».");
            path(d);
            if (!paths.add(d.pathPattern())) throw new DomainException("Dos documentos usan la ruta " + d.pathPattern() + ".");
            text(d.template(), "La plantilla de «" + d.label() + "»");
            if (d.writtenBy() != null && !KEY.matcher(d.writtenBy()).matches()) {
                throw new DomainException("Rol no válido en «" + d.label() + "»: " + d.writtenBy() + ".");
            }
        }
    }

    private static void path(DocumentDef d) {
        String p = d.pathPattern();
        if (p == null || p.contains("..") || p.startsWith("/")) throw new DomainException("Ruta no válida en «" + d.label() + "».");
        long placeholders = p.chars().filter(ch -> ch == '{').count();
        boolean hasIssuePlaceholder = p.contains("{feature}") || p.contains("{task}");
        if (d.scope() == DocumentDef.Scope.ISSUE && (placeholders != 1 || !hasIssuePlaceholder)) {
            throw new DomainException("«" + d.label() + "» es de issue: su ruta debe tener {feature} o {task} (una vez).");
        }
        if (d.scope() == DocumentDef.Scope.PROJECT && placeholders > 0) {
            throw new DomainException("«" + d.label() + "» es del proyecto: su ruta es fija, sin {…}.");
        }
        if (!SAFE_PATH.matcher(p.replace("{feature}", "x").replace("{task}", "x")).matches()) {
            throw new DomainException("La ruta de «" + d.label() + "» debe ser relativa y terminar en .md.");
        }
    }

    private static void role(AgentRole r) {
        name(r.name());
        text(r.description(), "La descripción");
        text(r.instructions(), "Las instrucciones");
        if (r.tools() == null) throw new DomainException("Indica las herramientas del rol (puede ser una lista vacía).");
        if (r.automation() == null) throw new DomainException("Indica la automatización del rol (NONE si no tiene).");
    }

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern VARIABLE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    private static void policy(String key, ec.kuador.board.catalog.AgentPolicy p) {
        if (!ec.kuador.board.catalog.AgentPolicy.KEY.equals(key)) {
            throw new DomainException("Las reglas de agentes son una sola entrada por espacio: " + ec.kuador.board.catalog.AgentPolicy.KEY + ".");
        }
        name(p.name());
        if (p.rules() == null || p.limits() == null || p.messages() == null || p.repo() == null) {
            throw new DomainException("Faltan secciones de las reglas de agentes (reglas, límites, mensajes y plantillas del repo).");
        }
        for (List<String> list : List.of(nz(p.rules().common()), nz(p.rules().board()), nz(p.rules().questions()))) {
            if (list.size() > 40) throw new DomainException("Cada lista de reglas admite hasta 40 reglas.");
            for (String r : list) if (r != null && r.length() > 2_000) throw new DomainException("Cada regla admite hasta 2000 caracteres.");
        }
        var l = p.limits();
        range("Vueltas de preguntas", l.questionRounds(), 1, 10);
        range("Comentarios que lee", l.commentsRead(), 1, 100);
        range("Archivos que recibe", l.filesRead(), 1, 30);
        range("Búsquedas web", l.webSearches(), 1, 30);
        range("Vueltas con Claude", l.turnsClaude(), 1, 40);
        range("Vueltas con otros proveedores", l.turnsOther(), 1, 80);
        if (p.roles() != null) {
            p.roles().forEach((role, t) -> {
                if (role == null || role.isBlank() || role.length() > 40) throw new DomainException("Cada sugerencia por rol lleva la clave del rol.");
                if (t == null) return;
                if (t.model() != null && t.model().length() > 60) throw new DomainException("El modelo sugerido para " + role + " es demasiado largo.");
                if (t.effort() != null && !t.effort().isBlank() && !ec.kuador.board.catalog.AgentPolicy.EFFORTS.contains(t.effort().strip())) {
                    throw new DomainException("El esfuerzo de " + role + " es uno de: " + String.join(", ", ec.kuador.board.catalog.AgentPolicy.EFFORTS) + ".");
                }
            });
        }
        var r = p.rules();
        var m = p.messages();
        var t = p.repo();
        for (String text : java.util.Arrays.asList(r.epicPlan(), r.bigCard(), r.codeReview(), r.sendBack(), r.web(), r.document(), r.taskSplit(),
                r.proposalFields(), r.gateReview(), r.gateEscalate(), r.gateDecide(), m.returnForReview(), m.returnWithQuestions(), m.toGate(), m.toAgent(), m.toPerson(), m.questionsTitle(),
                m.pendingTitle(), m.readDocument(), m.askFooter(), m.failedStart(), m.failedFinish(), t.agentsMd(), t.workSteps(), t.subagent(),
                t.mcpInstructions(), t.readme(), t.pieceReadme(), t.ci(), t.gitignore())) {
            if (text != null && text.length() > MAX_TEXT) throw new DomainException("Un texto de las reglas de agentes pasa de " + MAX_TEXT + " caracteres.");
        }
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static void range(String what, int value, int min, int max) {
        if (value < min || value > max) throw new DomainException(what + ": entre " + min + " y " + max + ".");
    }

    private static void component(ComponentTemplate c) {
        name(c.name());
        text(c.description(), "La descripción");
        if (c.kind() == null) throw new DomainException("Indica el tipo de pieza (backend, frontend, microfrontend...).");
        if (c.stack() == null || c.stack().isBlank() || c.stack().length() > 120) throw new DomainException("Indica el stack (p. ej. «Angular 20 · TypeScript»).");
        boolean hasRepo = c.templateRepo() != null && !c.templateRepo().isBlank();
        if (hasRepo && !REPO.matcher(c.templateRepo().strip()).matches()) {
            throw new DomainException("El repo plantilla va como owner/repo, por ejemplo mi-org/plantilla-api.");
        }
        if (hasRepo && (c.templateRef() == null || !REF.matcher(c.templateRef()).matches() || c.templateRef().contains(".."))) {
            throw new DomainException("Fija la versión de la plantilla: una etiqueta, rama o commit (p. ej. v1.0.0).");
        }
        if (c.variables() == null || c.ciChecks() == null || c.suggestedRoles() == null || c.mcpServers() == null) {
            throw new DomainException("Indica variables, verificaciones, roles y servidores MCP (pueden ser listas vacías).");
        }
        for (ComponentTemplate.Variable v : c.variables()) {
            if (v.name() == null || !VARIABLE.matcher(v.name()).matches()) throw new DomainException("Nombre de variable no válido: " + v.name() + " (mayúsculas, números o _).");
            if (ComponentTemplate.BUILT_IN_VARIABLES.contains(v.name())) throw new DomainException(v.name() + " la pone el tablero; usa otro nombre.");
        }
        if (c.commands() == null) throw new DomainException("Indica los comandos (pueden quedar vacíos).");
    }

    /** Lanzadores que puede usar un servidor MCP STDIO del catálogo. */
    static final java.util.List<String> MCP_COMMANDS = java.util.List.of("npx", "uvx", "bunx", "pnpx", "docker", "node", "deno", "pipx");

    private static void mcp(McpServer s) {
        name(s.name());
        text(s.description(), "La descripción");
        if (s.transport() == null) throw new DomainException("Indica el transporte: STDIO o HTTP.");
        if (s.transport() == McpServer.Transport.STDIO && (s.command() == null || s.command().isBlank())) {
            throw new DomainException("Un servidor STDIO necesita el comando que lo inicia.");
        }
        if (s.transport() == McpServer.Transport.HTTP && (s.url() == null || !URL.matcher(s.url()).matches())) {
            throw new DomainException("Un servidor HTTP necesita una URL http(s).");
        }
        if (s.args() == null || s.envVars() == null) throw new DomainException("Indica argumentos y variables (pueden ser listas vacías).");
        // El comando termina en la PC de cada persona del equipo (kit y .mcp.json): solo lanzadores conocidos, sin rutas.
        if (s.transport() == McpServer.Transport.STDIO && !MCP_COMMANDS.contains(s.command().strip())) {
            throw new DomainException("El comando debe ser uno de: " + String.join(", ", MCP_COMMANDS) + ". El servidor va en los argumentos.");
        }
        for (String a : s.args()) {
            if (a == null || a.length() > 300 || a.chars().anyMatch(Character::isISOControl)) {
                throw new DomainException("Argumento no válido: sin saltos de línea y de hasta 300 caracteres.");
            }
        }
        for (McpServer.EnvVar v : s.envVars()) {
            if (v.name() == null || !ENV_NAME.matcher(v.name()).matches()) {
                throw new DomainException("Nombre de variable no válido: " + v.name() + " (mayúsculas, números o _).");
            }
        }
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new DomainException("Clave no válida: " + key + ". Usa mayúsculas, números o _ (ej. SDD, PRODUCT_OWNER).");
        }
    }

    private static void name(String name) {
        if (name == null || name.isBlank() || name.length() > 80) throw new DomainException("El nombre es obligatorio (hasta 80 caracteres).");
    }

    private static void text(String text, String what) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
            throw new DomainException(what + " es obligatorio (hasta " + MAX_TEXT + " caracteres).");
        }
    }
}
