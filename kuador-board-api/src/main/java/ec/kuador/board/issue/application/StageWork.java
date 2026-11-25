package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi.Progress;
import ec.kuador.board.issue.IssueUpdated;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** El trabajo de una etapa: tomar, entregar, devolver, y aprobar o rechazar en una puerta. */
final class StageWork extends IssueSupport {

    private final Gates gates;
    private final StageEvidence evidence;
    private final IssueMover mover;
    private final EpicTasks tasks;

    StageWork(Ports ports, Gates gates, StageEvidence evidence, IssueMover mover, EpicTasks tasks) {
        super(ports);
        this.gates = gates;
        this.evidence = evidence;
        this.mover = mover;
        this.tasks = tasks;
    }

    /** Toma un issue sin asignar de una etapa: solo quien tenga uno de sus roles responsables. */
    Issue take(String key, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        if (issue.assigneeId() != null) {
            if (issue.assigneeId().equals(actorId)) return issue;
            String who = members.find(issue.assigneeId()).map(MemberView::name).orElse("otra persona");
            throw new ConflictException(issue.key() + " ya lo tomó " + who + ".");
        }
        List<String> pending = tasks.pendingDependencies(issue, project);
        if (!pending.isEmpty()) {
            throw new ConflictException(issue.key() + " depende de " + String.join(", ", pending) + ", que todavía no pasó su revisión (llega a la última puerta del flujo).");
        }
        Optional<StageDef> stage = project.stage(issue.status());
        if (tasks.inWorkColumn(issue)) {
            // Tarea de una épica en desarrollo: la toma quien tiene su rol.
            if (!actor.role().equals(issue.workRole()) && actor.agent()) {
                throw new ForbiddenException(issue.key() + " es una tarea de " + roleNames(List.of(issue.workRole())) + ".");
            }
        } else if (stage.isPresent()) {
            // En una puerta trabajan sus roles (p. ej. Release prepara) y la toman sus aprobadores.
            boolean preparesGate = stage.get().gate() && !stage.get().roles().isEmpty() && stage.get().roles().contains(actor.role());
            if (stage.get().gate() && !preparesGate) {
                gates.requireApprover(project, stage.get(), actorId, issue);
            } else if (!stage.get().coveredBy(actor.role()) && !(issue.type() == IssueType.EPIC && stage.get().worksEpicsFirst(actor.role()))) {
                throw new ForbiddenException("«" + columnName(project, issue.status()) + "» la cubren: " + roleNames(stage.get().roles()) + ".");
            }
        }
        Instant now = now();
        issue.assign(actorId, now);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(issue, null, actorId, now);
        return saved;
    }

    /** Entrega el trabajo de la etapa: el issue pasa a la siguiente que permita el flujo y queda abierto allí. */
    Issue deliver(String key, String comment, UUID actorId) {
        return deliver(key, null, comment, actorId);
    }

