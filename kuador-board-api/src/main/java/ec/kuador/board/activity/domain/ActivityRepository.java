package ec.kuador.board.activity.domain;

import java.util.List;
import java.util.UUID;

public interface ActivityRepository {

    boolean existsByEventId(UUID eventId);

    void saveAll(List<ActivityEntry> entries);

    /** Más recientes primero. */
    List<ActivityEntry> findByIssue(String issueKey, int limit);

    /** Más recientes primero. */
    List<ActivityEntry> findByProject(String projectKey, int limit);
}
