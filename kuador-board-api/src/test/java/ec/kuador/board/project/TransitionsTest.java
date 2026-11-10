package ec.kuador.board.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitionsTest {

    private static final List<ColumnDef> FLOW = List.of(
            new ColumnDef("TODO", "Por hacer", ColumnCategory.TODO),
            new ColumnDef("DOING", "En curso", ColumnCategory.IN_PROGRESS),
            new ColumnDef("QA", "En QA", ColumnCategory.IN_PROGRESS),
            new ColumnDef("DONE", "Hecho", ColumnCategory.DONE));

    private static Transitions.Transition t(String from, String to) {
        return new Transitions.Transition(from, to);
    }

    @Test
    void freeFlowAllowsEverythingAndSameColumnIsAlwaysAllowed() {
        assertThat(Transitions.FREE.allows("TODO", "DONE")).isTrue();
        Transitions strict = new Transitions(true, List.of(t("TODO", "DOING")));
        assertThat(strict.allows("QA", "QA")).as("reordenar en la misma columna").isTrue();
    }

    @Test
    void restrictedFlowOnlyAllowsTheConfiguredMoves() {
        Transitions jira = new Transitions(true, List.of(t("TODO", "DOING"), t("DOING", "QA"), t("QA", "DONE"), t("QA", "DOING")));
        assertThat(jira.allows("TODO", "DOING")).isTrue();
        assertThat(jira.allows("TODO", "DONE")).isFalse();
        assertThat(jira.targets("QA", FLOW)).containsExactly("DOING", "DONE");
    }

    @Test
    void validationRejectsUnknownColumnsAndUnreachableColumns() {
        assertThatThrownBy(() -> new Transitions(true, List.of(t("TODO", "NOPE"))).validatedFor(FLOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("no existe");
        assertThatThrownBy(() -> new Transitions(true, List.of(t("TODO", "DOING"), t("DOING", "QA"))).validatedFor(FLOW))
                .hasMessageContaining("«Hecho»");
        assertThat(new Transitions(false, List.of()).validatedFor(FLOW).restricted()).isFalse();
    }

    @Test
    void removingAColumnDropsItsRules() {
        Transitions rules = new Transitions(true, List.of(t("TODO", "QA"), t("QA", "DONE")));
        assertThat(rules.prunedTo(FLOW.stream().filter(c -> !c.key().equals("QA")).toList()).allowed()).isEmpty();
    }
}
