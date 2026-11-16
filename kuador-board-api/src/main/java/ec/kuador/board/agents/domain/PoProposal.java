package ec.kuador.board.agents.domain;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Lo que devuelve el modelo para el Agente PO, como JSON validado contra este esquema (salida
 * estructurada). Qué hacer con la propuesta lo decide el código, no el modelo.
 */
public record PoProposal(
        @JsonPropertyDescription("Resumen para la persona, en 2 a 3 frases: qué entendiste y qué propones, sin repetir el título. Va en el comentario; el detalle va en el documento.")
        String understanding,
        @JsonPropertyDescription("Historia de usuario: «Como <rol> quiero <capacidad> para <beneficio>».")
        String userStory,
        @JsonPropertyDescription("Criterios de aceptación verificables; cada uno en formato «Dado …, cuando …, entonces …».")
        List<String> acceptanceCriteria,
        @JsonPropertyDescription("Solo decisiones nuevas que debe tomar una persona y que nadie respondió en los comentarios. Vacío si no hay ninguna. No repitas preguntas ya respondidas, no incluyas dudas técnicas ni pidas aprobaciones formales o firmas (eso va en el documento como pendiente).")
        List<String> openQuestions,
        @JsonPropertyDescription("Lo que explícitamente queda fuera de este issue. Puede ser vacío.")
        List<String> outOfScope,
        @JsonPropertyDescription("Estimación en story points, uno de: 1, 2, 3, 5, 8, 13.")
        int storyPoints,
        @JsonPropertyDescription("SIMPLE si es un cambio acotado y de bajo riesgo; SUBSTANTIAL si requiere seguimiento.")
        Complexity complexity,
        @JsonPropertyDescription("Contenido Markdown completo del documento que se te pidió (spec o seguimiento). Cadena vacía si no se pidió ninguno.")
        String document,
        @JsonPropertyDescription("Solo si se te pide repartir la épica: una tarea por cada tarea del plan, en orden. Lista vacía en cualquier otro caso.")
        List<PlannedTask> tasks) {

    /** Una tarea del plan de una épica: la trabaja el rol indicado, en su propia rama y PR. */
    public record PlannedTask(
            @JsonPropertyDescription("Título corto de la tarea.")
            String title,
            @JsonPropertyDescription("Clave del rol que la trabaja, de la lista que se te dio (p. ej. BACKEND).")
            String role,
            @JsonPropertyDescription("Qué hacer: archivos o módulos, criterio de terminado y las líneas del plan que cubre.")
            String description,
            @JsonPropertyDescription("Números (desde 1) de tareas de esta misma lista que deben terminar antes. Vacía si no depende de ninguna.")
            List<Integer> dependsOn) {}

    /** Sin tareas (todo lo que no es el plan de una épica). */
    public PoProposal(String understanding, String userStory, List<String> acceptanceCriteria, List<String> openQuestions, List<String> outOfScope,
            int storyPoints, Complexity complexity, String document) {
        this(understanding, userStory, acceptanceCriteria, openQuestions, outOfScope, storyPoints, complexity, document, List.of());
    }

    public enum Complexity {
        SIMPLE,
        SUBSTANTIAL
    }
}
