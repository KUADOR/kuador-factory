package ec.kuador.board.testing;

import ec.kuador.board.issue.IssueApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Base para dobles de {@link IssueApi}: cada prueba sobrescribe solo lo que usa. */
public abstract class IssueApiStub implements IssueApi {

    @Override public Optional<IssueRef> find(String issueKey) { return Optional.empty(); }
    @Override public IssueRef createForImport(String projectKey, String title, UUID actorId) { throw new UnsupportedOperationException(); }
    @Override public Optional<IssueContext> context(String issueKey) { return Optional.empty(); }
    @Override public void addComment(String issueKey, UUID authorId, String body) { throw new UnsupportedOperationException(); }
    @Override public void reassign(String issueKey, UUID memberId, UUID actorId) { throw new UnsupportedOperationException(); }
    @Override public boolean setStoryPointsIfEmpty(String issueKey, int points, UUID actorId) { throw new UnsupportedOperationException(); }
    @Override public List<QueueItem> agentQueue(UUID supervisorId) { return List.of(); }
    @Override public Optional<UUID> supervisorOf(String issueKey) { return Optional.empty(); }
    @Override public Progress takeAs(String issueKey, UUID agentId) { throw new UnsupportedOperationException(); }
    @Override public Progress deliverAs(String issueKey, String comment, UUID agentId) { throw new UnsupportedOperationException(); }
    @Override public Progress sendBack(String issueKey, String column, String reason, UUID actorId) { throw new UnsupportedOperationException(); }

    @Override
    public List<IssueRef> createEpicTasks(String epicKey, List<PlannedTask> tasks, UUID actorId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<TaskInfo> epicTasks(String epicKey) {
        return List.of();
    }

    @Override
    public Optional<TaskInfo> taskInfo(String issueKey) {
        return Optional.empty();
    }
}
