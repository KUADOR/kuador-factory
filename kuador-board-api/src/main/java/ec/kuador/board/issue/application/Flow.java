package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Optional;

/** Lo que el flujo de un tablero dice de sus columnas: cuál termina, cuál es el release, a cuál se vuelve. Sin estado. */
final class Flow {

    private Flow() {}

    /** Etapa de trabajo: tiene roles y no es una puerta (p. ej. Desarrollo). */
    static boolean workStage(ProjectView project, String column) {
        return project.stage(column).map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false);
    }

    static Optional<ColumnDef> firstDone(ProjectView project) {
        return project.columns().stream().filter(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).findFirst();
    }

    /** La etapa de release: una puerta justo antes de la primera columna de terminados (p. ej. Release). */
    static Optional<String> releaseColumn(ProjectView project) {
        return firstDone(project).flatMap(done -> {
            int i = project.indexOf(done.key());
            if (i <= 0) return Optional.empty();
            String before = project.columns().get(i - 1).key();
            return project.stage(before).filter(StageDef::gate).map(st -> before);
        });
    }

    static boolean isDone(ProjectView project, String column) {
        return project.column(column).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(false);
    }

    /**
     * Una dependencia libera a las demás cuando llega a la última puerta del flujo (en software, Release: su PR ya pasó QA
     * y la revisión) o a una columna terminada. Esperar a «Hecho» encadenaba cada tarea al despliegue de la anterior.
     * Sin puertas, vale «Hecho».
     */
    static boolean readyForDependents(ProjectView project, String column) {
        if (isDone(project, column)) return true;
        List<String> order = project.columns().stream().map(ColumnDef::key).toList();
        int lastGate = -1;
        for (int i = 0; i < order.size(); i++) {
            if (project.stage(order.get(i)).map(StageDef::gate).orElse(false)) lastGate = i;
        }
        int at = order.indexOf(column);
        return lastGate >= 0 && at >= lastGate;
    }

    static Optional<String> previousColumn(ProjectView project, String from) {
        int i = project.indexOf(from);
        for (int j = Math.min(i, project.columns().size()) - 1; j >= 0; j--) {
            String k = project.columns().get(j).key();
            if (project.canMove(from, k)) return Optional.of(k);
        }
        return Optional.empty();
    }

    static String columnName(ProjectView project, String key) {
        return project.column(key).map(ColumnDef::name).orElse(key);
    }

    static ColumnDef requireColumn(ProjectView project, String columnKey) {
        return project.column(columnKey).orElseThrow(() -> new DomainException(
                "El paso " + columnKey + " no existe en el flujo de " + project.key() + "."));
    }}
