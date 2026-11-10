package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Servidor MCP del catálogo (p. ej. Context7). Se asigna a plantillas y proyectos y termina en el
 * {@code .mcp.json} del repo. Las variables de entorno se guardan solo por nombre: los valores los pone
 * cada persona en su máquina, nunca el tablero ni el repo.
 */
public record McpServer(
        UUID id, String key, String name, String description, Transport transport, String command, List<String> args, String url,
        List<EnvVar> envVars, String docsUrl, boolean builtIn, long version) {

    public enum Transport {
        STDIO,
        HTTP
    }

    public record EnvVar(String name, boolean required, String description) {}
}
