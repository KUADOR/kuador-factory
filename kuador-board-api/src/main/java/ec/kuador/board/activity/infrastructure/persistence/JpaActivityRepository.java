package ec.kuador.board.activity.infrastructure.persistence;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    boolean existsByEventId(UUID eventId);

    // A igual instante (varias filas de un mismo evento), id no sirve para ordenar; el desempate
    // por field mantiene un orden estable entre llamadas.
    List<ActivityEntity> findByIssueKeyOrderByOccurredAtDescFieldAsc(String issueKey, Limit limit);

    List<ActivityEntity> findByProjectKeyOrderByOccurredAtDescFieldAsc(String projectKey, Limit limit);
}

@Repository
class JpaActivityRepository implements ActivityRepository {

    private final SpringDataActivityRepository jpa;

    JpaActivityRepository(SpringDataActivityRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        return jpa.existsByEventId(eventId);
    }

    @Override
    public void saveAll(List<ActivityEntry> entries) {
        jpa.saveAll(entries.stream().map(ActivityEntity::from).toList());
    }

    @Override
    public List<ActivityEntry> findByIssue(String issueKey, int limit) {
        return jpa.findByIssueKeyOrderByOccurredAtDescFieldAsc(issueKey, Limit.of(limit)).stream().map(ActivityEntity::toDomain).toList();
    }

    @Override
    public List<ActivityEntry> findByProject(String projectKey, int limit) {
        return jpa.findByProjectKeyOrderByOccurredAtDescFieldAsc(projectKey, Limit.of(limit)).stream().map(ActivityEntity::toDomain).toList();
    }
}
