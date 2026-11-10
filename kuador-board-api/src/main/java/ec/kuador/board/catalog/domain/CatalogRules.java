package ec.kuador.board.catalog.domain;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.TransitionDef;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.IssueTypeDef;
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
        }
    }

    private static void workflowTemplate(WorkflowTemplate t) {
        name(t.name());
        if (t.kind() == null) throw new DomainException("Indica el tipo de proyecto: SOFTWARE o GENERAL.");
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
