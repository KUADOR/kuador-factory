package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.testing.IssueApiStub;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** «Aprobar y fusionar»: solo quien aprueba la puerta, solo el PR de la tarjeta, solo con el CI en verde y el commit revisado. */
class MergeServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();

    private final List<String> comments = new ArrayList<>();
    private final List<String> merged = new ArrayList<>();
    private List<CodeReviewApi.Check> checks = List.of(new CodeReviewApi.Check("api", "completed", "success", ""));
    private String approved;

    private MergeService service() {
        IssueApi issues = new IssueApiStub() {
            @Override
            public Optional<IssueContext> context(String key) {
                return Optional.of(new IssueContext(key, "KUA", "Corrige IVA", null, "STORY", "PR_REVIEW", "HIGH", null, List.of(), null, null, OWNER, List.of()));
            }

            @Override
            public boolean canApproveGate(String key, UUID actorId) {
                return OWNER.equals(actorId);
            }

            @Override
            public void addComment(String key, UUID author, String body) {
                comments.add(body);
            }

            @Override
            public Progress approveGate(String key, String comment, UUID actorId) {
                approved = key;
                return new Progress(key, "RELEASE", "Release", null);
            }
        };
        CodeReviewApi code = new CodeReviewApi() {
            final PullSummary pr = new PullSummary("acme/kua", 7, "KUA-12 Corrige IVA", "kua-12-iva", "main", "open", false, "agente", "u", "", "");

            public List<String> repositories(String p) { return List.of("acme/kua"); }

            public List<PullSummary> pullRequests(String p, String search) { return search.equalsIgnoreCase("KUA-12") ? List.of(pr) : List.of(); }

            public PullDetail pullRequest(String p, String repo, int n) { return new PullDetail(pr, "a1b2c3d4", List.of(), false, checks, null); }

            public String file(String p, String repo, String ref, String path) { return ""; }

            public String merge(String p, String repo, int n, String headSha, String title) {
                merged.add(repo + "#" + n + "@" + headSha + "|" + title);
                return "f00dcafe99";
            }
        };
        return new MergeService(issues, code);
    }

    @Test
    void whoApprovesMergesTheCardsPrWithGreenCiAndTheGateAdvances() {
        MergeService.Merged m = service().merge("KUA-12", "acme/kua", 7, true, OWNER);

        assertThat(merged).containsExactly("acme/kua#7@a1b2c3d4|KUA-12: KUA-12 Corrige IVA (#7)");
        assertThat(m.status()).isEqualTo("RELEASE");
        assertThat(approved).isEqualTo("KUA-12");
        assertThat(comments).anyMatch(c -> c.contains("Fusioné acme/kua #7") && c.contains("f00dcaf"));
    }

    @Test
    void nobodyElseMergesAndNothingMergesWithoutGreenCiOrAnotherCardsPr() {
        assertThatThrownBy(() -> service().merge("KUA-12", "acme/kua", 7, true, DEV)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service().merge("KUA-12", "acme/kua", 8, true, OWNER)).isInstanceOf(DomainException.class).hasMessageContaining("no menciona");

        checks = List.of(new CodeReviewApi.Check("api", "completed", "failure", ""));
        assertThatThrownBy(() -> service().merge("KUA-12", "acme/kua", 7, true, OWNER)).isInstanceOf(ConflictException.class).hasMessageContaining("no está en verde");
        checks = List.of(new CodeReviewApi.Check("api", "in_progress", null, ""));
        assertThatThrownBy(() -> service().merge("KUA-12", "acme/kua", 7, true, OWNER)).isInstanceOf(ConflictException.class).hasMessageContaining("sin resultado");
        checks = List.of();
        assertThatThrownBy(() -> service().merge("KUA-12", "acme/kua", 7, true, OWNER)).isInstanceOf(ConflictException.class).hasMessageContaining("no tiene CI");

        assertThat(merged).as("en ningún caso se fusionó").isEmpty();
    }
}
