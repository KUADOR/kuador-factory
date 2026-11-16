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

    private Optional<StageHandoff.Next> next(String template, String column, MemberView agent) {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("B", template, null);
        IssueApi issues = new IssueApiStub() {
            @Override public Optional<IssueContext> context(String k) {
                return Optional.of(new IssueContext(k, "B", "t", null, "TASK", column, "HIGH", null, List.of(), null, agent.id(), null, List.of()));
            }
        };
        return new StageHandoff(issues, projects).next(agent, "B-1");
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
}
