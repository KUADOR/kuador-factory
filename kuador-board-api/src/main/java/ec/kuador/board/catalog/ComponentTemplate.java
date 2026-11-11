package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Plantilla de una pieza de software (una API .NET, una web Angular, un microservicio Spring, un
 * microfrontend React...). Un proyecto se arma con una o varias piezas, en un monorepo o en un repo
 * por pieza. El tablero no conoce ningún stack: copia el código base de {@code templateRepo} en
 * {@code templateRef} (si lo hay), reemplaza las variables y deja los comandos para agentes y CI.
 *
 * @param templateRepo repo plantilla en GitHub ("owner/repo"); nulo = la pieza nace solo con su README
 * @param templateRef rama, etiqueta o commit fijo de la plantilla (la versión con la que nace cada pieza)
 * @param variables valores que se piden al crear la pieza; en la plantilla se escriben {{NOMBRE}}
 * @param ciChecks verificaciones de CI que la pieza exige antes de fusionar
 */
public record ComponentTemplate(
        UUID id, String key, String name, String description, Kind kind, String stack, String templateRepo, String templateRef,
        List<Variable> variables, Commands commands, List<String> ciChecks, List<String> suggestedRoles, List<String> mcpServers,
        boolean builtIn, long version) {

    public enum Kind {
        BACKEND,
        FRONTEND,
        MICROFRONTEND,
        SHELL,
        MOBILE,
        LIBRARY,
        OTHER
    }

    /** Variable de la plantilla: {{NOMBRE}} en archivos y rutas. */
    public record Variable(String name, String description, String defaultValue) {}

    /** Cómo se compila, prueba y levanta la pieza (lo leen los agentes y el CI). */
    public record Commands(String build, String test, String run) {}

    /** Variables que pone el tablero sin preguntar. */
    public static final List<String> BUILT_IN_VARIABLES = List.of("PROYECTO", "PROYECTO_CLAVE", "PIEZA");
}
