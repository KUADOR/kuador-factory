package ec.kuador.board.sync;

import java.util.Collection;
import java.util.List;

/**
 * Lo que cada repo trae para los agentes: el AGENTS.md y el kit de los clientes de IA. Lo implementa el
 * módulo de agentes locales (servidor MCP), que conoce el nombre del espacio y las herramientas; este
 * módulo solo lo escribe.
 */
public interface RepositoryInstructions {

    /** Un archivo del kit, con su ruta desde la raíz del repo. */
    record File(String path, String content) {}

    /** AGENTS.md genérico: cómo trabajar los pendientes del tablero desde cualquier cliente de IA. */
    String agentsMd();

    /**
     * Kit de agentes que vive en el repo (servidor MCP, comando, subagentes por rol, configuración de Codex),
     * sin secretos. {@code mcpServerKeys}: servidores MCP extra del catálogo que usan las piezas del proyecto.
     */
    default List<File> agentKit(Collection<String> mcpServerKeys) {
        return List.of();
    }
}
