package ec.kuador.board.sync;

/**
 * Lo que cada repo trae para los agentes (AGENTS.md). Lo implementa el módulo de agentes locales
 * (servidor MCP), que conoce el nombre del espacio y las herramientas; este módulo solo lo escribe.
 */
public interface RepositoryInstructions {

    /** AGENTS.md genérico: cómo trabajar los pendientes del tablero desde cualquier cliente de IA. */
    String agentsMd();
}
