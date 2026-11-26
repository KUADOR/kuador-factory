package ec.kuador.board.issue.infrastructure.persistence;

import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.issue.domain.IssueRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

interface SpringDataIssueRepository extends JpaRepository<IssueEntity, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<IssueEntity> {

    @Query("select i from IssueEntity i where i.projectKey = :projectKey and i.status not in :statuses")
    List<IssueEntity> findOutside(String projectKey, java.util.Collection<String> statuses);

    List<IssueEntity> findByProjectKeyAndStatusOrderByUpdatedAtDesc(String projectKey, String status, org.springframework.data.domain.Limit limit);

    @Query("select i.status, count(i) from IssueEntity i where i.projectKey = :projectKey group by i.status")
    List<Object[]> countByStatus(String projectKey);

    @Query("""
            select i.parentKey, sum(case when i.status in :done then 1 else 0 end), count(i) from IssueEntity i
            where i.projectKey = :projectKey and i.parentKey is not null and i.workRole is not null group by i.parentKey
            """)
    List<Object[]> epicProgress(String projectKey, java.util.Collection<String> done);

    @Query("select i.key, i.title from IssueEntity i where i.key in :keys")
    List<Object[]> titles(java.util.Collection<String> keys);


    Optional<IssueEntity> findByKey(String key);

    boolean existsByKey(String key);

    List<IssueEntity> findByProjectKey(String projectKey);

    List<IssueEntity> findBySupervisorIdOrderByProjectKeyAscPositionAsc(UUID supervisorId);

    List<IssueEntity> findByProjectKeyAndStatusOrderByPosition(String projectKey, String status);

    @Query("select coalesce(max(i.position) + 1, 0) from IssueEntity i where i.projectKey = :projectKey and i.status = :status")
    int nextPosition(String projectKey, String status);

    long countByProjectKeyAndStatus(String projectKey, String status);

    List<IssueEntity> findByParentKeyOrderByCreatedAt(String parentKey);
}

interface SpringDataCommentRepository extends JpaRepository<CommentEntity, UUID> {

    @Query("select c from CommentEntity c where c.issueId = :issueId order by c.createdAt")
    List<CommentEntity> findByIssue(UUID issueId);
}

@Repository
class JpaIssueRepository implements IssueRepository {

    private static final Comparator<Issue> BOARD_ORDER =
            Comparator.comparingInt(Issue::position).thenComparing(Issue::createdAt);

    private final SpringDataIssueRepository jpa;
    private final SpringDataCommentRepository comments;

    JpaIssueRepository(SpringDataIssueRepository jpa, SpringDataCommentRepository comments) {
        this.jpa = jpa;
        this.comments = comments;
    }

    @Override
    public Optional<Issue> findByKey(String key) {
        return jpa.findByKey(key).map(IssueEntity::toDomain);
    }

    @Override
    public List<Issue> findChildren(String parentKey) {
        return jpa.findByParentKeyOrderByCreatedAt(parentKey).stream().map(IssueEntity::toDomain).toList();
    }

    @Override
    public boolean existsByKey(String key) {
        return jpa.existsByKey(key);
    }

    @Override
    public List<Issue> findByProject(String projectKey, IssueFilter filter) {
        String text = filter.text() == null || filter.text().isBlank() ? null : filter.text().strip().toLowerCase(Locale.ROOT);
        return jpa.findByProjectKey(projectKey).stream()
                .map(IssueEntity::toDomain)
                .filter(i -> filter.assigneeId() == null || filter.assigneeId().equals(i.assigneeId()))
                .filter(i -> filter.type() == null || filter.type() == i.type())
                .filter(i -> text == null
                        || i.title().toLowerCase(Locale.ROOT).contains(text)
                        || i.key().toLowerCase(Locale.ROOT).contains(text)
                        || i.labels().contains(text))
                .sorted(BOARD_ORDER)
                .toList();
    }

    @Override
    public List<Issue> findOutside(String projectKey, List<String> statuses) {
        List<IssueEntity> found = statuses.isEmpty() ? jpa.findByProjectKey(projectKey) : jpa.findOutside(projectKey, statuses);
        return found.stream().map(IssueEntity::toDomain).sorted(BOARD_ORDER).toList();
    }

    @Override
    public List<Issue> findLatestInColumn(String projectKey, String status, int limit) {
        return jpa.findByProjectKeyAndStatusOrderByUpdatedAtDesc(projectKey, status, org.springframework.data.domain.Limit.of(limit)).stream()
                .map(IssueEntity::toDomain).toList();
    }

