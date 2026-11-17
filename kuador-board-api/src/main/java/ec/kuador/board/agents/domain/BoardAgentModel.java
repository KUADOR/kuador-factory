package ec.kuador.board.agents.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Puerto: el modelo con el que trabaja un agente en el tablero, de un proveedor de IA. Recibe el pedido y los archivos
 * de la tarjeta, trabaja con las herramientas que tenga (ejecución de código en Claude; lectura de archivos, consultas
 * y documentos del tablero en los demás) y termina entregando (con archivos) o preguntando a una persona.
 */
public interface BoardAgentModel {

    record InputFile(String name, String contentType, byte[] content) {}

    record OutputFile(String name, String contentType, byte[] content) {}

    /**
     * Una herramienta del tablero que el agente puede llamar a mitad del trabajo (p. ej. crear las tareas de una épica).
     * {@code parameters}: JSON Schema de sus argumentos. {@code run} devuelve el texto para el modelo; si lanza una
     * excepción, su mensaje vuelve al modelo como error para que corrija.
     */
    record BoardTool(String name, String description, Map<String, Object> parameters, Function<Map<String, Object>, String> run) {}

    /**
     * {@code system}: quién es el agente y las reglas del tablero; cada proveedor suma cómo es su entorno.
     * {@code tools}: herramientas del tablero además de las de cada proveedor. {@code deliverCheck}: antes de aceptar
     * la entrega; devuelve el motivo para no aceptarla (vuelve al modelo como error) o {@code null} si puede entregar.
     * {@code webSearch}: puede buscar y leer páginas en Internet (si su proveedor lo ofrece). {@code sendBackTo}: el nombre
     * de la columna a la que puede devolver el trabajo con {@code send_back} (p. ej. QA a «En desarrollo»), o {@code null}.
     * {@code codeExecution}: le da un entorno para ejecutar código (Claude); sin él ahorra tokens y tiempo de contenedor.
     * Con archivos en la tarjeta va siempre, porque se leen ahí. {@code effort}: cuánto razona (low, medium, high, xhigh,
     * max) o {@code null} para el del proveedor.
     */
    record Task(String system, String user, List<InputFile> files, List<BoardTool> tools, Supplier<String> deliverCheck, boolean webSearch,
            String sendBackTo, Limits limits, boolean codeExecution, String effort) {

        public Task {
            limits = limits == null ? Limits.DEFAULT : limits;
            codeExecution = codeExecution || (files != null && !files.isEmpty());
        }

        public Task(String system, String user, List<InputFile> files, List<BoardTool> tools, Supplier<String> deliverCheck, boolean webSearch,
                String sendBackTo, Limits limits) {
            this(system, user, files, tools, deliverCheck, webSearch, sendBackTo, limits, true, null);
        }

        public Task(String system, String user, List<InputFile> files) {
            this(system, user, files, List.of(), () -> null, false, null, Limits.DEFAULT);
        }

        public Task(String system, String user, List<InputFile> files, List<BoardTool> tools, Supplier<String> deliverCheck) {
            this(system, user, files, tools, deliverCheck, false, null, Limits.DEFAULT);
        }

        /** La descripción de send_back para el modelo. */
        public String sendBackDescription() {
            return "Devuelve el trabajo a «" + sendBackTo + "» porque no cumple: criterios de aceptación que fallan, CI en rojo o "
                    + "errores. Termina tu trabajo; quien lo hizo lo corrige y vuelve a pasar por tu etapa.";
        }

        /** El motivo para no aceptar la entrega, o {@code null}. */
        public String blocksDelivery() {
            return deliverCheck == null ? null : deliverCheck.get();
        }

        public java.util.Optional<BoardTool> tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
    }

    /** Perillas del espacio (Catálogo → Reglas de agentes): vueltas máximas según el proveedor y búsquedas web. */
    record Limits(int turnsClaude, int turnsOther, int webSearches) {
        public static final Limits DEFAULT = new Limits(12, 30, 8);
    }

    /** A dónde y con qué clave se habla. */
    record Endpoint(String baseUrl, String apiKey) {
        /** Nunca la clave en un log. */
        @Override
        public String toString() {
            return "Endpoint[" + baseUrl + "]";
        }
    }

    enum Outcome {
        DELIVERED,
        QUESTION,
        /** Devolvió el trabajo a la etapa anterior ({@link Task#sendBackTo()}). */
        SENT_BACK,
        /**
         * Terminó con texto, sin llamar a deliver, send_back ni ask_human. En una etapa cuenta como entrega; en una
         * puerta nunca aprueba (el texto puede decir «la rechazo» o venir de una inyección): pasa a una persona.
         */
        ANSWERED
    }

    /** {@code text}: el resumen de la entrega, la pregunta para la persona o qué hay que corregir. */
    record Result(Outcome outcome, String text, List<OutputFile> files, String model, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheWriteTokens) {

        /** Sin caché de prompts (proveedores que no lo informan). */
        public Result(Outcome outcome, String text, List<OutputFile> files, String model, long inputTokens, long outputTokens) {
            this(outcome, text, files, model, inputTokens, outputTokens, 0, 0);
        }
    }

    /** El protocolo que habla este adaptador. */
    AiProvider.Protocol protocol();

    /** Comprueba que la clave sirve; lanza {@link AgentModelException} con un motivo claro si no. */
    void checkKey(Endpoint endpoint);

    /** Lanza {@link AgentModelException} si el modelo falla, declina o no llega a entregar. */
    Result work(Endpoint endpoint, String model, Task task);
}
