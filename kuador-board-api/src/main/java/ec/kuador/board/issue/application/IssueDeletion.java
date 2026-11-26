package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.IssueDeleted;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Borrar tarjetas, una o varias, sin dejar el tablero roto. No se borra una tarjeta que un agente está trabajando, una
 * épica con tareas que ya arrancaron ni una tarea de la que depende otra que se queda. Las tareas que todavía esperan
 * se borran con su épica. Lo que se puede borrar se borra; lo demás vuelve con el motivo.
 */
final class IssueDeletion extends IssueSupport {

    /** {@code deleted}: lo que se borra (lo pedido); {@code withEpics}: tareas en espera que se van con su épica. */
    record Plan(List<String> deleted, List<String> withEpics, Map<String, String> blocked) {}

    private final StageEvidence evidence;

    IssueDeletion(Ports ports, StageEvidence evidence) {
        super(ports);
        this.evidence = evidence;
    }

    /** Qué pasaría al borrar {@code keys}, sin borrar nada. */
    Plan plan(Collection<String> keys) {
        Map<String, Issue> wanted = new LinkedHashMap<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        for (String key : new LinkedHashSet<>(keys)) {
            Optional<Issue> issue = issues.findByKey(key);
            if (issue.isPresent()) wanted.put(key, issue.get());
            else blocked.put(key, "ya no existe");
        }
        // Un bloqueo puede arrastrar a otra (la épica de una tarea que se queda, la tarea de la que depende otra que se
        // queda): se repite hasta que nada cambie.
        Map<String, List<Issue>> tasksOf = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Issue issue : List.copyOf(wanted.values())) {
                Optional<String> reason = reason(issue, wanted.keySet(), tasksOf);
                if (reason.isPresent()) {
                    wanted.remove(issue.key());
                    blocked.put(issue.key(), reason.get());
                    changed = true;
                }
            }
        }
        List<String> withEpics = new ArrayList<>();
        for (String key : wanted.keySet()) {
            for (Issue task : tasksOf.getOrDefault(key, List.of())) {
                if (!wanted.containsKey(task.key()) && !withEpics.contains(task.key())) withEpics.add(task.key());
            }
        }
        return new Plan(List.copyOf(wanted.keySet()), withEpics, blocked);
    }

    /** Borra lo que se puede de {@code keys} (y las tareas en espera de sus épicas). */
    Plan delete(Collection<String> keys, UUID actorId) {
        Plan plan = plan(keys);
        for (String key : plan.withEpics()) remove(key, actorId);
        for (String key : plan.deleted()) remove(key, actorId);
        return plan;
    }

    /** Una sola tarjeta: si no se puede, el motivo como error. */
    void deleteOne(String key, UUID actorId) {
        load(key);
        Plan plan = delete(List.of(key), actorId);
        String reason = plan.blocked().get(key);
        if (reason != null) throw new DomainException("No se puede borrar " + key + ": " + reason + ".");
    }

    private void remove(String key, UUID actorId) {
        issues.findByKey(key).ifPresent(issue -> {
            issues.delete(issue);
            events.publishEvent(new IssueDeleted(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now(), issue.title()));
        });
    }

    /** Por qué no se puede borrar {@code issue} si se borra todo {@code going}; vacío si se puede. */
    private Optional<String> reason(Issue issue, Set<String> going, Map<String, List<Issue>> tasksOf) {
        if (evidence.agentWorking(issue.key())) {
            String who = Optional.ofNullable(issue.assigneeId()).flatMap(members::find).filter(MemberView::agent).map(MemberView::name).orElse("un agente");
            return Optional.of("la está trabajando " + who + " ahora; espera a que termine o detenlo");
        }
        if (issue.type() == IssueType.EPIC) {
            ProjectView project = project(issue.projectKey());
            String first = project.columns().getFirst().key();
            List<Issue> tasks = tasksOf.computeIfAbsent(issue.key(), k -> issues.findChildren(k).stream().filter(c -> c.workRole() != null).toList());
            List<String> started = tasks.stream().filter(t -> !t.status().equals(first) && !going.contains(t.key())).map(Issue::key).toList();
            if (!started.isEmpty()) return Optional.of("tiene tareas que ya arrancaron (" + String.join(", ", started) + ")");
            // Sus tareas en espera se borran con ella: si alguna no se puede borrar (la trabaja un agente), la épica tampoco.
            List<String> busy = tasks.stream().filter(t -> !going.contains(t.key()) && evidence.agentWorking(t.key())).map(Issue::key).toList();
            if (!busy.isEmpty()) return Optional.of("un agente está trabajando su tarea " + String.join(", ", busy));
        }
        if (issue.parentKey() != null && issue.workRole() != null && !going.contains(issue.parentKey())) {
            List<String> dependents = issues.findChildren(issue.parentKey()).stream()
                    .filter(t -> t.dependsOn().contains(issue.key()) && !going.contains(t.key())).map(Issue::key).toList();
            if (!dependents.isEmpty()) {
                return Optional.of((dependents.size() == 1 ? "la tarea " + dependents.getFirst() + " depende" : "las tareas " + String.join(", ", dependents) + " dependen")
                        + " de ella; márcala" + (dependents.size() == 1 ? "" : "s") + " también");
            }
        }
        return Optional.empty();
    }
}
