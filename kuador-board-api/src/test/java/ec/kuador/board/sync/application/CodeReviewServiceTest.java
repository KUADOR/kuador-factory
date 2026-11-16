package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.CodeReviewApi.PullDetail;
import ec.kuador.board.sync.CodeReviewApi.PullSummary;
import ec.kuador.board.sync.domain.CodeHost;
import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ExportPullRequest;
import ec.kuador.board.sync.domain.ProjectComponent;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los agentes leen solo los repos del proyecto: el de documentos y los de sus piezas. */
class CodeReviewServiceTest {

    final List<String> asked = new ArrayList<>();

    private CodeReviewService service() {
        SyncRecordRepository records = new SyncRecordRepository() {
            public Optional<RepositoryLink> findLink(String p) {
                return Optional.of(new RepositoryLink(p, "GITHUB_APP", "acme/med", "main", "42", Instant.now(), null));
            }
            public void saveLink(RepositoryLink link) {}
            public void deleteLink(String p) {}
            public Map<UUID, SyncRecord> findRecords(Iterable<UUID> ids) { return Map.of(); }
            public void saveRecord(SyncRecord r) {}
            public void deleteRecords(Iterable<UUID> ids) {}
            public List<ExportPullRequest> findOpenPullRequests(String p) { return List.of(); }
            public void savePullRequest(ExportPullRequest pr) {}
            public void deletePullRequests(String p) {}
        };
        ComponentRepository components = new ComponentRepository() {
            public List<ProjectComponent> findByProject(String p) {
                return List.of(piece("acme/med"), piece("acme/med-web"), new ProjectComponent(UUID.randomUUID(), p, "local", null, null, null, null,
                        "API", "java", null, null, null, "/tmp/local", "", null, null, Instant.now(), null));
            }
            public void save(ProjectComponent c) {}
        };
        CodeHost host = new CodeHost() {
            public boolean supports(String kind) { return kind.equals("GITHUB_APP"); }
            public List<PullSummary> pulls(Access a) {
                asked.add(a.repository() + "@" + a.secret());
                return List.of(new PullSummary(a.repository(), a.repository().endsWith("web") ? 3 : 6,
                        a.repository().endsWith("web") ? "Pantalla de pacientes" : "MED-5 CRUD", "feature/x", "develop", "open", false, "diego", "u", "",
                        a.repository().endsWith("web") ? "2026-11-16T09:00:00Z" : "2026-11-16T10:00:00Z"));
            }
            public Optional<PullDetail> pull(Access a, int n) {
                return Optional.of(new PullDetail(pulls(a).getFirst(), "sha", List.of(), false, List.of(), null));
            }
            public Optional<String> file(Access a, String ref, String path) { return Optional.of("contenido"); }
        };
        return new CodeReviewService(records, components, List.of(host));
    }

    private static ProjectComponent piece(String repo) {
        return new ProjectComponent(UUID.randomUUID(), "MED", repo, null, null, null, null, "API", "java", null, null, null, repo, "", 42L, null, Instant.now(), null);
    }

    @Test
    void listsThePullRequestsOfEveryRepoOfTheProject() {
        CodeReviewService s = service();
        assertThat(s.repositories("MED")).as("sin repetir el de documentos; la pieza local no cuenta").containsExactly("acme/med", "acme/med-web");
        assertThat(s.pullRequests("MED", "")).extracting(PullSummary::number).as("los más recientes primero").containsExactly(6, 3);
        assertThat(s.pullRequests("MED", "med-5")).extracting(PullSummary::number).containsExactly(6);
        assertThat(asked).allMatch(a -> a.endsWith("@42"));
    }

    @Test
    void onlyReadsReposOfTheProject() {
        CodeReviewService s = service();
        assertThat(s.pullRequest("MED", "ACME/med-web", 3).pull().number()).isEqualTo(3);
        assertThatThrownBy(() -> s.pullRequest("MED", "otra/empresa", 1)).isInstanceOf(DomainException.class).hasMessageContaining("acme/med");
        assertThatThrownBy(() -> s.file("MED", "acme/med", "main", "../secretos")).isInstanceOf(DomainException.class);
        assertThat(s.file("MED", "acme/med", "main", "pom.xml")).isEqualTo("contenido");
    }
}
