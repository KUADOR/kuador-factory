package ec.kuador.board.docs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.docs.DocumentSaved;
import ec.kuador.board.docs.domain.Document;
import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.docs.domain.DocumentVersion;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.TestTenant;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestTenant.class)
class DocumentServiceTest {

    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private final List<Object> published = new ArrayList<>();
    private DocumentService service;

    @BeforeEach
    void setUp() {
        InMemoryProjects projectApi = new InMemoryProjects();
        projectApi.add("KUA", "SOFTWARE", "SDD");
        projectApi.add("ODD", "SOFTWARE", "ODD");
        projectApi.add("NUE", "SOFTWARE", null);
        projectApi.add("VEN", "SALES", null);
        IssueApi issueApi = new ec.kuador.board.testing.IssueApiStub() {
            public Optional<IssueRef> find(String key) {
                return switch (key) {
                    case "KUA-8" -> Optional.of(new IssueRef("KUA-8", "KUA", 8, "Prueba de eventos"));
                    case "ODD-1" -> Optional.of(new IssueRef("ODD-1", "ODD", 1, "Recuperar contraseña"));
                    default -> Optional.empty();
                };
            }
            public IssueRef createForImport(String projectKey, String title, UUID actorId) { throw new UnsupportedOperationException(); }
            public Optional<IssueContext> context(String key) { return Optional.empty(); }
            public void addComment(String key, UUID author, String body) {}
            public void reassign(String key, UUID member, UUID actor) {}
            public boolean setStoryPointsIfEmpty(String key, int points, UUID actor) { return false; }
        };
        service = new DocumentService(new InMemoryDocuments(), projectApi, issueApi, new InMemoryCatalog(), published::add,
                Clock.fixed(Instant.parse("2026-11-10T17:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsSpecFromTemplateWithSpecKitPath() {
        DocumentService.WithContent spec = service.create("KUA", "SPEC", "KUA-8", AGENT);

        assertThat(spec.document().path()).isEqualTo("specs/008-prueba-de-eventos/spec.md");
        assertThat(spec.document().currentVersion()).isEqualTo(1);
        assertThat(spec.content()).startsWith("# Spec: Prueba de eventos").contains("**Issue**: KUA-8").contains("FR-001");
        DocumentSaved event = (DocumentSaved) published.getFirst();
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.issueKey()).isEqualTo("KUA-8");
        assertThat(event.actorId()).isEqualTo(AGENT);
    }

    @Test
    void savingCreatesVersionsAndRejectsStaleEdits() {
        UUID id = service.create("KUA", "CONSTITUTION", null, PERSON).document().id();

        DocumentService.WithContent v2 = service.save(id, 1, "# Constitución\n\nI. Hexagonal", "Primer principio", PERSON);
        assertThat(v2.document().currentVersion()).isEqualTo(2);

        // Un agente editó sobre la versión 1 sin ver la 2: no puede pisarla.
        assertThatThrownBy(() -> service.save(id, 1, "otra cosa", null, AGENT))
                .isInstanceOf(ConflictException.class).hasMessageContaining("versión 2");
        assertThat(service.get(id).content()).contains("I. Hexagonal");
    }

    @Test
    void savingTheSameContentDoesNotCreateAVersion() {
        DocumentService.WithContent doc = service.create("KUA", "PRODUCT", null, PERSON);
        published.clear();

        DocumentService.WithContent same = service.save(doc.document().id(), 1, doc.content(), "nada", PERSON);

        assertThat(same.document().currentVersion()).isEqualTo(1);
        assertThat(published).isEmpty();
    }

    @Test
    void restoringCreatesANewVersionWithOldContent() {
        DocumentService.WithContent doc = service.create("KUA", "GLOSSARY", null, PERSON);
        UUID id = doc.document().id();
        service.save(id, 1, "# Glosario\n\nborrado por error", null, AGENT);

        DocumentService.WithContent restored = service.restore(id, 1, 2, PERSON);

        assertThat(restored.document().currentVersion()).isEqualTo(3);
        assertThat(restored.content()).isEqualTo(doc.content());
        assertThat(service.versions(id)).extracting(DocumentVersion::version).containsExactly(3, 2, 1);
        assertThat(service.versions(id).getFirst().note()).isEqualTo("Restaurada desde la versión 1");
    }

    @Test
    void methodologyDecidesWhichDocumentsExist() {
        assertThatThrownBy(() -> service.create("ODD", "CONSTITUTION", null, PERSON))
                .isInstanceOf(DomainException.class).hasMessageContaining("Organic");
        assertThat(service.create("ODD", "TRACKING", "ODD-1", PERSON).document().path())
                .isEqualTo("odd/tasks/odd-1-recuperar-contrasena.md");
        assertThatThrownBy(() -> service.create("NUE", "PRODUCT", null, PERSON))
                .isInstanceOf(DomainException.class).hasMessageContaining("metodología");
    }

    @Test
    void aBoardWithoutRepositoryAlsoKeepsDocuments() {
        // Un tablero de ventas lleva documentos (su manual, una propuesta) aunque no tenga repositorio de código.
        assertThat(service.overview("VEN").project().repository()).isFalse();
        assertThat(service.overview("VEN").documents()).isEmpty();
    }

    @Test
    void validatesScopeAndDuplicates() {
        assertThatThrownBy(() -> service.create("KUA", "SPEC", null, PERSON)).hasMessageContaining("indica el issue");
        assertThatThrownBy(() -> service.create("KUA", "CONSTITUTION", "KUA-8", PERSON)).hasMessageContaining("del proyecto");
        assertThatThrownBy(() -> service.create("KUA", "SPEC", "ODD-1", PERSON)).hasMessageContaining("No existe el issue ODD-1 en KUA");
        service.create("KUA", "SPEC", "KUA-8", PERSON);
        assertThatThrownBy(() -> service.create("KUA", "SPEC", "KUA-8", AGENT)).isInstanceOf(ConflictException.class);
    }

    static class InMemoryDocuments implements DocumentRepository {
        final Map<UUID, Document> docs = new HashMap<>();
        final List<DocumentVersion> versions = new ArrayList<>();

        public Optional<Document> findById(UUID id) { return Optional.ofNullable(docs.get(id)); }
        public List<Document> findByProject(String p) { return docs.values().stream().filter(d -> d.projectKey().equals(p)).toList(); }
        public List<Document> findByIssue(String i) { return docs.values().stream().filter(d -> i.equals(d.issueKey())).toList(); }
        public boolean exists(String p, String i, String k) {
            return docs.values().stream().anyMatch(d -> d.projectKey().equals(p) && Objects.equals(d.issueKey(), i) && d.kind().equals(k));
        }
        public Document save(Document d) { docs.put(d.id(), d); return d; }
        public void saveVersion(DocumentVersion v) { versions.add(v); }
        public Optional<DocumentVersion> findVersion(UUID id, int n) {
            return versions.stream().filter(v -> v.documentId().equals(id) && v.version() == n).findFirst();
        }
        public List<DocumentVersion> findVersions(UUID id) {
            return versions.stream().filter(v -> v.documentId().equals(id)).sorted(Comparator.comparingInt(DocumentVersion::version).reversed()).toList();
        }
    }
}
