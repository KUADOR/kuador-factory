package ec.kuador.board.docs.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.docs.domain.Document;
import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.issue.CardEvidence;
import ec.kuador.board.project.ProjectApi;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Para las etapas que exigen un documento o un diagrama, y para saber si un rol ya escribió el suyo en la tarjeta. */
@Component
class DocumentEvidence implements CardEvidence {

    /** Un bloque de código Mermaid en Markdown. */
    static final String MERMAID = "```mermaid";

    private final DocumentRepository documents;
    private final ProjectApi projects;
    private final CatalogApi catalog;

    DocumentEvidence(DocumentRepository documents, ProjectApi projects, CatalogApi catalog) {
        this.documents = documents;
        this.projects = projects;
        this.catalog = catalog;
    }

    @Override
    public boolean hasDocument(String issueKey, String kind) {
        return documents.findByIssue(issueKey).stream().anyMatch(d -> d.kind().equalsIgnoreCase(kind));
    }

    @Override
    public boolean hasDiagram(String issueKey) {
        return documents.findByIssue(issueKey).stream().anyMatch(this::withDiagram);
    }

    @Override
    public Optional<Boolean> hasDocumentOf(String projectKey, String issueKey, String role) {
        return projects.find(projectKey).filter(p -> p.documents() && p.methodology() != null)
                .flatMap(p -> catalog.methodology(p.methodology()))
                .flatMap(m -> m.issueDocumentOf(role))
                .map(def -> hasDocument(issueKey, def.kind()));
    }

    private boolean withDiagram(Document d) {
        return documents.findVersion(d.id(), d.currentVersion()).map(v -> v.content() != null && v.content().contains(MERMAID)).orElse(false);
    }
}
