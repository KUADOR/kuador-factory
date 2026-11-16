package ec.kuador.board.agents.domain;

import java.util.List;

/**
 * Arma el prompt del Agente PO. El system es estable (rol + reglas) para que sea cacheable; todo lo
 * variable va en el mensaje, en bloques etiquetados. Un documento demasiado largo se recorta y se
 * avisa al modelo que está recortado.
 */
public final class PoPrompt {

    static final int MAX_DOC_CHARS = 30_000;


    private PoPrompt() {}

    /** Rol del catálogo + especialidad del miembro. Estable entre issues: cacheable. */
    public static String system(PoContext c) {
        StringBuilder s = new StringBuilder("Eres ").append(c.agentName()).append(".\n\n");
        s.append(c.roleInstructions().strip()).append('\n');
        if (c.agentDescription() != null && !c.agentDescription().isBlank()) {
            s.append("\nTu especialidad: ").append(c.agentDescription().strip()).append('\n');
        }
        return s.toString();
    }

    public static String user(PoContext c) {
        StringBuilder u = new StringBuilder();
        u.append("<proyecto clave=\"").append(c.projectKey()).append("\" tipo=\"").append(c.projectKind()).append("\">")
                .append(c.projectName()).append("</proyecto>\n\n");
        if (c.methodologyName() != null) {
            u.append("<metodologia nombre=\"").append(c.methodologyName()).append("\">\n").append(c.methodologySummary()).append("\n");
            c.methodologyRules().forEach(r -> u.append("- ").append(r).append('\n'));
            u.append("</metodologia>\n\n");
        } else {
            u.append("<metodologia>El proyecto no tiene metodología definida.</metodologia>\n\n");
        }
        for (PoContext.Doc d : c.projectDocs()) {
            u.append("<documento tipo=\"").append(d.kind()).append("\" ruta=\"").append(d.path()).append("\">\n")
                    .append(clip(d.content())).append("\n</documento>\n\n");
        }
        if (c.epic() != null) {
            u.append("<epica clave=\"").append(c.epic().key()).append("\">\n# ").append(c.epic().title()).append('\n')
                    .append(nullToEmpty(c.epic().description())).append("\n</epica>\n\n");
        }
        PoContext.Issue i = c.issue();
        u.append("<issue clave=\"").append(i.key()).append("\" tipo=\"").append(i.type()).append("\" columna=\"").append(i.status())
                .append("\" prioridad=\"").append(i.priority()).append("\" puntos=\"").append(i.storyPoints() == null ? "sin estimar" : i.storyPoints())
                .append("\" etiquetas=\"").append(String.join(", ", i.labels())).append("\">\n# ").append(i.title()).append('\n')
                .append(i.description() == null || i.description().isBlank() ? "(sin descripción)" : i.description()).append("\n</issue>\n\n");
        if (!c.comments().isEmpty()) {
            u.append("<comentarios>\n");
            for (PoContext.Comment cm : c.comments()) {
                u.append("- ").append(cm.author()).append(cm.byAgent() ? " (agente)" : "").append(": ").append(cm.body().strip()).append('\n');
            }
            u.append("</comentarios>\n\n");
        }
        PoContext.Guide g = c.guide();
        if (!g.rules().isEmpty()) {
            u.append("<reglas>\n");
            g.rules().forEach(r -> u.append("- ").append(r).append('\n'));
            u.append("</reglas>\n\n");
        }
        if (!g.proposalFields().isBlank()) u.append(g.proposalFields().strip()).append("\n\n");
        u.append(documentInstruction(c));
        u.append(taskInstruction(c));
        return u.toString();
    }

