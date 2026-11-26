package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lo que una etapa exige a la tarjeta para dejarla salir (adjunto, documento, dato, diagrama) y qué rol previo falta en una épica. */
final class StageEvidence extends IssueSupport {

    StageEvidence(Ports ports) {
        super(ports);
    }

    /** Lo que vive en otros módulos (adjuntos, documentos): cada uno dice lo que la tarjeta tiene. */
    List<ec.kuador.board.issue.CardEvidence> evidence = List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvidence(List<ec.kuador.board.issue.CardEvidence> evidence) {
        this.evidence = evidence == null ? List.of() : evidence;
    }

    /**
     * Pasar una puerta hacia adelante con PR abiertos de la tarjeta movería la tarjeta sin su código: se pasa fusionándolos
     * («Aprobar y fusionar») o se rechaza.
     */
    void requireMerged(ProjectView project, Issue issue, String gateColumn) {
        List<String> open = new ArrayList<>();
        for (ec.kuador.board.issue.CardEvidence e : evidence) open.addAll(e.unmergedChanges(project.key(), issue.key()));
        if (open.isEmpty()) return;
        throw new DomainException("«" + columnName(project, gateColumn) + "» se pasa fusionando el código de " + issue.key() + ": falta fusionar "
                + String.join(", ", open.stream().distinct().toList()) + ". Usa «Aprobar y fusionar» o recházala.");
    }

    /** Si un agente la está trabajando ahora, según quien lo sepa (las ejecuciones, las máquinas). */
    boolean agentWorking(String issueKey) {
        return evidence.stream().anyMatch(e -> e.agentWorking(issueKey));
    }

    /**
     * En una épica, el primer rol de {@code epicFirst} de la etapa que todavía no escribió su documento y que algún agente
     * del espacio cubre. Vacío si ya pasaron todos (o no hay a quién esperar): entonces trabajan los roles de la etapa.
     */
    Optional<String> pendingFirstRole(Issue issue, ProjectView project, StageDef stage) {
        if (issue.type() != IssueType.EPIC || stage.epicFirst().isEmpty()) return Optional.empty();
        List<String> agentRoles = members.agents().stream().map(MemberView::role).toList();
        for (String role : stage.epicFirst()) {
            if (!agentRoles.contains(role)) continue;
            boolean done = evidence.stream().map(e -> ec.kuador.board.shared.tenant.SubjectScope.call(issue.subjectKey(), () -> e.hasDocumentOf(issue.projectKey(), issue.key(), role))).flatMap(Optional::stream)
                    .findFirst().orElse(true); // un rol sin documento propio no tiene cómo marcar su parte: no se le espera
            if (!done) return Optional.of(role);
        }
        return Optional.empty();
    }

    Optional<String> notReadyToDeliver(String issueKey, UUID agentId, String comment) {
        Issue issue = load(issueKey);
        ProjectView project = project(issue.projectKey());
        Optional<StageDef> stage = project.stage(issue.status());
        Optional<MemberView> agent = members.find(agentId);
        if (stage.isEmpty() || stage.get().gate() || agent.isEmpty()) return Optional.empty();
        if (issue.type() == IssueType.EPIC && stage.get().worksEpicsFirst(agent.get().role())) {
            return pendingFirstRole(issue, project, stage.get()).filter(r -> r.equals(agent.get().role()))
                    .map(r -> "Antes de entregar, escribe tu documento de " + issue.key() + " con write_document: es lo que deja tu parte lista para el rol que sigue.");
        }
        boolean diagram = stage.get().requires().stream().anyMatch(r -> r.kind() == ec.kuador.board.catalog.Requirement.Kind.DIAGRAM);
        if (diagram && issue.type() == IssueType.EPIC && !hasDiagram(issue, comment)) {
            return Optional.of("El tablero no deja salir " + issue.key() + " de «" + columnName(project, issue.status()) + "» sin el diagrama de la solución:"
                    + " escribe en tu documento (write_document) la sección «Diagrama de la solución» con un bloque ```mermaid de la solución completa.");
        }
        return Optional.empty();
    }

    /** Lo que le falta a la tarjeta de lo que su etapa exige para salir (vacío = cumple). En palabras para una persona. */
    List<String> missingEvidence(ProjectView project, StageDef stage, Issue issue) {
        return missingEvidence(project, stage, issue, null);
    }

    /** {@code delivering}: el comentario con que se entrega, que también cuenta (p. ej. trae el diagrama). */
    List<String> missingEvidence(ProjectView project, StageDef stage, Issue issue, String delivering) {
        List<String> missing = new ArrayList<>();
        for (ec.kuador.board.catalog.Requirement r : stage.requires()) {
            switch (r.kind()) {
                case ATTACHMENT -> {
                    if (evidence.stream().noneMatch(e -> e.hasAttachment(issue.key()))) missing.add("un archivo adjunto");
                }
                case DOCUMENT -> {
                    if (evidence.stream().noneMatch(e -> e.hasDocument(issue.key(), r.ref()))) missing.add("el documento " + r.ref());
                }
                case FIELD -> {
                    String value = issue.fields().get(r.ref());
                    if (value == null || value.isBlank()) {
                        String label = project.fieldsOf(issue.type()).stream().filter(f -> f.key().equals(r.ref())).map(ec.kuador.board.catalog.FieldDef::label)
                                .findFirst().orElse(null);
                        // Un dato que este tipo de tarjeta no tiene no se le puede exigir.
                        if (label != null) missing.add("el dato «" + label + "»");
                    }
                }
                case DIAGRAM -> {
                    if (issue.type() == IssueType.EPIC && !hasDiagram(issue, delivering)) {
                        missing.add("el diagrama de la solución (un bloque ```mermaid en su documento)");
                    }
                }
            }
        }
        return missing;
    }

    boolean hasDiagram(Issue issue, String delivering) {
        String mermaid = "```mermaid";
        if (delivering != null && delivering.contains(mermaid)) return true;
        if (evidence.stream().anyMatch(e -> e.hasDiagram(issue.key()))) return true;
        return issues.findComments(issue).stream().anyMatch(c -> c.body() != null && c.body().contains(mermaid));
    }

    void requireEvidence(ProjectView project, StageDef stage, Issue issue) {
        requireEvidence(project, stage, issue, null);
    }

    void requireEvidence(ProjectView project, StageDef stage, Issue issue, String delivering) {
        List<String> missing = missingEvidence(project, stage, issue, delivering);
        if (!missing.isEmpty()) {
            throw new DomainException("Para salir de «" + columnName(project, issue.status()) + "», a " + issue.key() + " le falta " + String.join(", ", missing) + ".");
        }
    }
}