    /** Entrega a {@code column} (o, si es nula, a la siguiente etapa permitida); debe estar más adelante en el flujo. */
    Issue deliver(String key, String column, String comment, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isPresent() && stage.get().gate()) {
            throw new DomainException("«" + columnName(project, issue.status()) + "» es una puerta: se aprueba o se rechaza.");
        }
        if (actor.agent() && !actorId.equals(issue.assigneeId())) {
            throw new ForbiddenException("Un agente solo entrega los issues que tomó.");
        }
        if (column == null && actor.agent() && issue.type() == IssueType.EPIC && stage.isPresent() && stage.get().worksEpicsFirst(actor.role())) {
            return handOverInStage(issue, project, comment, actorId);
        }
        ColumnDef next = column != null ? requireColumn(project, column) : project.nextAfter(issue.status())
                .orElseThrow(() -> new DomainException("Desde «" + columnName(project, issue.status()) + "» no hay una etapa siguiente permitida."));
        if (column != null && project.indexOf(next.key()) <= project.indexOf(issue.status())) {
            throw new DomainException("«" + next.name() + "» no está después de «" + columnName(project, issue.status()) + "».");
        }
        stage.ifPresent(st -> evidence.requireEvidence(project, st, issue, comment));
        if (comment != null && !comment.isBlank()) saveComment(issue, actorId, comment.strip(), now());
        Issue moved = mover.move(key, next.key(), Integer.MAX_VALUE, actorId);
        if (actor.agent() && actorId.equals(moved.assigneeId())) {
            // La columna siguiente no tiene etapa: ningún rol la cubre, así que el agente no se queda con el
            // issue (volvería a sus pendientes). Pasa a la persona que lo supervisa, que decide qué sigue.
            Instant now = now();
            moved.assign(moved.supervisorId(), now);
            Issue saved = issues.save(moved);
            publishAssignmentIfChanged(saved, actorId, actorId, now);
            return saved;
        }
        return moved;
    }

    /** Cierra una puerta: aprobar pasa a la siguiente etapa; rechazar devuelve el issue con un motivo. */
    Issue review(String key, boolean approve, String comment, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        StageDef gate = project.stage(issue.status()).filter(StageDef::gate)
                .orElseThrow(() -> new DomainException("«" + columnName(project, issue.status()) + "» no es una puerta de aprobación."));
        MemberView person = gates.requireApprover(project, gate, actorId, issue);
        String target;
        if (approve) {
            evidence.requireEvidence(project, gate, issue);
            target = project.nextAfter(issue.status()).map(ColumnDef::key)
                    .orElseThrow(() -> new DomainException("Desde «" + columnName(project, issue.status()) + "» no hay una etapa siguiente permitida."));
            saveComment(issue, actorId, "**Aprobado** por " + person.name() + "." + (comment == null || comment.isBlank() ? "" : "\n\n" + comment.strip()), now());
        } else {
            if (comment == null || comment.isBlank()) throw new DomainException("Para rechazar, explica qué hay que corregir.");
            target = gate.rejectTo() != null ? gate.rejectTo() : previousColumn(project, issue.status())
                    .orElseThrow(() -> new DomainException("No hay a qué etapa devolver el issue: configura «Rechazar a» en la puerta."));
            saveComment(issue, actorId, "**Rechazado** por " + person.name() + ": " + comment.strip(), now());
        }
        return mover.move(key, target, Integer.MAX_VALUE, actorId);
    }

    /**
     * Un rol que trabaja las épicas antes en la etapa (p. ej. UI/UX en Diseño) terminó su parte: la épica sigue en la
     * misma columna, sin asignar, para el rol que viene (otro previo o los de la etapa). La cadena de agentes la toma.
     */
    Issue handOverInStage(Issue issue, ProjectView project, String comment, UUID actorId) {
        if (evidence.pendingFirstRole(issue, project, project.stage(issue.status()).orElseThrow()).filter(r -> r.equals(requireMember(actorId).role())).isPresent()) {
            throw new DomainException("Antes de entregar, escribe tu documento en " + issue.key() + ": es lo que deja tu parte lista para el rol que sigue.");
        }
        Instant now = now();
        if (comment != null && !comment.isBlank()) saveComment(issue, actorId, comment.strip(), now);
        issue.assign(null, now);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(saved, actorId, actorId, now);
        return saved;
    }

    /** Quién revisa esta tarjeta en las puertas: PERSON, AGENT o nulo (lo del tablero). Personas con permiso de edición. */
    Issue setReviewBy(String key, String reviewBy, UUID actorId) {
        Issue issue = load(key);
        MemberView actor = requireMember(actorId);
        if (actor.agent()) throw new ForbiddenException("Quién revisa una tarjeta lo decide una persona.");
        if ("AGENT".equals(reviewBy)) {
            // Dejarla al agente salta a quienes aprueban: solo lo decide alguien que podría aprobar esas puertas.
            ProjectView board = project(issue.projectKey());
            for (StageDef gate : board.stages()) {
                if (gate.gate() && gate.reviewFor(null) == StageDef.ReviewMode.PERSON && !gate.approvableBy(actor.role())) {
                    throw new ForbiddenException("Solo quien aprueba «" + columnName(board, gate.column()) + "» puede dejar esta tarjeta al agente revisor ("
                            + roleNames(gate.approvers()) + ").");
                }
            }
        }
        String before = issue.reviewBy();
        issue.reviewBy(reviewBy);
        if (java.util.Objects.equals(before, issue.reviewBy())) return issue;
        Instant now = now();
        // Ya en una puerta y ahora la revisa una persona: el agente revisor la suelta.
        ProjectView project = project(issue.projectKey());
        UUID previousAssignee = issue.assigneeId();
        project.stage(issue.status()).filter(StageDef::gate).ifPresent(gate -> {
            boolean byAgent = gate.reviewFor(issue.reviewBy()) != StageDef.ReviewMode.PERSON;
            if (!byAgent && Optional.ofNullable(issue.assigneeId()).flatMap(members::find).map(MemberView::agent).orElse(false)) issue.assign(null, now);
        });
        Issue saved = issues.save(issue);
        // La cadena de agentes lo oye: si ahora la revisa el agente y está libre en la puerta, se la asigna.
        events.publishEvent(new IssueUpdated(UUID.randomUUID(), TenantContext.require(), key, saved.projectKey(), actorId, now,
                List.of(new IssueUpdated.Change("reviewBy", before, saved.reviewBy()))));
        publishAssignmentIfChanged(saved, previousAssignee, actorId, now);
        return saved;
    }

    /** El agente revisor no puede decidir: comenta su análisis y deja la tarjeta a una persona que aprueba la puerta. */
    Progress escalate(String issueKey, String analysis, UUID agentId) {
        Issue issue = load(issueKey);
        ProjectView project = project(issue.projectKey());
        StageDef gate = project.stage(issue.status()).filter(StageDef::gate)
                .orElseThrow(() -> new DomainException("«" + columnName(project, issue.status()) + "» no es una puerta de aprobación."));
        Instant now = now();
        MemberView person = gates.approverFor(issue, gate);
        saveComment(issue, agentId, "🔎 **Revisión para una persona** — " + person.name() + ", esta la decides tú:\n\n" + analysis.strip(), now);
        UUID before = issue.assigneeId();
        issue.assign(person.id(), now);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(saved, before, agentId, now);
        return progress(saved);
    }

    /** Devuelve el issue a una etapa anterior con el motivo (p. ej. QA encontró fallas): suma una vuelta atrás. */
    Progress sendBack(String issueKey, String column, String reason, UUID actorId) {
        Issue issue = load(issueKey);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        ColumnDef to = requireColumn(project, column);
        if (project.indexOf(to.key()) >= project.indexOf(issue.status())) {
            throw new DomainException("«" + to.name() + "» no está antes de «" + columnName(project, issue.status()) + "»: para avanzar, entrega el issue.");
        }
        if (actor.agent() && !actorId.equals(issue.assigneeId())) {
            throw new ForbiddenException("Un agente solo devuelve los issues que tomó.");
        }
        if (reason == null || reason.isBlank()) throw new DomainException("Para devolver un issue, explica qué hay que corregir.");
        saveComment(issue, actorId, "**Devuelto a " + to.name() + "** por " + actor.name() + ": " + reason.strip(), now());
        return progress(mover.move(issueKey, to.key(), Integer.MAX_VALUE, actorId));
    }

    Optional<String> returnForConflict(String issueKey, String note, UUID actorId) {
        Optional<Issue> found = issues.findByKey(issueKey);
        if (found.isEmpty() || note == null || note.isBlank()) return Optional.empty();
        Issue issue = found.get();
        ProjectView project = project(issue.projectKey());
        if (project.column(issue.status()).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(true)) return Optional.empty();
        String body = "⚠️ **Conflicto con la rama destino**: " + note.strip();
        if (lastComment(issue).map(c -> c.body().endsWith(body)).orElse(false)) return Optional.empty();
        Optional<StageDef> gate = project.stage(issue.status()).filter(StageDef::gate);
        Optional<String> back = gate.flatMap(g -> Optional.ofNullable(g.rejectTo()).or(() -> previousColumn(project, issue.status())));
        if (back.isEmpty()) {
            saveComment(issue, actorId, body, now());
            return Optional.empty();
        }
        saveComment(issue, actorId, "**Devuelto a " + columnName(project, back.get()) + "**. " + body, now());
        mover.move(issueKey, back.get(), Integer.MAX_VALUE, actorId, true);
        return back.map(k -> columnName(project, k));
    }

    boolean branchIdle(String issueKey) {
        Optional<Issue> found = issues.findByKey(issueKey);
        if (found.isEmpty()) return false;
        ProjectView project = project(found.get().projectKey());
        String status = found.get().status();
        if (project.column(status).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(true)) return false;
        // Una columna sin etapa o que cubre cualquiera puede tener a alguien programando: no se toca.
        return project.stage(status)
                .map(st -> st.gate() || (!st.roles().isEmpty() && st.roles().stream().noneMatch(this::writesCode)))
                .orElse(false);
    }

    Progress progress(Issue issue) {
        String name = projects.find(issue.projectKey()).map(p -> columnName(p, issue.status())).orElse(issue.status());
        return new Progress(issue.key(), issue.status(), name, issue.assigneeId());
    }
}
