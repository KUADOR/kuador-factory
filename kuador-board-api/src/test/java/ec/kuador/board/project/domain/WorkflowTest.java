package ec.kuador.board.project.domain;

import static ec.kuador.board.catalog.ColumnCategory.DONE;
import static ec.kuador.board.catalog.ColumnCategory.IN_PROGRESS;
import static ec.kuador.board.catalog.ColumnCategory.TODO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowTest {

    private static final List<ColumnDef> TASKS = InMemoryCatalog.factoryTemplate("TASKS").columns();

    @Test
    void everyTemplateStartsWithTodoAndEndsWithDone() {
        for (WorkflowTemplate t : InMemoryCatalog.FACTORY.workflowTemplates()) {
            assertThat(t.columns().getFirst().category()).as(t.key()).isEqualTo(TODO);
            assertThat(t.columns().getLast().category()).as(t.key()).isEqualTo(DONE);
        }
        assertThat(InMemoryCatalog.factoryTemplate("SALES").columns()).extracting(ColumnDef::name)
                .containsExactly("Prospecto", "Contactado", "Propuesta enviada", "Negociación", "Ganado", "Perdido");
    }

    @Test
    void renamingAndReorderingKeepKeys() {
        List<ColumnDef> result = Workflow.replace(TASKS, List.of(
                new Workflow.Draft("DOING", "En curso", IN_PROGRESS),
                new Workflow.Draft("TODO", "Pendiente", TODO),
                new Workflow.Draft("DONE", "Terminado", DONE)), k -> 0);

        assertThat(result).containsExactly(
                new ColumnDef("DOING", "En curso", IN_PROGRESS),
                new ColumnDef("TODO", "Pendiente", TODO),
                new ColumnDef("DONE", "Terminado", DONE));
    }

    @Test
    void newColumnsGetASlugKeyThatDoesNotCollide() {
        List<ColumnDef> result = Workflow.replace(TASKS, List.of(
                new Workflow.Draft("TODO", "Pendiente", TODO),
                new Workflow.Draft(null, "Revisión del cliente", IN_PROGRESS),
                new Workflow.Draft(null, "Done", DONE),
                new Workflow.Draft("DONE", "Listo", DONE)), k -> 0);

        assertThat(result).extracting(ColumnDef::key).containsExactly("TODO", "REVISION_DEL_CLIENTE", "DONE_2", "DONE");
    }

    @Test
    void cannotRemoveAColumnThatHasIssues() {
        Map<String, Long> issues = Map.of("DOING", 2L);
        List<Workflow.Draft> withoutDoing = List.of(new Workflow.Draft("TODO", "Pendiente", TODO), new Workflow.Draft("DONE", "Listo", DONE));

        assertThatThrownBy(() -> Workflow.replace(TASKS, withoutDoing, k -> issues.getOrDefault(k, 0L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Haciendo").hasMessageContaining("2 issues");
        assertThat(Workflow.replace(TASKS, withoutDoing, k -> 0)).hasSize(2);
    }

    @Test
    void rejectsInvalidWorkflows() {
        assertThatThrownBy(() -> Workflow.replace(TASKS, List.of(), k -> 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Workflow.replace(TASKS, List.of(
                new Workflow.Draft(null, "A", TODO), new Workflow.Draft(null, "a", DONE)), k -> 0))
                .hasMessageContaining("dos columnas");
        assertThatThrownBy(() -> Workflow.replace(TASKS, List.of(new Workflow.Draft("WON", "Ganado", DONE)), k -> 0))
                .hasMessageContaining("no existe");
        assertThatThrownBy(() -> Workflow.replace(TASKS, List.of(new Workflow.Draft(null, " ", TODO)), k -> 0))
                .isInstanceOf(DomainException.class);
    }
}
