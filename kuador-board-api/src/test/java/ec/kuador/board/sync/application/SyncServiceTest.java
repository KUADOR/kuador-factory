package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.sync.domain.ExportPullRequest;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import ec.kuador.board.sync.domain.SyncState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncServiceTest {

    private static final UUID PERSON = UUID.randomUUID();

    private final FakeRepo repo = new FakeRepo();
    private final Map<UUID, DocContent> board = new LinkedHashMap<>();
    private final List<String> createdIssues = new ArrayList<>();
    private SyncService sync;

    @BeforeEach
    void setUp() {
        RepositoryProvider provider = new RepositoryProvider() {
            public String kind() { return "GITHUB"; }
            public Validated validate(LinkSpec spec) { return new Validated(spec.location(), "main", "cifrado"); }
            public RepositoryFiles open(RepositoryLink link) { return repo; }
        };
        DocsApi docs = new ec.kuador.board.testing.DocsApiStub() {
            public List<DocContent> ofProject(String projectKey) { return List.copyOf(board.values()); }
            public DocContent saveFromRepository(UUID id, int base, String content, String note, UUID actor) {
                return edit(id, content);
            }
            public DocContent createFromRepository(String projectKey, String kind, String issueKey, String path, String content, UUID actor) {
                DocContent d = new DocContent(UUID.randomUUID(), projectKey, issueKey, kind, kind, path, 1, content);
                board.put(d.id(), d);
                return d;
            }
            public List<String> allowedKinds(String projectKey) { return List.of("CONSTITUTION", "PRODUCT", "AGENT_RULES", "SPEC", "PLAN", "TASKS"); }
            public Optional<MethodologyRules> methodologyRules(String projectKey) { return Optional.empty(); }
            public DocContent write(String p, String i, String k, String c, String n, UUID a) { throw new UnsupportedOperationException(); }
        };
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", "SDD");
        IssueApi issues = new ec.kuador.board.testing.IssueApiStub() {
            public Optional<IssueRef> find(String key) { return Optional.of(new IssueRef(key, "KUA", 1, "x")); }
            public IssueRef createForImport(String projectKey, String title, UUID actor) {
                createdIssues.add(title);
                return new IssueRef("KUA-9", projectKey, 9, title);
            }
            public Optional<IssueContext> context(String key) { return Optional.empty(); }
            public void addComment(String key, UUID author, String body) {}
            public void reassign(String key, UUID member, UUID actor) {}
            public boolean setStoryPointsIfEmpty(String key, int points, UUID actor) { return false; }
        };
        sync = new SyncService(new InMemoryRecords(), List.of(provider), docs, projects, issues, new InMemoryCatalog(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        sync.link("KUA", "GITHUB", new RepositoryProvider.LinkSpec("kuador/kuador-api", null, "token"), PERSON);
    }

    private DocContent doc(String kind, String path, String content) {
        DocContent d = new DocContent(UUID.randomUUID(), "KUA", null, kind, kind, path, 1, content);
        board.put(d.id(), d);
        return d;
    }

    private DocContent edit(UUID id, String content) {
        DocContent d = board.get(id);
        DocContent saved = new DocContent(id, d.projectKey(), d.issueKey(), d.kind(), d.title(), d.path(), d.version() + 1, content);
        board.put(id, saved);
        return saved;
    }

    private SyncService.Entry entry(UUID id) {
        return sync.status("KUA").entries().stream().filter(e -> e.document().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void exportOpensAPullRequestAndMergingItMarksTheDocumentSynced() {
        DocContent c = doc("CONSTITUTION", ".specify/memory/constitution.md", "# v1");

        SyncService.Exported exported = sync.export("KUA", c.id(), false, PERSON);

        assertThat(exported.direct()).isFalse();
        assertThat(exported.pullRequest()).isEqualTo(1);
        assertThat(repo.main).as("main no cambia hasta fusionar").doesNotContainKey(".specify/memory/constitution.md");
        SyncService.Entry pending = entry(c.id());
        assertThat(pending.state()).isEqualTo(SyncState.BOARD_ONLY);
        assertThat(pending.openPr()).isEqualTo(new SyncService.OpenPr(1, "https://github.com/pr/1", 1));
        assertThatThrownBy(() -> sync.export("KUA", c.id(), false, PERSON)).as("la misma versión ya va en el PR").hasMessageContaining("PR #1");

        repo.merge(1);

        SyncService.Entry merged = entry(c.id());
        assertThat(merged.state()).isEqualTo(SyncState.SYNCED);
        assertThat(merged.openPr()).isNull();
    }

    @Test
    void newBoardChangesAreAddedToTheOpenPullRequest() {
        DocContent c = doc("CONSTITUTION", ".specify/memory/constitution.md", "# v1");
        DocContent p = doc("PRODUCT", "PRODUCT.md", "# producto");
        sync.export("KUA", c.id(), false, PERSON);
        edit(c.id(), "# v2");

        SyncService.Exported again = sync.export("KUA", List.of(c.id(), p.id()), Set.of(), PERSON);

        assertThat(again.pullRequest()).as("mismo PR").isEqualTo(1);
        assertThat(repo.prs).hasSize(1);
        assertThat(entry(c.id()).openPr().version()).isEqualTo(2);
        repo.merge(1);
        assertThat(repo.main).containsEntry(".specify/memory/constitution.md", "# v2").containsEntry("PRODUCT.md", "# producto");
        assertThat(entry(c.id()).state()).isEqualTo(SyncState.SYNCED);
        assertThat(entry(p.id()).state()).isEqualTo(SyncState.SYNCED);
    }

    @Test
    void boardEditAfterThePullRequestWasOpenedIsNotLostWhenItMerges() {
        DocContent c = doc("PRODUCT", "PRODUCT.md", "# v1");
        sync.export("KUA", c.id(), false, PERSON);
        edit(c.id(), "# v2 (después del PR)");

        repo.merge(1);

        assertThat(entry(c.id()).state()).as("el PR llevaba v1; v2 sigue pendiente, no es conflicto").isEqualTo(SyncState.BOARD_AHEAD);
    }

    @Test
    void closedWithoutMergingTheDocumentGoesBackToPending() {
        DocContent c = doc("PRODUCT", "PRODUCT.md", "# v1");
        sync.export("KUA", c.id(), false, PERSON);

        repo.close(1);

        SyncService.Entry e = entry(c.id());
        assertThat(e.openPr()).isNull();
        assertThat(e.state()).isEqualTo(SyncState.BOARD_ONLY);
        assertThat(sync.export("KUA", c.id(), false, PERSON).pullRequest()).as("abre un PR nuevo").isEqualTo(2);
    }

    @Test
    void changesMergedByAnotherDevAreImportedAndConflictsNeedAChoice() {
        DocContent c = doc("PRODUCT", "PRODUCT.md", "# base");
        sync.export("KUA", c.id(), false, PERSON);
        repo.merge(1);

        repo.main.put("PRODUCT.md", "# editado por otro dev en main");
        assertThat(entry(c.id()).state()).isEqualTo(SyncState.FILE_AHEAD);
        sync.importFile("KUA", c.id(), false, PERSON);
        assertThat(board.get(c.id()).content()).isEqualTo("# editado por otro dev en main");

        edit(c.id(), "# tablero");
        repo.main.put("PRODUCT.md", "# main otra vez");
        assertThat(entry(c.id()).state()).isEqualTo(SyncState.CONFLICT);
        assertThatThrownBy(() -> sync.export("KUA", c.id(), false, PERSON)).isInstanceOf(ConflictException.class);
        assertThat(sync.export("KUA", c.id(), true, PERSON).pullRequest()).as("la persona eligió el tablero: va a un PR").isEqualTo(2);
    }

    @Test
    void syncSafeImportsMainAndExportsTheRestInOnePullRequest() {
        DocContent c = doc("CONSTITUTION", ".specify/memory/constitution.md", "# c");
        DocContent a = doc("AGENT_RULES", "AGENTS.md", "# reglas");
        DocContent p = doc("PRODUCT", "PRODUCT.md", "# p");
        repo.main.put("PRODUCT.md", "# p");
        sync.syncSafe("KUA", PERSON); // registra PRODUCT como idéntico y exporta los otros dos
        repo.merge(1);
        repo.main.put("PRODUCT.md", "# p desde main");
        edit(c.id(), "# c v2");

        SyncService.SafeResult r = sync.syncSafe("KUA", PERSON);

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.exported().documents()).isEqualTo(1);
        assertThat(r.exported().pullRequest()).isEqualTo(2);
        assertThat(board.get(p.id()).content()).isEqualTo("# p desde main");
        assertThat(entry(a.id()).state()).isEqualTo(SyncState.SYNCED);
    }

    @Test
    void importsExistingSpecFolderAsANewIssue() {
        repo.main.put("specs/001-lead-capture/spec.md", "# Feature Specification: Captura de leads");
        repo.main.put("specs/001-lead-capture/plan.md", "# Plan");

        SyncService.Untracked group = sync.status("KUA").untracked().getFirst();
        assertThat(group.suggestedTitle()).isEqualTo("Captura de leads");
        List<SyncService.Entry> imported = sync.importNew("KUA", "specs/001-lead-capture", null, group.suggestedTitle(), PERSON);

        assertThat(createdIssues).containsExactly("Captura de leads");
        assertThat(imported).allMatch(e -> e.document().issueKey().equals("KUA-9") && e.state() == SyncState.SYNCED);
        assertThat(sync.status("KUA").untracked()).isEmpty();
    }

    @Test
    void relinkingToAnotherRepoForgetsPreviousHistory() {
        DocContent c = doc("PRODUCT", "PRODUCT.md", "# v1");
        sync.export("KUA", c.id(), false, PERSON);

        sync.link("KUA", "GITHUB", new RepositoryProvider.LinkSpec("kuador/otro-repo", null, "token"), PERSON);

        assertThat(entry(c.id()).openPr()).as("los PRs del repo anterior no aplican").isNull();
    }

    /** Repositorio en memoria que se comporta como GitHub: publicar abre un PR; fusionar lo aplica a main. */
    static class FakeRepo implements RepositoryFiles {
        final Map<String, String> main = new HashMap<>();
        final Map<Integer, Map<String, String>> prs = new LinkedHashMap<>();
        final Map<Integer, PullRequestState> states = new HashMap<>();
        final Map<String, Integer> branches = new HashMap<>();

        public Optional<String> read(String path) { return Optional.ofNullable(main.get(path)); }
        public List<String> listMarkdown(String dir, int depth) {
            return main.keySet().stream().filter(p -> p.startsWith(dir + "/")).sorted().toList();
        }
        public Published publish(List<FileWrite> files, String message, String title, String description, String existingBranch) {
            if (existingBranch != null && branches.containsKey(existingBranch)) {
                files.forEach(f -> prs.get(branches.get(existingBranch)).put(f.path(), f.content()));
                return new Published(false, null, null, existingBranch);
            }
            int n = prs.size() + 1;
            Map<String, String> changes = new HashMap<>();
            files.forEach(f -> changes.put(f.path(), f.content()));
            prs.put(n, changes);
            states.put(n, PullRequestState.OPEN);
            branches.put("tablero/docs-" + n, n);
            return new Published(false, n, "https://github.com/pr/" + n, "tablero/docs-" + n);
        }
        public Optional<PullRequestState> pullRequestState(int number) { return Optional.ofNullable(states.get(number)); }
        void merge(int n) {
            main.putAll(prs.get(n));
            states.put(n, PullRequestState.MERGED);
            branches.values().remove(n);
        }
        void close(int n) {
            states.put(n, PullRequestState.CLOSED);
            branches.values().remove(n);
        }
    }

    static class InMemoryRecords implements SyncRecordRepository {
        RepositoryLink link;
        final Map<UUID, SyncRecord> records = new HashMap<>();
        final Map<UUID, ExportPullRequest> pullRequests = new LinkedHashMap<>();
        public Optional<RepositoryLink> findLink(String p) { return Optional.ofNullable(link); }
        public void saveLink(RepositoryLink l) { link = l; }
        public void deleteLink(String p) { link = null; }
        public Map<UUID, SyncRecord> findRecords(Iterable<UUID> ids) {
            Map<UUID, SyncRecord> out = new HashMap<>();
            ids.forEach(id -> { if (records.containsKey(id)) out.put(id, records.get(id)); });
            return out;
        }
        public void saveRecord(SyncRecord r) { records.put(r.documentId(), r); }
        public void deleteRecords(Iterable<UUID> ids) { ids.forEach(records::remove); }
        public List<ExportPullRequest> findOpenPullRequests(String p) {
            return pullRequests.values().stream().filter(pr -> pr.state() == RepositoryFiles.PullRequestState.OPEN).toList();
        }
        public void savePullRequest(ExportPullRequest pr) { pullRequests.put(pr.id(), pr); }
        public void deletePullRequests(String p) { pullRequests.clear(); }
    }
}
