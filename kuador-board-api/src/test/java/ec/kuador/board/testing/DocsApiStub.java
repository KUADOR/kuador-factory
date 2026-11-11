package ec.kuador.board.testing;

import ec.kuador.board.docs.DocsApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Base para dobles de {@link DocsApi}: cada prueba sobrescribe solo lo que usa. */
public abstract class DocsApiStub implements DocsApi {

    @Override public List<DocContent> ofProject(String projectKey) { return List.of(); }
    @Override public DocContent saveFromRepository(UUID documentId, int baseVersion, String content, String note, UUID actorId) { throw new UnsupportedOperationException(); }
    @Override public DocContent createFromRepository(String projectKey, String kind, String issueKey, String path, String content, UUID actorId) { throw new UnsupportedOperationException(); }
    @Override public List<String> allowedKinds(String projectKey) { return List.of(); }
    @Override public Optional<MethodologyRules> methodologyRules(String projectKey) { return Optional.empty(); }
    @Override public List<DocContent> ensureProjectDocuments(String projectKey, UUID actorId) { return ofProject(projectKey).stream().filter(d -> d.issueKey() == null).toList(); }
    @Override public DocContent write(String projectKey, String issueKey, String kind, String content, String note, UUID actorId) { throw new UnsupportedOperationException(); }
}
