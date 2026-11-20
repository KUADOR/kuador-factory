package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.sync.PullMerged;
import ec.kuador.board.testing.IssueApiStub;
import ec.kuador.board.testing.TestTenant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Tras una fusión, el PR que quedó en conflicto devuelve su tarjeta; los demás no se tocan. */
@ExtendWith(TestTenant.class)
class ConflictWatcherTest {

    private static final UUID OWNER = UUID.randomUUID();

    private final Map<Integer, Deque<Optional<Boolean>>> mergeable = new HashMap<>();
    private final List<String> returned = new ArrayList<>();

    private static CodeReviewApi.PullSummary pr(int n, String title, String branch, String base, String state) {
        return new CodeReviewApi.PullSummary("acme/kua", n, title, branch, base, state, false, "agente", null, null, null);
    }

    private final List<CodeReviewApi.PullSummary> pulls = List.of(
            pr(7, "KUA-1 Login", "feature/KUA-1-login", "develop", "closed"),
            pr(8, "Pantalla de pagos", "feature/kua-2-pagos", "develop", "open"),
            pr(9, "KUA-3 Reportes", "feature/KUA-3-reportes", "develop", "open"),
            pr(10, "KUA-4 Hotfix", "hotfix/KUA-4", "main", "open"),
            pr(11, "OTRO-5 De otro tablero", "feature/OTRO-5", "develop", "open"));

    private ConflictWatcher watcher() {
        CodeReviewApi code = new CodeReviewApi() {
            public List<String> repositories(String p) { return List.of("acme/kua"); }

            public List<PullSummary> pullRequests(String p, String search) { return pulls; }

            public PullDetail pullRequest(String p, String repo, int n) {
                return new PullDetail(pulls.stream().filter(x -> x.number() == n).findFirst().orElseThrow(), "sha", List.of(), false, List.of(), null);
            }

            public String file(String p, String repo, String ref, String path) { return ""; }

            public String merge(String p, String repo, int n, String headSha, String title) { return ""; }

            @Override
            public Optional<Boolean> mergeable(String p, String repo, int n) {
                Deque<Optional<Boolean>> answers = mergeable.get(n);
                if (answers == null) throw new AssertionError("No debía preguntar por el PR #" + n);
                return answers.size() > 1 ? answers.poll() : answers.peek();
            }
        };
        IssueApi issues = new IssueApiStub() {
            @Override
            public Optional<IssueContext> context(String key) {
                String project = key.substring(0, key.indexOf('-'));
                return Optional.of(new IssueContext(key, project, "t", null, "TASK", "PR_REVIEW", "HIGH", null, List.of(), null, null, OWNER, List.of()));
            }

            @Override
            public Optional<String> returnForConflict(String key, String note, UUID actorId) {
                returned.add(key + "|" + note);
                return Optional.of("Desarrollo");
            }
        };
        PlatformTransactionManager tx = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }

            public void commit(TransactionStatus s) {}

            public void rollback(TransactionStatus s) {}
        };
        return new ConflictWatcher(code, issues, tx, 0, 4);
    }

    @Test
    void devuelveSoloLasTarjetasDeLosPrEnConflictoHaciaLaMismaRama() {
        // #8 todavía se calcula y después queda en conflicto; #9 se puede fusionar; #11 es de otro tablero y también choca.
        mergeable.put(8, new ArrayDeque<>(List.of(Optional.empty(), Optional.of(false))));
        mergeable.put(9, new ArrayDeque<>(List.of(Optional.of(true))));
        mergeable.put(11, new ArrayDeque<>(List.of(Optional.of(false))));

        watcher().check(new PullMerged(TestTenant.WORKSPACE, "KUA", "acme/kua", 7, OWNER));

        assertThat(returned).singleElement().satisfies(r -> {
            assertThat(r).startsWith("KUA-2|").contains("acme/kua #8", "«develop»", "#7");
        });
    }

    @Test
    void reconoceLaClaveEnElTituloOEnLaRama() {
        assertThat(ConflictWatcher.cardKeys(pr(1, "Pagos", "feature/kua-12-pagos", "develop", "open"))).containsExactly("KUA-12");
        assertThat(ConflictWatcher.cardKeys(pr(1, "KUA-3 y KUA-4", "feature/KUA-3", "develop", "open"))).containsExactly("KUA-3", "KUA-4");
    }
}
