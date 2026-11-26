package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCreated;
import ec.kuador.board.issue.IssueDeleted;
import ec.kuador.board.issue.IssueUpdated;
import ec.kuador.board.issue.domain.FieldChange;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Crear, editar, asignar y borrar una tarjeta, con sus eventos. */
final class IssueEditor extends IssueSupport {

    private final Gates gates;
    private final Supervision supervision;

    IssueEditor(Ports ports, Gates gates, Supervision supervision) {
        super(ports);
        this.gates = gates;
        this.supervision = supervision;
    }

    Issue create(String projectKey, IssueDetails details, UUID actorId, String status) {
        ProjectView project = project(projectKey);
        validateReferences(project, details, null, null);
        // Los datos propios se validan contra los del tipo en este tablero; lo que no está definido se descarta.
        details = details.withFields(ec.kuador.board.catalog.FieldDef.clean(project.fieldsOf(details.type()), details.fields()))
                .withSubject(subjectFor(projectKey, details, null));
        if (actorId != null) {
            requireMember(actorId);
        }
        // Sin columna indicada, el issue entra en la primera del flujo.
        String column = status == null || status.isBlank() ? project.columns().getFirst().key() : requireColumn(project, status).key();
        int number = projects.allocateIssueNumber(projectKey);
        Instant now = now();
        Issue created = Issue.create(projectKey, number, details, actorId, column, issues.nextPosition(projectKey, column), now);
        // Crear la tarjeta ya pasada una puerta sería saltársela: lo hace solo quien la aprueba.
        if (actorId != null) gates.requireGatesCrossed(project, null, column, actorId, created);
        supervision.superviseIfPersonHandsToAgents(created, project, actorId);
        Issue issue = issues.save(created);
        events.publishEvent(new IssueCreated(UUID.randomUUID(), TenantContext.require(), issue.key(), projectKey, actorId, now,
                issue.title(), issue.type().name(), issue.status(), requireColumn(project, column).category().name()));
        if (issue.assigneeId() != null) {
            events.publishEvent(new IssueAssigned(UUID.randomUUID(), TenantContext.require(), issue.key(), projectKey, actorId, now, null, issue.assigneeId()));
        }
        return issue;
    }

    Issue edit(String key, IssueDetails details, UUID actorId) {
        Issue issue = load(key);
        validateReferences(project(issue.projectKey()), details, issue.key(), issue.assigneeId());
        // Sin datos propios en el pedido se conservan los que tiene; al cambiar de tipo quedan los que el nuevo tipo define.
        details = details.withFields(ec.kuador.board.catalog.FieldDef.clean(project(issue.projectKey()).fieldsOf(details.type()),
                details.fields() == null ? issue.fields() : details.fields()));
        details = details.withSubject(subjectFor(issue.projectKey(), details, issue));
        String previousSubject = issue.subjectKey();
        UUID previousAssignee = issue.assigneeId();
        Instant now = now();
        List<FieldChange> changes = issue.edit(details, now);
        // Las tarjetas de una épica se trabajan en su mismo proyecto.
        if (issue.type() == IssueType.EPIC && !Objects.equals(previousSubject, issue.subjectKey())) {
            for (Issue child : issues.findChildren(issue.key())) {
                String before = child.subjectKey();
                child.subject(issue.subjectKey());
                issues.save(child);
                events.publishEvent(new IssueUpdated(UUID.randomUUID(), TenantContext.require(), child.key(), child.projectKey(), actorId, now,
                        List.of(new IssueUpdated.Change("subject", before, child.subjectKey()))));
            }
        }
        if (!Objects.equals(previousAssignee, issue.assigneeId())) supervision.superviseIfPersonHandsToAgents(issue, project(issue.projectKey()), actorId);
        Issue saved = issues.save(issue);
        if (!changes.isEmpty()) {
            events.publishEvent(new IssueUpdated(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now,
                    changes.stream().map(c -> new IssueUpdated.Change(c.field(), c.from(), c.to())).toList()));
        }
        publishAssignmentIfChanged(issue, previousAssignee, actorId, now);
        return saved;
    }

    Issue assign(String key, UUID memberId, UUID actorId) {
        Issue issue = load(key);
        if (memberId != null) {
            requireOnBoard(memberId, issue.projectKey());
        }
        UUID previousAssignee = issue.assigneeId();
        Instant now = now();
        issue.assign(memberId, now);
        supervision.superviseIfPersonHandsToAgents(issue, project(issue.projectKey()), actorId);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(issue, previousAssignee, actorId, now);
        return saved;
    }

    void delete(String key, UUID actorId) {
        Issue issue = load(key);
        issues.delete(issue);
        events.publishEvent(new IssueDeleted(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now(), issue.title()));
    }

    /**
     * El proyecto en que queda la tarjeta: el de su épica si tiene una; si no, el que se pidió (debe atenderlo el
     * tablero), el que ya tenía o el primero del tablero.
     */
    private String subjectFor(String projectKey, IssueDetails d, Issue current) {
        if (d.parentKey() != null) {
            Optional<Issue> parent = issues.findByKey(d.parentKey());
            if (parent.isPresent()) return projects.subjectKey(projectKey, parent.get().subjectKey());
        }
        String wanted = d.subjectKey() == null || d.subjectKey().isBlank() ? null : d.subjectKey().strip().toUpperCase(java.util.Locale.ROOT);
        if (wanted != null && projects.subjectsOf(projectKey).stream().noneMatch(s -> s.key().equals(wanted))) {
            throw new DomainException("Este tablero no trabaja en el proyecto " + wanted + ": súmalo en la configuración del tablero.");
        }
        if (wanted != null) return wanted;
        return current == null ? projects.currentSubject(projectKey) : projects.subjectKey(projectKey, current.subjectKey());
    }

    /** {@code currentAssignee}: quien ya lo tenía (puede seguir aunque haya salido del tablero). */
    void validateReferences(ProjectView project, IssueDetails d, String selfKey, UUID currentAssignee) {
        String projectKey = project.key();
        if (d.type() != null && !project.allows(d.type())) {
            throw new DomainException("Este proyecto no usa el tipo " + d.type() + "; usa "
                    + project.issueTypes().stream().map(t -> t.label() + " (" + t.type() + ")").toList() + ".");
        }
        if (d.assigneeId() != null && !d.assigneeId().equals(currentAssignee)) {
            requireOnBoard(d.assigneeId(), projectKey);
        }
        if (d.parentKey() != null) {
            Issue parent = issues.findByKey(d.parentKey())
                    .orElseThrow(() -> new DomainException("El issue padre " + d.parentKey() + " no existe."));
            if (!parent.projectKey().equals(projectKey)) {
                throw new DomainException("El padre debe ser del mismo proyecto.");
            }
            if (parent.type() != IssueType.EPIC) {
                throw new DomainException("Solo una épica puede ser padre de otros issues.");
            }
            if (parent.key().equals(selfKey)) {
                throw new DomainException("Un issue no puede ser su propio padre.");
            }
        }
    }
}
