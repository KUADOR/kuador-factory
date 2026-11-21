package ec.kuador.board.agents.application;

import ec.kuador.board.sync.CodeReviewApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * El CI de los PR de una tarjeta antes de que la revise el agente revisor. El agente revisa solo con el CI terminado:
 * mientras corre, la tarjeta espera en la puerta (sin gastar tokens ni avisar a nadie); si queda en rojo, el tablero la
 * devuelve sin llamar al modelo. Lo que no se puede comprobar aquí (sin repos, sin PR, CI ilegible) sigue al revisor y a
 * las comprobaciones de siempre antes de aprobar.
 */
final class GateCi {

    /** Cuánto espera la puerta a que termine el CI antes de dejar la tarjeta a una persona. */
    static final Duration MAX_WAIT = Duration.ofMinutes(60);
    /** Un PR recién subido puede no tener checks todavía: se espera esto antes de tratarlo como un repo sin CI. */
    static final Duration NO_CHECKS_GRACE = Duration.ofMinutes(10);

    static final Set<String> FAILED = Set.of("failure", "timed_out", "cancelled", "action_required", "startup_failure", "error");
    static final Set<String> PASSED = Set.of("success", "neutral", "skipped");

    /**
     * Archivos que no cuentan para el tamaño del cambio: los genera una herramienta (lockfiles, código generado, snapshots)
     * y nadie los revisa línea por línea.
     */
    static final Pattern GENERATED = Pattern.compile("(?i)(^|/)("
            + "package-lock\\.json|npm-shrinkwrap\\.json|yarn\\.lock|pnpm-lock\\.yaml|bun\\.lockb?|pubspec\\.lock|Podfile\\.lock|Gemfile\\.lock"
            + "|poetry\\.lock|Pipfile\\.lock|uv\\.lock|Cargo\\.lock|go\\.sum|composer\\.lock|gradle\\.lockfile|packages\\.lock\\.json"
            + "|[^/]+\\.(g|freezed|gr|mocks)\\.dart|[^/]+\\.min\\.(js|css)|[^/]+\\.snap|[^/]+\\.map)$");

    enum Status {
        /** Todo en verde, o nada que esperar aquí: sigue la revisión. */
        READY,
        /** Algún check sigue corriendo. */
        RUNNING,
        /** El PR todavía no tiene checks (puede que el CI no haya arrancado). */
        NO_CHECKS,
        /** Algún check falló. */
        FAILED
    }

    record State(Status status, String detail) {}

    /** Lo que hace la puerta con ese estado, según cuánto lleva esperando. */
    enum Decision {
        REVIEW, WAIT, RETURN, ESCALATE
    }

    private GateCi() {}

    static boolean generated(String path) {
        return path != null && GENERATED.matcher(path).find();
    }

    /** El CI de los PR abiertos de la tarjeta. Si no hay repos, PR o permiso para leer el CI, no hay nada que esperar. */
    static State of(CodeReviewApi code, String projectKey, String issueKey) {
        List<CodeReviewApi.PullDetail> pulls = new ArrayList<>();
        try {
            if (code.repositories(projectKey).isEmpty()) return new State(Status.READY, null);
            for (CodeReviewApi.PullSummary p : code.pullRequests(projectKey, issueKey)) {
                if (p.merged() || !"open".equalsIgnoreCase(p.state())) continue;
                pulls.add(code.pullRequest(projectKey, p.repository(), p.number()));
            }
        } catch (RuntimeException e) {
            return new State(Status.READY, null); // el revisor lo vuelve a intentar y, si sigue fallando, decide una persona
        }
        return of(pulls);
    }

    static State of(List<CodeReviewApi.PullDetail> pulls) {
        List<String> failed = new ArrayList<>();
        List<String> running = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (CodeReviewApi.PullDetail d : pulls) {
            String pr = d.pull().repository() + " #" + d.pull().number();
            if (d.checksNote() != null) continue; // sin permiso para leer el CI: lo resuelve una persona después de la revisión
            if (d.checks().isEmpty()) empty.add(pr);
            for (CodeReviewApi.Check c : d.checks()) {
                String result = c.conclusion() == null ? null : c.conclusion().toLowerCase(Locale.ROOT);
                if (result != null && FAILED.contains(result)) failed.add(pr + ": " + c.name());
                else if (result == null || !PASSED.contains(result)) running.add(pr + ": " + c.name());
            }
        }
        if (!failed.isEmpty()) return new State(Status.FAILED, String.join(", ", failed));
        if (!running.isEmpty()) return new State(Status.RUNNING, String.join(", ", running));
        if (!empty.isEmpty()) return new State(Status.NO_CHECKS, String.join(", ", empty));
        return new State(Status.READY, null);
    }

    /** {@code since}: desde cuándo espera la tarjeta en la puerta (o ahora, si recién llega). */
    static Decision decide(State state, Instant since, Instant now) {
        Duration waited = Duration.between(since, now);
        return switch (state.status()) {
            case READY -> Decision.REVIEW;
            case FAILED -> Decision.RETURN;
            case RUNNING -> waited.compareTo(MAX_WAIT) < 0 ? Decision.WAIT : Decision.ESCALATE;
            // Un repo sin CI no espera más que el margen de arranque: lo revisa el agente y, sin CI, decide una persona.
            case NO_CHECKS -> waited.compareTo(NO_CHECKS_GRACE) < 0 ? Decision.WAIT : Decision.REVIEW;
        };
    }
}
