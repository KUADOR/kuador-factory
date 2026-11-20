package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.catalog.StageDef.AgentReview;
import ec.kuador.board.catalog.StageDef.ReviewMode;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.CodeReviewApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** La puerta en automático fusiona exactamente lo revisado y distingue conflictos de commits nuevos. */
class GateMergeTest {

    /** GitHub de mentira: fusiona salvo los PR marcados para fallar con un mensaje. */
    static final class FakeCode implements CodeReviewApi {
        final List<String> merged = new ArrayList<>();
        final java.util.Map<Integer, RuntimeException> failures = new java.util.HashMap<>();

        @Override
        public List<String> repositories(String projectKey) {
            return List.of("kuador/app");
        }

        @Override
        public List<PullSummary> pullRequests(String projectKey, String search) {
            return List.of();
        }

        @Override
        public PullDetail pullRequest(String projectKey, String repository, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String file(String projectKey, String repository, String ref, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String merge(String projectKey, String repository, int number, String headSha, String title) {
            RuntimeException fail = failures.get(number);
            if (fail != null) throw fail;
            merged.add(number + "@" + headSha + " " + title);
            return "abcdef1234";
        }
    }

    private static CodeReviewApi.PullDetail pull(int number, String sha) {
        return new CodeReviewApi.PullDetail(new CodeReviewApi.PullSummary("kuador/app", number, "Login", "feature/KUA-1-login", "develop", "open",
                false, "agente", null, null, null), sha, List.of(), false, List.of(), null);
    }

    @Test
    void fusionaCadaPrEnElCommitRevisado() {
        FakeCode code = new FakeCode();

        GateMerge.Result r = GateMerge.merge(code, "KUA", "KUA-1", List.of(pull(7, "sha7"), pull(8, "sha8")));

        assertThat(r.outcome()).isEqualTo(GateMerge.Outcome.MERGED);
        assertThat(code.merged).containsExactly("7@sha7 KUA-1: Login (#7)", "8@sha8 KUA-1: Login (#8)");
        assertThat(r.detail()).contains("kuador/app #7 (abcdef1)", "kuador/app #8 (abcdef1)");
    }

    @Test
    void unConflictoSeDistingueYDiceLoQueYaSeFusiono() {
        FakeCode code = new FakeCode();
        code.failures.put(8, new DomainException("GitHub respondió 405: Pull Request is not mergeable."));

        GateMerge.Result r = GateMerge.merge(code, "KUA", "KUA-1", List.of(pull(7, "sha7"), pull(8, "sha8")));

        assertThat(r.outcome()).isEqualTo(GateMerge.Outcome.CONFLICT);
        assertThat(r.detail()).startsWith("Ya fusioné kuador/app #7").contains("kuador/app #8");
    }

    @Test
    void commitsNuevosDespuesDeRevisarNoSeFusionanACiegas() {
        FakeCode code = new FakeCode();
        code.failures.put(7, new ConflictException("GitHub rechazó la operación: Head branch was modified. Review and try the merge again."));

        assertThat(GateMerge.merge(code, "KUA", "KUA-1", List.of(pull(7, "sha7"))).outcome()).isEqualTo(GateMerge.Outcome.MOVED);
    }

    @Test
    void otroErrorQuedaParaUnaPersona() {
        assertThat(GateMerge.classify("GitHub rechazó la credencial (vencida o inválida).")).isEqualTo(GateMerge.Outcome.FAILED);
        assertThat(GateMerge.classify(null)).isEqualTo(GateMerge.Outcome.FAILED);
    }

    @Test
    void lasPuertasGuardadasAntesNoFusionan() {
        assertThat(new AgentReview(ReviewMode.AGENT_ESCALATE, null, null, null).merges()).isFalse();
        assertThat(new AgentReview(ReviewMode.AGENT_ESCALATE, null, null, null, null).merges()).isFalse();
        assertThat(new AgentReview(ReviewMode.AGENT_ESCALATE, null, null, null, true).merges()).isTrue();
    }
}
