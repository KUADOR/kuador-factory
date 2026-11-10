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
        u.append(documentInstruction(c));
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
        if (doc.instructions() != null && !doc.instructions().isBlank()) {
            out.append(doc.instructions().strip()).append('\n');
        }
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

    /** Comentario que deja el agente en el issue, armado por código a partir de la propuesta. */
    public static String comment(String agentName, PoProposal p, String docNote, String requesterName, boolean pointsApplied) {
        StringBuilder c = new StringBuilder("**Propuesta de ").append(agentName).append("**\n\n> ").append(p.understanding().strip()).append("\n\n");
        c.append("**Historia:** ").append(p.userStory().strip()).append("\n\n");
        list(c, "Criterios de aceptación", p.acceptanceCriteria(), true);
        list(c, "Preguntas para decidir", p.openQuestions(), false);
        list(c, "Fuera de alcance", p.outOfScope(), false);
        c.append("**Estimación:** ").append(p.storyPoints()).append(" pts")
                .append(pointsApplied ? " (la puse en el issue)" : " (el issue ya tenía estimación; no la cambié)")
                .append(" · **Complejidad:** ").append(p.complexity() == PoProposal.Complexity.SIMPLE ? "simple" : "sustancial").append("\n\n");
        if (docNote != null) c.append(docNote).append("\n\n");
        c.append(p.openQuestions().isEmpty()
                ? "Te lo devuelvo" + (requesterName == null ? "" : ", " + requesterName) + ", para que lo revises y, si estás de acuerdo, lo muevas a la siguiente columna."
                : "Te lo devuelvo" + (requesterName == null ? "" : ", " + requesterName) + ": necesito que respondas las preguntas antes de seguir.");
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
