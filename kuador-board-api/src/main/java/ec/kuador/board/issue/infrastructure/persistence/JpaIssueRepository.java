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

interface SpringDataIssueRepository extends JpaRepository<IssueEntity, UUID> {

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
}
