package ec.kuador.board.docs.infrastructure.persistence;

import ec.kuador.board.docs.domain.Document;
import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.docs.domain.DocumentVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataDocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    /** Los documentos base del proyecto (de todos sus tableros) y los de las tarjetas de este tablero en ese proyecto. */
    @org.springframework.data.jpa.repository.Query("""
            select d from DocumentEntity d where d.subjectKey = :subjectKey and (d.issueKey is null or d.projectKey = :projectKey)
             order by d.kind, d.issueKey""")
    List<DocumentEntity> findInBoard(String projectKey, String subjectKey);

    List<DocumentEntity> findBySubjectKeyAndIssueKeyIsNullOrderByKind(String subjectKey);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from DocumentEntity d where d.projectKey = :projectKey and d.issueKey is not null")
    int deleteCardsOf(String projectKey);

    List<DocumentEntity> findByIssueKeyOrderByKind(String issueKey);

    boolean existsBySubjectKeyAndIssueKeyIsNullAndKind(String subjectKey, String kind);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update DocumentEntity d set d.subjectKey = :subjectKey where d.issueKey = :issueKey")
    int moveIssue(String issueKey, String subjectKey);

    boolean existsByProjectKeyAndIssueKeyAndKind(String projectKey, String issueKey, String kind);
}

interface SpringDataDocumentVersionRepository extends JpaRepository<DocumentVersionEntity, DocumentVersionEntity.Key> {

    List<DocumentVersionEntity> findByDocumentIdOrderByVersionDesc(UUID documentId);
}

@Repository
class JpaDocumentRepository implements DocumentRepository {

    private final SpringDataDocumentRepository docs;
    private final SpringDataDocumentVersionRepository versions;

    JpaDocumentRepository(SpringDataDocumentRepository docs, SpringDataDocumentVersionRepository versions) {
        this.docs = docs;
        this.versions = versions;
    }

    @Override
    public Optional<Document> findById(UUID id) {
        return docs.findById(id).map(DocumentEntity::toDomain);
    }

    @Override
    public void moveIssue(String issueKey, String subjectKey) {
        docs.moveIssue(issueKey, subjectKey);
    }

    @Override
    public List<Document> findByProject(String projectKey, String subjectKey) {
        return docs.findInBoard(projectKey, subjectKey).stream().map(DocumentEntity::toDomain).toList();
    }

    @Override
    public List<Document> findBase(String subjectKey) {
        return docs.findBySubjectKeyAndIssueKeyIsNullOrderByKind(subjectKey).stream().map(DocumentEntity::toDomain).toList();
    }

    @Override
    public void deleteCardsOf(String projectKey) {
        docs.deleteCardsOf(projectKey);
    }

    @Override
    public List<Document> findByIssue(String issueKey) {
        return docs.findByIssueKeyOrderByKind(issueKey).stream().map(DocumentEntity::toDomain).toList();
    }

    @Override
    public boolean exists(String projectKey, String subjectKey, String issueKey, String kind) {
        return issueKey == null
                ? docs.existsBySubjectKeyAndIssueKeyIsNullAndKind(subjectKey, kind)
                : docs.existsByProjectKeyAndIssueKeyAndKind(projectKey, issueKey, kind);
    }

    @Override
    public Document save(Document document) {
        DocumentEntity entity = docs.findById(document.id())
                .map(e -> { e.copyFrom(document); return e; })
                .orElseGet(() -> DocumentEntity.newFrom(document));
        return docs.saveAndFlush(entity).toDomain();
    }

    @Override
    public void saveVersion(DocumentVersion version) {
        versions.save(DocumentVersionEntity.from(version));
    }

    @Override
    public Optional<DocumentVersion> findVersion(UUID documentId, int version) {
        return versions.findById(new DocumentVersionEntity.Key(documentId, version)).map(DocumentVersionEntity::toDomain);
    }

    @Override
    public List<DocumentVersion> findVersions(UUID documentId) {
        return versions.findByDocumentIdOrderByVersionDesc(documentId).stream().map(DocumentVersionEntity::toDomain).toList();
    }
}
