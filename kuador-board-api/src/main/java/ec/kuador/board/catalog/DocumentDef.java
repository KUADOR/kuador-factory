package ec.kuador.board.catalog;

/**
 * Un documento que pide una metodología.
 *
 * @param kind clave del tipo (SPEC, CONSTITUTION...), única dentro de la metodología
 * @param pathPattern ruta en el repositorio. Los documentos de issue usan {feature} (NNN-nombre,
 *     estilo Spec Kit) o {task} (clave-nombre); los del proyecto, una ruta fija
 * @param template contenido inicial en Markdown; admite {project}, {key}, {title} y {date}
 * @param writtenBy rol de agente que lo escribe (p. ej. PRODUCT_OWNER); nulo si nadie lo automatiza
 * @param condition cuándo lo escribe el agente
 * @param agentInstructions cómo debe escribirlo el agente (estructura y criterio)
 */
public record DocumentDef(
        String kind, String label, Scope scope, boolean required, String purpose, String pathPattern, String template,
        String writtenBy, Condition condition, String agentInstructions) {

    public enum Scope {
        PROJECT,
        ISSUE
    }

    public enum Condition {
        /** Siempre que el agente trabaja el issue. */
        ALWAYS,
        /** Solo si el issue es sustancial (proporcionalidad, como pide ODD). */
        SUBSTANTIAL
    }
}
