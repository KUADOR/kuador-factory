package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.IssueApiStub;
import ec.kuador.board.testing.TestTenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A dónde pasa la tarjeta cuando un agente termina, en los tableros de ventas y de tareas. */
class StageHandoffTest {

    static final MemberView ANALYST = new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, "Analista", true, "ANALYST", "#fff", null);
    static final MemberView MARKETING = new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, "Marketing", true, "MARKETING", "#fff", null);
    static final MemberView DESIGNER = new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, "Diseño", true, "DESIGNER", "#fff", null);
    static final MemberView QA = new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, "QA", true, "QA", "#fff", null);

    private Optional<StageHandoff.Next> next(String template, String column, MemberView agent) {
        return handoff(template, column, agent).next(agent, "B-1");
    }

    private StageHandoff handoff(String template, String column, MemberView agent) {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("B", template, null);
        IssueApi issues = new IssueApiStub() {
            @Override public Optional<IssueContext> context(String k) {
                return Optional.of(new IssueContext(k, "B", "t", null, "TASK", column, "HIGH", null, List.of(), null, agent.id(), null, List.of()));
            }
        };
        return new StageHandoff(issues, projects);
    }

    @Test
    void inTheTasksBoardWhatAnAgentDeliversGoesToReview() {
        assertThat(next("TASKS", "DOING", ANALYST)).hasValueSatisfying(n -> {
            assertThat(n.column()).isEqualTo("REVIEW");
            assertThat(n.gate()).isTrue();
        });
        assertThat(next("TASKS", "TODO", ANALYST)).as("asignada en Pendiente: también va a Revisión").map(StageHandoff.Next::column).hasValue("REVIEW");
        assertThat(next("TASKS", "DONE", ANALYST)).isEmpty();
    }

    @Test
    void inTheSalesBoardEachAgentPreparesAndAPersonActs() {
        assertThat(next("SALES", "RESEARCH", ANALYST)).as("la ficha lista: una persona contacta").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Contactado");
            assertThat(n.gate()).isFalse();
            assertThat(n.agents()).isFalse();
        });
        assertThat(next("SALES", "PROPOSAL_DRAFT", MARKETING)).as("la propuesta va a revisión").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Revisión de propuesta");
            assertThat(n.gate()).isTrue();
        });
        assertThat(next("SALES", "PROSPECT", ANALYST)).as("lo que sigue es trabajo de una etapa, no una revisión: vuelve a la persona").isEmpty();
        assertThat(next("SALES", "PROPOSAL_DRAFT", ANALYST)).as("no es la etapa de su rol").isEmpty();
    }

    @Test
    void inTheMarketingBoardThePieceGoesFromBriefToCopyToDesignAndThenToAPerson() {
        assertThat(next("MARKETING", "BRIEF", MARKETING)).as("el brief listo: Marketing redacta").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Redacción");
            assertThat(n.agents()).isTrue();
        });
        assertThat(next("MARKETING", "COPY", MARKETING)).as("el texto listo: Diseño hace las imágenes").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Diseño");
            assertThat(n.agents()).isTrue();
        });
        assertThat(next("MARKETING", "DESIGN", DESIGNER)).as("las imágenes listas: una persona aprueba").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Revisión");
            assertThat(n.gate()).isTrue();
        });
        assertThat(next("MARKETING", "MEASURE", ANALYST)).as("medida: queda cerrada").hasValueSatisfying(n -> {
            assertThat(n.columnName()).isEqualTo("Medido");
            assertThat(n.agents()).isFalse();
        });
        assertThat(handoff("MARKETING", "COPY", MARKETING).back(MARKETING, "B-1")).as("Redacción devuelve al brief")
                .map(StageHandoff.Back::column).hasValue("BRIEF");
    }

    @Test
    void qaSendsBackToTheDevelopmentStage() {
        assertThat(handoff("SOFTWARE_FACTORY", "QA", QA).back(QA, "B-1")).as("de QA vuelve a desarrollo")
                .map(StageHandoff.Back::column).hasValue("DEVELOPMENT");
        assertThat(handoff("SOFTWARE_FACTORY", "DEVELOPMENT", QA).back(QA, "B-1")).as("no es la etapa de su rol").isEmpty();
        assertThat(handoff("SALES", "RESEARCH", ANALYST).back(ANALYST, "B-1")).as("la primera etapa de trabajo: no hay a dónde devolver").isEmpty();
        assertThat(handoff("SALES", "PROPOSAL_DRAFT", MARKETING).back(MARKETING, "B-1")).map(StageHandoff.Back::columnName).hasValue("Investigación");
    }

    @Test
    void theStepsDecisionsComeFromTheBoardsConditionalPaths() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("B", "SALES", null);
        IssueApi issues = new IssueApiStub() {
            @Override public Optional<IssueContext> context(String k) {
                return Optional.of(new IssueContext(k, "B", "t", null, "TASK", "RESEARCH", "HIGH", null, List.of(), null, ANALYST.id(), null, List.of()));
            }
        };
        StageHandoff handoff = new StageHandoff(issues, projects);
        assertThat(handoff.choices(ANALYST, "B-1")).as("sin caminos con condición no hay decisión: sigue al siguiente").isEmpty();

        projects.restrict("B", new ec.kuador.board.project.Transitions.Transition("RESEARCH", "LOST", "si el prospecto no califica"),
                new ec.kuador.board.project.Transitions.Transition("RESEARCH", "PROSPECT", "si faltan datos de contacto"),
                new ec.kuador.board.project.Transitions.Transition("RESEARCH", "CONTACTED"));
        List<StageHandoff.Next> choices = handoff.choices(ANALYST, "B-1");
        assertThat(choices).extracting(StageHandoff.Next::columnName).as("las alternativas y el camino normal")
                .containsExactly("Perdido", "Prospecto", "Contactado");
        assertThat(choices).extracting(StageHandoff.Next::when).containsExactly("si el prospecto no califica", "si faltan datos de contacto", null);
        assertThat(choices.get(1).back(projects.find("B").orElseThrow(), "RESEARCH")).as("volver a un paso anterior es un bucle").isTrue();
        assertThat(handoff.choices(MARKETING, "B-1")).as("no es la etapa de su rol").isEmpty();
    }

    @Test
    void toolArgumentNamesMustBeOnesEveryAiProviderAccepts() {
        assertThat(BoardAgent.validArguments(java.util.Map.of("type", "object", "properties", java.util.Map.of("numero_factura", java.util.Map.of())))).isTrue();
        assertThat(BoardAgent.validArguments(java.util.Map.of("type", "object", "properties", java.util.Map.of("Número de factura", java.util.Map.of())))).isFalse();
        assertThat(BoardAgent.validArguments(java.util.Map.of("type", "object"))).isTrue();
    }
}