    @Override
    public Map<String, Long> countByStatus(String projectKey) {
        return jpa.countByStatus(projectKey).stream().collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));
    }

    @Override
    public Map<String, long[]> epicProgress(String projectKey, List<String> doneStatuses) {
        // Sin columnas de cierre, ninguna está hecha: una clave que no es de ninguna columna.
        java.util.Collection<String> done = doneStatuses.isEmpty() ? List.of("") : doneStatuses;
        return jpa.epicProgress(projectKey, done).stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()}));
    }

    @Override
    public Map<String, String> titles(java.util.Collection<String> keys) {
        if (keys.isEmpty()) return Map.of();
        return jpa.titles(keys).stream().collect(Collectors.toMap(r -> (String) r[0], r -> (String) r[1]));
    }

    @Override
    public ec.kuador.board.issue.domain.IssuePage search(String projectKey, ec.kuador.board.issue.domain.IssueQuery query) {
        org.springframework.data.jpa.domain.Specification<IssueEntity> spec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> where = new java.util.ArrayList<>();
            where.add(cb.equal(root.get("projectKey"), projectKey));
            if (query.text() != null) {
                String like = "%" + query.text().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("key")), like, '\\')));
            }
            if (!query.statuses().isEmpty()) where.add(root.get("status").in(query.statuses()));
            if (query.assigneeId() != null) where.add(cb.equal(root.get("assigneeId"), query.assigneeId()));
            if (query.type() != null) where.add(cb.equal(root.get("type"), query.type()));
            if (query.subjectKey() != null) where.add(cb.equal(root.get("subjectKey"), query.subjectKey()));
            if (query.label() != null) {
                // array_position de Hibernate: la posición (desde 1) o 0 si no está.
                where.add(cb.gt(cb.function("array_position", Integer.class, root.get("labels"), cb.literal(query.label().toLowerCase(Locale.ROOT))), 0));
            }
            // El orden va aquí y no en la página: la prioridad se ordena por importancia, no por su nombre.
            if (Long.class != cq.getResultType() && long.class != cq.getResultType()) {
                jakarta.persistence.criteria.Expression<?> by = switch (query.sort()) {
                    case "key", "created" -> root.get("createdAt");
                    case "title" -> cb.lower(root.get("title"));
                    case "type" -> root.get("type");
                    case "status" -> root.get("status");
                    case "priority" -> {
                        var rank = cb.selectCase(root.<Object>get("priority"));
                        for (ec.kuador.board.issue.domain.Priority p : ec.kuador.board.issue.domain.Priority.values()) rank = rank.when(p, p.ordinal());
                        yield rank.otherwise(99);
                    }
                    default -> root.get("updatedAt");
                };
                cq.orderBy(query.descending() ? cb.desc(by) : cb.asc(by), cb.asc(root.get("createdAt")));
            }
            return cb.and(where.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var page = jpa.findAll(spec, org.springframework.data.domain.PageRequest.of(query.page(), query.size()));
        return new ec.kuador.board.issue.domain.IssuePage(page.getContent().stream().map(IssueEntity::toDomain).toList(), page.getTotalElements(),
                query.page(), query.size());
    }

    @Override
    public List<Issue> findColumn(String projectKey, String status) {
        return jpa.findByProjectKeyAndStatusOrderByPosition(projectKey, status).stream().map(IssueEntity::toDomain).toList();
    }

    @Override
    public List<Issue> findBySupervisor(UUID supervisorId) {
        return jpa.findBySupervisorIdOrderByProjectKeyAscPositionAsc(supervisorId).stream().map(IssueEntity::toDomain).toList();
    }

    @Override
    public int nextPosition(String projectKey, String status) {
        return jpa.nextPosition(projectKey, status);
    }

    @Override
    public long countInColumn(String projectKey, String status) {
        return jpa.countByProjectKeyAndStatus(projectKey, status);
    }

    @Override
    public Issue save(Issue issue) {
        IssueEntity entity = jpa.findById(issue.id())
                .map(e -> { e.copyFrom(issue); return e; })
                .orElseGet(() -> IssueEntity.newFrom(issue));
        return jpa.save(entity).toDomain();
    }

    @Override
    public void saveAll(List<Issue> issues) {
        Map<UUID, IssueEntity> existing = jpa.findAllById(issues.stream().map(Issue::id).toList()).stream()
                .collect(Collectors.toMap(IssueEntity::id, Function.identity()));
        jpa.saveAll(issues.stream().map(i -> {
            IssueEntity e = existing.get(i.id());
            if (e == null) {
                return IssueEntity.newFrom(i);
            }
            e.copyFrom(i);
            return e;
        }).toList());
    }

    @Override
    public void delete(Issue issue) {
        jpa.deleteById(issue.id());
    }

    @Override
    public List<Comment> findComments(Issue issue) {
        return comments.findByIssue(issue.id()).stream().map(CommentEntity::toDomain).toList();
    }

    @Override
    public Comment saveComment(Comment comment) {
        return comments.save(CommentEntity.from(comment)).toDomain();
    }

    @Override
    public java.util.Optional<Comment> findComment(UUID commentId) {
        return comments.findById(commentId).map(CommentEntity::toDomain);
    }

    @Override
    public void deleteComment(UUID commentId) {
        comments.deleteById(commentId);
    }
}
