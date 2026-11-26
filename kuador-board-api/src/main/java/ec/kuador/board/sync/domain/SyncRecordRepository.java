package ec.kuador.board.sync.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SyncRecordRepository {

    /** El repositorio de un proyecto (el mismo en todos los tableros que lo trabajan); {@code projectKey} no cuenta. */
    Optional<RepositoryLink> findLink(String projectKey, String subjectKey);

    /** Los repositorios de todos los proyectos del tablero. */
    java.util.List<RepositoryLink> findLinks(String projectKey);

    void saveLink(RepositoryLink link, String subjectKey);

    void deleteLink(String projectKey, String subjectKey);

    Map<UUID, SyncRecord> findRecords(Iterable<UUID> documentIds);

    void saveRecord(SyncRecord record);

    /** Al desvincular o cambiar de repositorio, la historia de sincronización deja de valer. */
    void deleteRecords(Iterable<UUID> documentIds);

    List<ExportPullRequest> findOpenPullRequests(String projectKey, String subjectKey);

    void savePullRequest(ExportPullRequest pullRequest, String subjectKey);

    void deletePullRequests(String projectKey, String subjectKey);
}
