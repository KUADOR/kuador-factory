package ec.kuador.board.shared.config;

/** Nombres de los ejecutores con hilos propios, para {@code @Async("...")}. Sin nombre, el trabajo va al de eventos. */
public final class AsyncExecutors {

    public static final String AGENTS = "agentsExecutor";
    public static final String NOTICES = "noticesExecutor";

    private AsyncExecutors() {}
}
