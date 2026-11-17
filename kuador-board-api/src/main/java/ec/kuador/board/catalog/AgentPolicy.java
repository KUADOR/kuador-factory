package ec.kuador.board.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reglas de agentes del espacio: lo que reciben todos los agentes además del manual de su rol (las reglas comunes, cómo
 * preguntar, cómo entregar), las perillas de costo (cuántas vueltas, comentarios, archivos y búsquedas) y los textos que
 * escriben en el tablero y en el repo. Una sola entrada por espacio, {@link #KEY}; viene de fábrica y la edita quien
 * administra. El núcleo de seguridad ({@link AgentRules#CORE}) no está aquí: va siempre y no se edita.
 *
 * <p>Los textos admiten variables entre llaves, p. ej. {@code {persona}} o {@code {columna}} (ver {@link AgentRules#fill}).
 * Un texto vacío vuelve al de fábrica.
 */
public record AgentPolicy(
        UUID id, String key, String name, String description, Rules rules, Limits limits, Messages messages, RepoTemplates repo,
        Map<String, Tuning> roles, boolean builtIn, long version) {

    public static final String KEY = "DEFAULT";

    /** Esfuerzos que se pueden elegir, de menos a más razonamiento (y tokens). */
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /** Sin la sugerencia por rol (copias anteriores). */
    public AgentPolicy(UUID id, String key, String name, String description, Rules rules, Limits limits, Messages messages, RepoTemplates repo,
            boolean builtIn, long version) {
        this(id, key, name, description, rules, limits, messages, repo, null, builtIn, version);
    }

    /**
     * Modelo y esfuerzo sugeridos para un rol (perillas de costo): los usa el agente si en su equipo nadie eligió otros.
     * {@code model} vacío = el del espacio; {@code effort} vacío = el del proveedor. Un modelo que el proveedor del espacio
     * no ofrece se ignora.
     */
    public record Tuning(String model, String effort) {
        public static final Tuning NONE = new Tuning(null, null);
    }

    /** Lo sugerido para un rol, o {@link Tuning#NONE}. */
    public Tuning tuning(String roleKey) {
        Tuning t = roles == null || roleKey == null ? null : roles.get(roleKey);
        return t == null ? Tuning.NONE : new Tuning(blankToNull(t.model()), blankToNull(t.effort()));
    }

    /** Lo que se le dice al modelo. Las listas van una regla por línea. */
    public record Rules(
            /** Todos los agentes: en el tablero, el PO/Arquitecto y los locales (Claude Code, Codex). */
            List<String> common,
            /** Solo los agentes que trabajan en el tablero con la clave de la empresa. */
            List<String> board,
            /** Cómo preguntar y qué no volver a preguntar. */
            List<String> questions,
            /** Épica: el plan termina creando sus tareas. */
            String epicPlan,
            /** Tarjeta demasiado grande para una persona o un agente. */
            String bigCard,
            /** Revisar el código en GitHub. Variables: {repos}, {clave}. */
            String codeReview,
            /** Devolver a la etapa anterior. Variable: {columna}. */
            String sendBack,
            /** Búsqueda en Internet. */
            String web,
            /** PO/Arquitecto: el documento lleva el detalle. */
            String document,
            /** PO/Arquitecto: repartir una épica en tareas. */
            String taskSplit,
            /** PO/Arquitecto: cómo llenar cada parte de la propuesta (resumen, historia, criterios, preguntas, estimación). */
            String proposalFields) {}

    /** Perillas de costo y alcance. */
    public record Limits(
            /** Vueltas de preguntas respondidas antes de avanzar igual con lo que quede como pendiente. */
            int questionRounds,
            /** Comentarios más recientes que lee el agente. */
            int commentsRead,
            /** Archivos de la tarjeta que recibe. */
            int filesRead,
            /** Búsquedas y páginas web por trabajo. */
            int webSearches,
            /** Vueltas máximas del modelo con Claude (ejecución de código). */
            int turnsClaude,
            /** Vueltas máximas del modelo con otros proveedores. */
            int turnsOther) {}

    /** Textos que escriben los agentes en el tablero. */
    public record Messages(
            /** Variables: {persona}. */
            String returnForReview,
            /** Variables: {persona}. */
            String returnWithQuestions,
            /** Pasó a una puerta. Variables: {columna}, {persona}. */
            String toGate,
            /** Pasó a la etapa de otro agente. Variables: {columna}. */
            String toAgent,
            /** Pasó a una columna que atiende una persona. Variables: {columna}, {persona}. */
            String toPerson,
            String questionsTitle,
            /** Variables: {vueltas}. */
            String pendingTitle,
            /** Variables: {documento}, {criterios}. */
            String readDocument,
            /** Debajo de una pregunta de un agente del tablero. */
            String askFooter,
            /** Variables: {motivo}. */
            String failedStart,
            /** Variables: {motivo}. */
            String failedFinish) {}

    /** Archivos que el tablero escribe en el repo. */
    public record RepoTemplates(
            /** AGENTS.md. Variables: {empresa}, {servidor}, {reglas}. */
            String agentsMd,
            /** Pasos de «trabaja mis pendientes» (comando /pendientes y prompt del MCP). Variables: {empresa}, {servidor}, {pedido}, {cada_issue}. */
            String workSteps,
            /** Subagente de un rol. Variables: {rol}, {empresa}, {herramientas_tablero}, {clave_rol}, {reglas}. */
            String subagent,
            /** Instrucciones del servidor MCP. Variables: {empresa}. */
            String mcpInstructions,
            /** README del repo del proyecto. Variables: {proyecto}, {clave}, {descripcion}, {piezas}, {documentos}. */
            String readme,
            /** README de una pieza. Variables: {proyecto}, {pieza}, {clave}, {stack}, {comandos}, {repo_documentos}. */
            String pieceReadme,
            /** CI mínimo. Variables: {archivos}. */
            String ci,
            String gitignore) {}

    /** Esta política con los textos vacíos tomados de {@code fallback} (la de fábrica). */
    public AgentPolicy orElse(AgentPolicy fallback) {
        if (fallback == null) return this;
        Rules r = rules == null ? fallback.rules : rules;
        Rules fr = fallback.rules;
        Rules mergedRules = new Rules(list(r.common(), fr.common()), list(r.board(), fr.board()), list(r.questions(), fr.questions()),
                text(r.epicPlan(), fr.epicPlan()), text(r.bigCard(), fr.bigCard()), text(r.codeReview(), fr.codeReview()),
                text(r.sendBack(), fr.sendBack()), text(r.web(), fr.web()), text(r.document(), fr.document()), text(r.taskSplit(), fr.taskSplit()),
                text(r.proposalFields(), fr.proposalFields()));
        Limits l = limits == null ? fallback.limits : limits;
        Limits fl = fallback.limits;
        Limits mergedLimits = new Limits(num(l.questionRounds(), fl.questionRounds()), num(l.commentsRead(), fl.commentsRead()),
                num(l.filesRead(), fl.filesRead()), num(l.webSearches(), fl.webSearches()), num(l.turnsClaude(), fl.turnsClaude()),
                num(l.turnsOther(), fl.turnsOther()));
        Messages m = messages == null ? fallback.messages : messages;
        Messages fm = fallback.messages;
        Messages mergedMessages = new Messages(text(m.returnForReview(), fm.returnForReview()), text(m.returnWithQuestions(), fm.returnWithQuestions()),
                text(m.toGate(), fm.toGate()), text(m.toAgent(), fm.toAgent()), text(m.toPerson(), fm.toPerson()),
                text(m.questionsTitle(), fm.questionsTitle()), text(m.pendingTitle(), fm.pendingTitle()), text(m.readDocument(), fm.readDocument()),
                text(m.askFooter(), fm.askFooter()), text(m.failedStart(), fm.failedStart()), text(m.failedFinish(), fm.failedFinish()));
        RepoTemplates t = repo == null ? fallback.repo : repo;
        RepoTemplates ft = fallback.repo;
        RepoTemplates mergedRepo = new RepoTemplates(text(t.agentsMd(), ft.agentsMd()), text(t.workSteps(), ft.workSteps()),
                text(t.subagent(), ft.subagent()), text(t.mcpInstructions(), ft.mcpInstructions()), text(t.readme(), ft.readme()),
                text(t.pieceReadme(), ft.pieceReadme()), text(t.ci(), ft.ci()), text(t.gitignore(), ft.gitignore()));
        // Por rol: lo del espacio manda; lo que no dice, de fábrica.
        Map<String, Tuning> mergedRoles = new java.util.TreeMap<>();
        if (fallback.roles != null) mergedRoles.putAll(fallback.roles);
        if (roles != null) {
            roles.forEach((role, own) -> {
                if (own == null) return;
                Tuning f = mergedRoles.getOrDefault(role, Tuning.NONE);
                // Vacío = a propósito el del espacio (o del proveedor); se guarda así para que no vuelva el de fábrica.
                mergedRoles.put(role, new Tuning(own.model() == null ? f.model() : own.model().strip(),
                        own.effort() == null ? f.effort() : own.effort().strip()));
            });
        }
        return new AgentPolicy(id, key, text(name, fallback.name), description, mergedRules, mergedLimits, mergedMessages, mergedRepo,
                Map.copyOf(mergedRoles), builtIn, version);
    }

    /** Rellena un texto de esta política con sus variables. */
    public static String fill(String template, Map<String, ?> vars) {
        return AgentRules.fill(template, vars);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String text(String own, String fallback) {
        return own == null || own.isBlank() ? fallback : own;
    }

    private static List<String> list(List<String> own, List<String> fallback) {
        if (own == null) return fallback;
        return own.stream().filter(x -> x != null && !x.isBlank()).map(String::strip).toList();
    }

    private static int num(int own, int fallback) {
        return own > 0 ? own : fallback;
    }
}