    private static String documentInstruction(PoContext c) {
        PoContext.DocTask doc = c.docTask();
        if (doc == null) {
            return "No escribas ningún documento: deja `document` como cadena vacía. Igual clasifica el issue como SIMPLE o SUBSTANTIAL.\n";
        }
        StringBuilder out = new StringBuilder();
        if (c.existingDocContent() != null) {
            out.append("Ya existe una versión de «").append(doc.label()).append("». Mejórala conservando lo que siga siendo válido:\n")
                    .append("<documento_actual>\n").append(clip(c.existingDocContent())).append("\n</documento_actual>\n\n");
        }
        out.append(doc.substantialOnly()
                ? "Si el issue es SUBSTANTIAL, escribe en `document` «" + doc.label() + "» en Markdown. Si es SIMPLE, deja `document` como cadena vacía: esta metodología no pide documentos para tareas simples.\n"
                : "Escribe en `document` «" + doc.label() + "» completo en Markdown.\n");
        if (!c.guide().document().isBlank()) out.append(c.guide().document().strip()).append('\n');
        if (doc.instructions() != null && !doc.instructions().isBlank()) {
            out.append(doc.instructions().strip()).append('\n');
        }
        return out.toString();
    }

    private static String taskInstruction(PoContext c) {
        if (!c.splitsIntoTasks()) return "No repartas el issue en tareas: deja `tasks` como lista vacía.\n";
        StringBuilder out = new StringBuilder("\n").append(c.guide().taskSplit().isBlank() ? "Reparte la épica en `tasks`." : c.guide().taskSplit().strip())
                .append(" Va en `tasks`. Roles posibles:\n");
        c.taskRoles().forEach(r -> out.append("- ").append(r).append('\n'));
        return out.toString();
    }

    static String clip(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_DOC_CHARS) return text;
        return text.substring(0, MAX_DOC_CHARS) + "\n\n[… documento recortado: " + (text.length() - MAX_DOC_CHARS) + " caracteres más no se incluyeron …]";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** El documento que escribió el agente, para enlazarlo desde el comentario. */
    public record DocLink(String label, int version, String projectKey, java.util.UUID id, String path) {}

    /**
     * Comentario que deja el agente en el issue, armado por código a partir de la propuesta. Es corto: qué
     * entendió, lo que necesita de la persona y dónde leer el detalle. Si escribió un documento, la historia y
     * los criterios quedan solo ahí (no se repiten); si no, van aquí porque no hay otro lugar.
     */
    public static String comment(String agentName, PoProposal p, DocLink doc, String tasksNote, boolean pointsApplied, String closing,
            ec.kuador.board.catalog.AgentPolicy.Messages m) {
        StringBuilder c = new StringBuilder("**").append(agentName).append(":** ").append(p.understanding().strip()).append("\n\n");
        list(c, m.questionsTitle(), p.openQuestions(), true);
        if (doc != null) {
            String link = "[«" + doc.label() + "» v" + doc.version() + "](/projects/" + doc.projectKey() + "/docs/" + doc.id() + ") (`" + doc.path() + "`)";
            c.append(ec.kuador.board.catalog.AgentRules.fill(m.readDocument(), java.util.Map.of("documento", link, "criterios", p.acceptanceCriteria().size())))
                    .append("\n\n");
        } else {
            c.append("**Historia:** ").append(p.userStory().strip()).append("\n\n");
            list(c, "Criterios de aceptación", p.acceptanceCriteria(), true);
            list(c, "Fuera de alcance", p.outOfScope(), false);
        }
        if (tasksNote != null) c.append(tasksNote).append("\n\n");
        c.append("**Estimación:** ").append(p.storyPoints()).append(" pts")
                .append(pointsApplied ? "" : " (el issue ya tenía estimación; no la cambié)")
                .append(" · ").append(p.complexity() == PoProposal.Complexity.SIMPLE ? "simple" : "sustancial").append("\n\n");
        c.append(closing);
        return c.toString();
    }

    private static void list(StringBuilder c, String title, List<String> items, boolean numbered) {
        if (items == null || items.isEmpty()) return;
        c.append("**").append(title).append("**\n");
        for (int i = 0; i < items.size(); i++) {
            c.append(numbered ? (i + 1) + ". " : "- ").append(items.get(i).strip()).append('\n');
        }
        c.append('\n');
    }
}
