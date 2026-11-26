package ec.kuador.board.sync.infrastructure.persistence;

import ec.kuador.board.sync.domain.ExportPullRequest;
import ec.kuador.board.sync.domain.RepositoryFiles.PullRequestState;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataRepositoryLinks extends JpaRepository<RepositoryLinkEntity, UUID> {

    Optional<RepositoryLinkEntity> findBySubjectKey(String subjectKey);

    /** Los repositorios de los proyectos que atiende un tablero (el espacio lo filtra la consulta: es SQL directo). */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select r.* from project_repository r
              join board_subject b on b.workspace_id = r.workspace_id and b.subject_key = r.subject_key
             where r.workspace_id = :workspaceId and b.project_key = :projectKey
             order by b.position, b.added_at, r.linked_at""")
    List<RepositoryLinkEntity> findOfBoard(UUID workspaceId, String projectKey);
}

interface SpringDataSyncRecords extends JpaRepository<SyncRecordEntity, UUID> {}

interface SpringDataPullRequests extends JpaRepository<PullRequestEntity, UUID> {

    List<PullRequestEntity> findBySubjectKeyAndStateOrderByCreatedAtDesc(String subjectKey, PullRequestState state);

    void deleteBySubjectKey(String subjectKey);
}

@Repository
class JpaSyncRecordRepository implements SyncRecordRepository {

    private final SpringDataRepositoryLinks links;
    private final SpringDataSyncRecords recordsJpa;
    private final SpringDataPullRequests pullRequests;

    JpaSyncRecordRepository(SpringDataRepositoryLinks links, SpringDataSyncRecords recordsJpa, SpringDataPullRequests pullRequests) {
        this.links = links;
        this.recordsJpa = recordsJpa;
        this.pullRequests = pullRequests;
    }

    @Override
    public List<ExportPullRequest> findOpenPullRequests(String projectKey, String subjectKey) {
        return pullRequests.findBySubjectKeyAndStateOrderByCreatedAtDesc(subjectKey, PullRequestState.OPEN).stream()
                .map(PullRequestEntity::toDomain).toList();
    }

    @Override
    public void savePullRequest(ExportPullRequest pullRequest, String subjectKey) {
        pullRequests.save(PullRequestEntity.from(pullRequest, subjectKey));
    }

    @Override
    public void deletePullRequests(String projectKey, String subjectKey) {
        pullRequests.deleteBySubjectKey(subjectKey);
    }

    @Override
    public Optional<RepositoryLink> findLink(String projectKey, String subjectKey) {
        return links.findBySubjectKey(subjectKey).map(RepositoryLinkEntity::toDomain);
    }

    @Override
    public List<RepositoryLink> findLinks(String projectKey) {
        return links.findOfBoard(ec.kuador.board.shared.tenant.TenantContext.require(), projectKey).stream().map(RepositoryLinkEntity::toDomain).toList();
    }

    // Un vínculo por proyecto (lo comparten sus tableros): guardar reemplaza al anterior.
    @Override
    public void saveLink(RepositoryLink link, String subjectKey) {
        links.findBySubjectKey(subjectKey).ifPresent(existing -> {
            links.delete(existing);
            links.flush();
        });
        links.save(RepositoryLinkEntity.from(link, subjectKey));
    }

    @Override
    public void deleteLink(String projectKey, String subjectKey) {
        links.findBySubjectKey(subjectKey).ifPresent(links::delete);
    }

    @Override
    public Map<UUID, SyncRecord> findRecords(Iterable<UUID> documentIds) {
        return recordsJpa.findAllById(documentIds).stream().map(SyncRecordEntity::toDomain)
                .collect(Collectors.toMap(SyncRecord::documentId, r -> r));
    }

    @Override
    public void saveRecord(SyncRecord record) {
        recordsJpa.save(SyncRecordEntity.from(record));
    }

    @Override
    public void deleteRecords(Iterable<UUID> documentIds) {
        recordsJpa.deleteAllById(documentIds);
    }
}
