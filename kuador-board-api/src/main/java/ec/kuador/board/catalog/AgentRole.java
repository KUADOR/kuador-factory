package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Rol de agente: su manual. {@code instructions} es la base del prompt (también se usa para generar
 * el subagente de Claude Code); {@code tools} las herramientas que puede usar; {@code automation}
 * el comportamiento que el tablero sabe ejecutar para este rol.
 */
public record AgentRole(
        UUID id, String key, String name, String description, String instructions, List<String> tools, Automation automation,
        boolean builtIn, long version) {

    public enum Automation {
        NONE,
        /** Refinar un issue: historia, criterios, preguntas, estimación y el documento de su metodología. */
        REFINE_ISSUE
    }
}
