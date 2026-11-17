package ec.kuador.board.testing;

import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.project.Transitions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Proyectos en memoria creados desde las plantillas de fábrica. */
public class InMemoryProjects implements ProjectApi {

    private final Map<String, ProjectView> all = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    /** Proyecto desde una plantilla de fábrica (SOFTWARE, SALES, TASKS) con metodología opcional. */
    public ProjectView add(String key, String templateKey, String methodology) {
        WorkflowTemplate t = InMemoryCatalog.factoryTemplate(templateKey);
        ProjectView p = new ProjectView(key, "Proyecto " + key, t.kind(), t.key(), methodology, t.columns(), t.issueTypes(), Transitions.FREE, t.stages(), 3);
        all.put(key, p);
        return p;
    }

    /** Restringe el flujo del proyecto a estas reglas. */
    public void restrict(String key, Transitions.Transition... allowed) {
        ProjectView p = all.get(key);
        all.put(key, new ProjectView(p.key(), p.name(), p.kind(), p.templateKey(), p.methodology(), p.columns(), p.issueTypes(),
                new Transitions(true, java.util.List.of(allowed)), p.stages(), p.maxRework()));
    }

    /** Configura las etapas (roles, puertas) y el freno de bucles del proyecto. */
    public void stages(String key, int maxRework, ec.kuador.board.catalog.StageDef... stages) {
        ProjectView p = all.get(key);
        all.put(key, new ProjectView(p.key(), p.name(), p.kind(), p.templateKey(), p.methodology(), p.columns(), p.issueTypes(),
                p.transitions(), java.util.List.of(stages), maxRework));
    }

    @Override
    public boolean exists(String projectKey) {
        return all.containsKey(projectKey);
    }

    @Override
    public Optional<ProjectView> find(String projectKey) {
        return Optional.ofNullable(all.get(projectKey));
    }

    @Override
    public String name(String projectKey) {
        return find(projectKey).map(ProjectView::name).orElse(projectKey);
    }

    @Override
    public int allocateIssueNumber(String projectKey) {
        return counters.merge(projectKey, 1, Integer::sum);
    }

    @Override
    public void createBoard(String key, String name, String templateKey) {
        throw new UnsupportedOperationException();
    }

    // Las pruebas unitarias no modelan personas por tablero: todos ven todo.
    @Override
    public boolean canAccess(java.util.UUID memberId, String projectKey) {
        return true;
    }

    @Override
    public java.util.Set<String> boardsOf(java.util.UUID memberId) {
        return all.keySet();
    }

    @Override
    public java.util.Set<String> agentBoardsOf(java.util.UUID agentId) {
        return all.keySet();
    }

    @Override
    public void addPerson(String projectKey, java.util.UUID memberId) {
    }
}
