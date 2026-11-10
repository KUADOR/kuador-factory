package ec.kuador.board.sync.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SyncRecordRepository {

    Optional<RepositoryLink> findLink(String projectKey);

    void saveLink(RepositoryLink link);

    void deleteLink(String projectKey);

    Map<UUID, SyncRecord> findRecords(Iterable<UUID> documentIds);

    void saveRecord(SyncRecord record);

    /** Al desvincular o cambiar de repositorio, la historia de sincronización deja de valer. */
    void deleteRecords(Iterable<UUID> documentIds);

    List<ExportPullRequest> findOpenPullRequests(String projectKey);

    void savePullRequest(ExportPullRequest pullRequest);

    void deletePullRequests(String projectKey);
}
