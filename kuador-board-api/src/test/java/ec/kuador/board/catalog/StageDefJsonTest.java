package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Etapas guardadas antes de un campo nuevo (plantillas del catálogo, tableros existentes) se siguen leyendo con el
 * lector JSON por defecto: lo que falta toma su valor por defecto en vez de romper la creación de tableros.
 */
class StageDefJsonTest {

    @Test
    void stagesSavedBeforeAutoPlanStillLoad() {
        String saved = "[{\"column\":\"RESEARCH\",\"roles\":[\"ANALYST\"],\"gate\":false,\"approvers\":[],\"rejectTo\":null},"
                + "{\"column\":\"PROPOSAL_REVIEW\",\"roles\":[],\"gate\":true,\"approvers\":[],\"rejectTo\":\"PROPOSAL_DRAFT\",\"autoPlan\":null}]";
        List<StageDef> stages = JsonMapper.builder().build().readValue(saved, new TypeReference<List<StageDef>>() {});
        assertThat(stages).extracting(StageDef::autoPlan).containsExactly(false, false);

        StageDef work = JsonMapper.builder().build().readValue("{\"column\":\"DEV\",\"roles\":[\"BACKEND\"],\"gate\":false,\"autoPlan\":true}", StageDef.class);
        assertThat(work.autoPlan()).isTrue();
        StageDef gate = JsonMapper.builder().build().readValue("{\"column\":\"QA\",\"roles\":[],\"gate\":true,\"autoPlan\":true}", StageDef.class);
        assertThat(gate.autoPlan()).as("una puerta no reparte tareas").isFalse();
    }
}
