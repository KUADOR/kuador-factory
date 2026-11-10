package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.project.domain.Project;
import ec.kuador.board.project.domain.ProjectRepository;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/*
 * Las consultas derivadas las filtra Hibernate por espacio (@TenantId). Las de modificación y la
 * nativa llevan el espacio explícito: no dependen de que el filtro alcance a esas sentencias.
 */
interface SpringDataProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    Optional<ProjectEntity> findByKey(String key);

    boolean existsByKey(String key);

    long countByMethodology(String methodology);

    @Query(value = """
            UPDATE project SET next_issue_number = next_issue_number + 1
            WHERE workspace_id = :workspaceId AND project_key = :key
            RETURNING next_issue_number - 1
            """, nativeQuery = true)
    Optional<Integer> incrementIssueNumber(UUID workspaceId, String key);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProjectEntity p set p.methodology = :methodology where p.workspaceId = :workspaceId and p.key = :key")
    int setMethodology(UUID workspaceId, String key, String methodology);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProjectEntity p set p.name = :name, p.description = :description where p.workspaceId = :workspaceId and p.key = :key")
    int setDetails(UUID workspaceId, String key, String name, String description);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProjectEntity p set p.transitionsRestricted = :restricted, p.transitions = :transitions where p.workspaceId = :workspaceId and p.key = :key")
    int setTransitions(UUID workspaceId, String key, boolean restricted, String transitions);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProjectEntity p set p.stages = :stages, p.maxRework = :maxRework where p.workspaceId = :workspaceId and p.key = :key")
    int setStages(UUID workspaceId, String key, String stages, int maxRework);
}

interface SpringDataColumnRepository extends JpaRepository<ColumnEntity, UUID> {

    List<ColumnEntity> findByProjectKeyOrderByPosition(String projectKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ColumnEntity c where c.workspaceId = :workspaceId and c.projectKey = :projectKey")
    void deleteByProject(UUID workspaceId, String projectKey);
}

@Repository
class JpaProjectRepository implements ProjectRepository {

    private final SpringDataProjectRepository jpa;
    private final SpringDataColumnRepository columns;

    JpaProjectRepository(SpringDataProjectRepository jpa, SpringDataColumnRepository columns) {
        this.jpa = jpa;
        this.columns = columns;
    }

    @Override
    public List<Project> findAll() {
        return jpa.findAll(Sort.by("key")).stream().map(ProjectEntity::toDomain).toList();
    }

    @Override
    public Optional<Project> findByKey(String key) {
        return jpa.findByKey(key).map(ProjectEntity::toDomain);
    }

    @Override
    public boolean existsByKey(String key) {
        return jpa.existsByKey(key);
    }

    @Override
    public Project save(Project project) {
        return jpa.save(ProjectEntity.from(project)).toDomain();
    }

    @Override
    public void updateMethodology(String projectKey, String methodology) {
        jpa.setMethodology(TenantContext.require(), projectKey, methodology);
    }

    @Override
    public void updateDetails(String projectKey, String name, String description) {
        jpa.setDetails(TenantContext.require(), projectKey, name, description);
    }

    @Override
    public void updateTransitions(String projectKey, ec.kuador.board.project.Transitions transitions) {
        jpa.setTransitions(TenantContext.require(), projectKey, transitions.restricted(), ec.kuador.board.shared.json.Json.write(transitions.allowed()));
    }

    @Override
    public void updateStages(String projectKey, java.util.List<ec.kuador.board.catalog.StageDef> stages, int maxRework) {
        jpa.setStages(TenantContext.require(), projectKey, ec.kuador.board.shared.json.Json.write(stages), maxRework);
    }

    @Override
    public long countUsingMethodology(String methodology) {
        return jpa.countByMethodology(methodology);
    }

    @Override
    public List<ColumnDef> findColumns(String projectKey) {
        return columns.findByProjectKeyOrderByPosition(projectKey).stream().map(ColumnEntity::toDomain).toList();
    }

    // Borrar y reinsertar: el flujo es pequeño (máx. 12 columnas) y así el orden y las claves quedan exactos.
    @Override
    public void saveColumns(String projectKey, List<ColumnDef> list) {
        columns.deleteByProject(TenantContext.require(), projectKey);
        columns.saveAll(IntStream.range(0, list.size()).mapToObj(i -> ColumnEntity.from(projectKey, list.get(i), i)).toList());
    }

    @Override
    public Optional<Integer> nextIssueNumber(String key) {
        return jpa.incrementIssueNumber(TenantContext.require(), key);
    }
}
