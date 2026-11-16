package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Rol de agente: su manual. {@code instructions} es la base del prompt (también se usa para generar
 * las instrucciones de los clientes de IA); {@code tools} las herramientas que puede usar; {@code automation}
 * el comportamiento que el tablero sabe ejecutar para este rol.
 */
public record AgentRole(
        UUID id, String key, String name, String description, String instructions, List<String> tools, Automation automation,
        boolean builtIn, long version) {

    /** Herramientas que cambian o ejecutan el código del repo. */
    private static final java.util.Set<String> CODE_TOOLS = java.util.Set.of("Bash", "Edit", "Write");

    /**
     * El rol trabaja sobre el código del repo (lo edita o ejecuta sus pruebas y comandos): necesita el repo, el entorno
     * y la red de una PC, así que solo trabaja en modo local; en el tablero no tendría con qué.
     */
    public boolean worksOnCode() {
        return tools != null && tools.stream().anyMatch(CODE_TOOLS::contains);
    }

    public enum Automation {
        NONE,
        /** Refinar un issue: historia, criterios, preguntas, estimación y el documento de su metodología. */
        REFINE_ISSUE
    }
}
