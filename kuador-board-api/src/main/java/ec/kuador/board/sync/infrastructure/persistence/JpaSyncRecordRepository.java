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

    Optional<RepositoryLinkEntity> findByProjectKey(String projectKey);
}

interface SpringDataSyncRecords extends JpaRepository<SyncRecordEntity, UUID> {}

interface SpringDataPullRequests extends JpaRepository<PullRequestEntity, UUID> {

    List<PullRequestEntity> findByProjectKeyAndStateOrderByCreatedAtDesc(String projectKey, PullRequestState state);

    void deleteByProjectKey(String projectKey);
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
    public List<ExportPullRequest> findOpenPullRequests(String projectKey) {
        return pullRequests.findByProjectKeyAndStateOrderByCreatedAtDesc(projectKey, PullRequestState.OPEN).stream()
                .map(PullRequestEntity::toDomain).toList();
    }

    @Override
    public void savePullRequest(ExportPullRequest pullRequest) {
        pullRequests.save(PullRequestEntity.from(pullRequest));
    }

    @Override
    public void deletePullRequests(String projectKey) {
        pullRequests.deleteByProjectKey(projectKey);
    }

    @Override
    public Optional<RepositoryLink> findLink(String projectKey) {
        return links.findByProjectKey(projectKey).map(RepositoryLinkEntity::toDomain);
    }

    // Un vínculo por proyecto: guardar reemplaza al anterior.
    @Override
    public void saveLink(RepositoryLink link) {
        links.findByProjectKey(link.projectKey()).ifPresent(existing -> {
            links.delete(existing);
            links.flush();
        });
        links.save(RepositoryLinkEntity.from(link));
    }

    @Override
    public void deleteLink(String projectKey) {
        links.findByProjectKey(projectKey).ifPresent(links::delete);
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
