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

    Optional<RepositoryLinkEntity> findByProjectKeyAndSubjectKey(String projectKey, String subjectKey);

    List<RepositoryLinkEntity> findByProjectKeyOrderByLinkedAt(String projectKey);
}

interface SpringDataSyncRecords extends JpaRepository<SyncRecordEntity, UUID> {}

interface SpringDataPullRequests extends JpaRepository<PullRequestEntity, UUID> {

    List<PullRequestEntity> findByProjectKeyAndSubjectKeyAndStateOrderByCreatedAtDesc(String projectKey, String subjectKey, PullRequestState state);

    void deleteByProjectKeyAndSubjectKey(String projectKey, String subjectKey);
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
        return pullRequests.findByProjectKeyAndSubjectKeyAndStateOrderByCreatedAtDesc(projectKey, subjectKey, PullRequestState.OPEN).stream()
                .map(PullRequestEntity::toDomain).toList();
    }

    @Override
    public void savePullRequest(ExportPullRequest pullRequest, String subjectKey) {
        pullRequests.save(PullRequestEntity.from(pullRequest, subjectKey));
    }

    @Override
    public void deletePullRequests(String projectKey, String subjectKey) {
        pullRequests.deleteByProjectKeyAndSubjectKey(projectKey, subjectKey);
    }

    @Override
    public Optional<RepositoryLink> findLink(String projectKey, String subjectKey) {
        return links.findByProjectKeyAndSubjectKey(projectKey, subjectKey).map(RepositoryLinkEntity::toDomain);
    }

    @Override
    public List<RepositoryLink> findLinks(String projectKey) {
        return links.findByProjectKeyOrderByLinkedAt(projectKey).stream().map(RepositoryLinkEntity::toDomain).toList();
    }

    // Un vínculo por proyecto del tablero: guardar reemplaza al anterior.
    @Override
    public void saveLink(RepositoryLink link, String subjectKey) {
        links.findByProjectKeyAndSubjectKey(link.projectKey(), subjectKey).ifPresent(existing -> {
            links.delete(existing);
            links.flush();
        });
        links.save(RepositoryLinkEntity.from(link, subjectKey));
    }

    @Override
    public void deleteLink(String projectKey, String subjectKey) {
        links.findByProjectKeyAndSubjectKey(projectKey, subjectKey).ifPresent(links::delete);
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
