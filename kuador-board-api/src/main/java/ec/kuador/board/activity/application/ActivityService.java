package ec.kuador.board.activity.application;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityRepository;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    public static final int MAX_LIMIT = 200;

    private final ActivityRepository activity;
    private final ProjectApi projects;

    ActivityService(ActivityRepository activity, ProjectApi projects) {
        this.activity = activity;
        this.projects = projects;
    }

    /** Incluye el historial de issues ya eliminados: la bitácora no se borra. */
    @Transactional(readOnly = true)
    public List<ActivityEntry> ofIssue(String issueKey, int limit) {
        return activity.findByIssue(issueKey, clamp(limit));
    }

    @Transactional(readOnly = true)
    public List<ActivityEntry> ofProject(String projectKey, int limit) {
        if (!projects.exists(projectKey)) {
            throw new NotFoundException("No existe el proyecto " + projectKey + ".");
        }
        return activity.findByProject(projectKey, clamp(limit));
    }

    private static int clamp(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}
