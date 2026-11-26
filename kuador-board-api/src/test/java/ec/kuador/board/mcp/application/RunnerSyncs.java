package ec.kuador.board.mcp.application;

import ec.kuador.board.issue.BranchSyncApi;
import ec.kuador.board.sync.CodeReviewApi;

/** Para las pruebas: cambiar cómo el runner le pregunta a GitHub si un PR quedó sin conflictos. */
public final class RunnerSyncs {

    private RunnerSyncs() {}

    public static void use(RunnerService runners, BranchSyncApi syncs, CodeReviewApi code) {
        runners.setSyncs(syncs, code);
    }
}
