package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.BranchSyncApi;
import ec.kuador.board.issue.BranchSynced;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ver {@link BranchSyncApi}. Un pedido por tarjeta, en {@code branch_sync}. */
@Service
class BranchSyncs implements BranchSyncApi {

    /** Intentos del runner antes de devolver la tarjeta a Desarrollo. */
    static final int MAX_ATTEMPTS = 2;

    private final JdbcClient jdbc;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BranchSyncs(JdbcClient jdbc, IssueApi issues, ProjectApi projects, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.issues = issues;
        this.projects = projects;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean request(String issueKey, Conflict conflict, String note, UUID actorId) {
        Optional<IssueApi.IssueContext> card = issues.context(issueKey);
        if (card.isEmpty() || conflict == null || conflict.branch() == null || conflict.branch().isBlank()) return false;
        if (!issues.branchIdle(issueKey)) return false; // en desarrollo: su autor trae los cambios al subir los suyos
        ProjectView project = projects.find(card.get().projectKey()).orElse(null);
        if (project == null || !project.software()) return false;
        Optional<String> role = coder(project, issueKey);
        if (role.isEmpty()) return false;
        if (pending(issueKey).isPresent()) return true; // ya pedido: no se repite el aviso
        jdbc.sql("""
                insert into branch_sync (workspace_id, issue_key, project_key, repository, pr_number, branch, base_branch, role, note, requested_at, attempts)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (workspace_id, issue_key) do nothing""")
                .params(TenantContext.require(), issueKey, project.key(), conflict.repository(), conflict.number(), conflict.branch(), conflict.baseBranch(),
                        role.get(), note, Timestamp.from(clock.instant()))
                .update();
        String roleName = catalog.role(role.get()).map(AgentRole::name).orElse(role.get());
        issues.addComment(issueKey, actorId, "🔀 **Con conflictos**: " + note.strip() + "\n\nLa tarjeta se queda aquí: el agente " + roleName
                + " actualiza la rama en su runner (trae «" + conflict.baseBranch() + "», resuelve y prueba). Si no puede, vuelve a desarrollo.");
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchSync> pending(String issueKey) {
        return jdbc.sql(SELECT + " where workspace_id = ? and issue_key = ?").params(TenantContext.require(), issueKey).query(BranchSyncs::row).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchSync> pending() {
        return jdbc.sql(SELECT + " where workspace_id = ? order by requested_at").param(TenantContext.require()).query(BranchSyncs::row).list();
    }

    @Override
    @Transactional
    public void resolved(String issueKey, String detail, UUID actorId) {
        Optional<BranchSync> sync = pending(issueKey);
        if (sync.isEmpty()) return;
        delete(issueKey);
        issues.addComment(issueKey, actorId, "🔀 Rama actualizada con «" + sync.get().baseBranch() + "»: el PR " + sync.get().repository() + " #"
                + sync.get().number() + " ya no tiene conflictos." + (detail == null || detail.isBlank() ? "" : "\n\n" + detail.strip())
                + "\n\nAl revisar otra vez, basta mirar la resolución del merge.");
        events.publishEvent(new BranchSynced(TenantContext.require(), issueKey, sync.get().projectKey()));
    }

    @Override
    @Transactional
    public void failed(String issueKey, String reason, boolean giveUp, UUID actorId) {
        Optional<BranchSync> sync = pending(issueKey);
        if (sync.isEmpty()) return;
        int attempts = sync.get().attempts() + 1;
        if (!giveUp && attempts < MAX_ATTEMPTS) {
            jdbc.sql("update branch_sync set attempts = ? where workspace_id = ? and issue_key = ?").params(attempts, TenantContext.require(), issueKey).update();
            return;
        }
        delete(issueKey);
        // Como antes de este pedido: vuelve a quien la desarrolló con el motivo.
        String why = "el runner no pudo actualizar la rama (" + (reason == null || reason.isBlank() ? "sin detalle" : reason.strip()) + "). "
                + sync.get().note();
        issues.returnForConflict(issueKey, why, actorId);
    }

    private void delete(String issueKey) {
        jdbc.sql("delete from branch_sync where workspace_id = ? and issue_key = ?").params(TenantContext.require(), issueKey).update();
    }

    /** El rol que programa la tarjeta: el de su tarea, si escribe código; si no, el primero que programa en el flujo. */
    private Optional<String> coder(ProjectView project, String issueKey) {
        Optional<String> own = issues.taskInfo(issueKey).map(IssueApi.TaskInfo::workRole).filter(this::writesCode);
        if (own.isPresent()) return own;
        for (ColumnDef c : project.columns()) {
            Optional<StageDef> st = project.stage(c.key());
            if (st.isEmpty() || st.get().gate()) continue;
            Optional<String> role = st.get().roles().stream().filter(this::writesCode).findFirst();
            if (role.isPresent()) return role;
        }
        return Optional.empty();
    }

    private boolean writesCode(String role) {
        return role != null && catalog.role(role).map(AgentRole::worksOnCode).orElse(false);
    }

    private static final String SELECT = """
            select issue_key, project_key, repository, pr_number, branch, base_branch, role, note, requested_at, attempts from branch_sync""";

    private static BranchSync row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new BranchSync(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getInt(10));
    }
}
