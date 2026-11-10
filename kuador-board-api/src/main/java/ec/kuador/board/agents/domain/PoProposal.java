package ec.kuador.board.agents.domain;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Lo que devuelve el modelo para el Agente PO, como JSON validado contra este esquema (salida
 * estructurada). Qué hacer con la propuesta lo decide el código, no el modelo.
 */
public record PoProposal(
        @JsonPropertyDescription("Qué entendiste del pedido, en 1 a 3 frases, sin repetir el título.")
        String understanding,
        @JsonPropertyDescription("Historia de usuario: «Como <rol> quiero <capacidad> para <beneficio>».")
        String userStory,
        @JsonPropertyDescription("Criterios de aceptación verificables; cada uno en formato «Dado …, cuando …, entonces …».")
        List<String> acceptanceCriteria,
        @JsonPropertyDescription("Decisiones de producto que debe tomar una persona. Vacío si no hay ninguna. No incluyas dudas técnicas.")
        List<String> openQuestions,
        @JsonPropertyDescription("Lo que explícitamente queda fuera de este issue. Puede ser vacío.")
        List<String> outOfScope,
        @JsonPropertyDescription("Estimación en story points, uno de: 1, 2, 3, 5, 8, 13.")
        int storyPoints,
        @JsonPropertyDescription("SIMPLE si es un cambio acotado y de bajo riesgo; SUBSTANTIAL si requiere seguimiento.")
        Complexity complexity,
        @JsonPropertyDescription("Contenido Markdown completo del documento que se te pidió (spec o seguimiento). Cadena vacía si no se pidió ninguno.")
        String document) {

    public enum Complexity {
        SIMPLE,
        SUBSTANTIAL
    }
}
