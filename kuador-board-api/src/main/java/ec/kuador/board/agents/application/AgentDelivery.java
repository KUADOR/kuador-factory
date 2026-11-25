package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.catalog.AgentPolicy;
import ec.kuador.board.catalog.AgentRules;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lo que el agente deja en la tarjeta al terminar: adjunta lo que generó, comenta su resumen y la pasa a la etapa que
 * sigue (o al camino que eligió), la devuelve a una etapa anterior o se la regresa a quien se la pidió.
 */
class AgentDelivery {

    private static final Logger log = LoggerFactory.getLogger(BoardAgent.class);

    private final IssueApi issues;
    private final ProjectApi projects;
    private final CatalogApi catalog;
    private final AttachmentApi attachments;
    private final StageHandoff handoff;
    private final Map<String, StageHandoff.Next> chosenNext;
    private final TransactionTemplate tx;

    AgentDelivery(IssueApi issues, ProjectApi projects, CatalogApi catalog, AttachmentApi attachments, StageHandoff handoff,
            Map<String, StageHandoff.Next> chosenNext, TransactionTemplate tx) {
        this.issues = issues;
        this.projects = projects;
        this.catalog = catalog;
        this.attachments = attachments;
        this.handoff = handoff;
        this.chosenNext = chosenNext;
        this.tx = tx;
    }

    /** Adjunta lo generado, comenta y devuelve la tarjeta. Devuelve el resumen para el registro. */
    String deliver(MemberView agent, MemberView requester, String issueKey, BoardAgentModel.Result result) {
        List<String> attached = new ArrayList<>();
        List<String> notAttached = new ArrayList<>();
        // Un archivo guardado dos veces con el mismo nombre se adjunta una sola vez (la última versión).
        Map<String, BoardAgentModel.OutputFile> unique = new java.util.LinkedHashMap<>();
        for (BoardAgentModel.OutputFile f : result.files()) {
            unique.remove(f.name());
            unique.put(f.name(), f);
        }
        for (BoardAgentModel.OutputFile f : unique.values()) {
            try {
                attached.add(attachments.attach(issueKey, f.name(), f.contentType(), f.content(), agent.id()).fileName());
            } catch (RuntimeException e) {
                log.warn("No se pudo adjuntar {} en {}: {}", f.name(), issueKey, e.getMessage());
                notAttached.add(f.name());
            }
        }
        if (result.outcome() == BoardAgentModel.Outcome.SENT_BACK) {
            Optional<StageHandoff.Back> back = handoff.back(agent, issueKey);
            if (back.isPresent()) {
                String reason = result.text().isBlank() ? "No cumple; revisa los comentarios." : result.text();
                if (!attached.isEmpty()) reason += "\n\n📎 Adjunté: " + String.join(", ", attached) + ".";
                issues.sendBack(issueKey, back.get().column(), reason, agent.id());
                return "devolvió a " + back.get().columnName();
            }
        }
        boolean question = result.outcome() == BoardAgentModel.Outcome.QUESTION;
        StringBuilder comment = new StringBuilder();
        if (question) {
            comment.append("❓ ").append(result.text()).append("\n\n").append(catalog.agentPolicy().messages().askFooter());
        } else {
            comment.append(result.text());
        }
        if (!attached.isEmpty()) comment.append("\n\n📎 Adjunté: ").append(String.join(", ", attached)).append(". El detalle completo está ahí.");
        if (!notAttached.isEmpty()) comment.append("\n\n⚠️ No pude adjuntar: ").append(String.join(", ", notAttached))
                .append(" (revisa que los adjuntos estén habilitados y el tamaño).");
        String files = attached.isEmpty() ? "" : " · " + attached.size() + " archivo(s)";
        // Terminó su etapa sin preguntas: la tarjeta pasa sola a la siguiente (y al agente que la cubre).
        Optional<StageHandoff.Next> next = question || !notAttached.isEmpty() ? Optional.empty() : handoff.next(agent, issueKey);
        // Si eligió otro paso según su resultado, va ahí (siempre dentro de lo permitido al elegir).
        StageHandoff.Next chosen = chosenNext.remove(issueKey);
        if (next.isPresent() && chosen != null) {
            // Un camino que vuelve a un paso anterior es un bucle: se devuelve con su motivo (y cuenta para el freno de vueltas).
            String status = issues.context(issueKey).map(IssueApi.IssueContext::status).orElse(null);
            ProjectView project = issues.context(issueKey).flatMap(c -> projects.find(c.projectKey())).orElse(null);
            if (project != null && status != null && chosen.back(project, status)) {
                issues.sendBack(issueKey, chosen.column(), comment.toString(), agent.id());
                return "entregó" + files + " · volvió a " + chosen.columnName();
            }
            next = Optional.of(chosen);
        }
        if (next.isPresent()) {
            comment.append("\n\n").append(StageHandoff.closing(next.get(), requester.name(), catalog.agentPolicy().messages()));
            handoff.deliver(agent, requester, issueKey, next.get(), comment.toString());
            return "entregó" + files + " · pasó a " + next.get().columnName();
        }
        returnCard(agent, requester, issueKey, comment.toString());
        return (question ? "preguntó" : "entregó") + files + " · devuelto a " + requester.name();
    }

    /** El comentario cuando no pudo empezar o terminar, con los textos del espacio. */
    String failed(boolean start, String reason) {
        AgentPolicy.Messages m = tx.execute(s -> catalog.agentPolicy().messages());
        return AgentRules.fill(start ? m.failedStart() : m.failedFinish(), Map.of("motivo", reason == null ? "" : reason));
    }

    /** Comenta y, si la tarjeta sigue asignada al agente, se la devuelve a la persona (le llega el aviso). */
    void returnCard(MemberView agent, MemberView requester, String issueKey, String comment) {
        if (issues.context(issueKey).isEmpty()) return;
        issues.addComment(issueKey, agent.id(), comment);
        boolean stillMine = issues.context(issueKey).map(i -> agent.id().equals(i.assigneeId())).orElse(false);
        if (stillMine) issues.reassign(issueKey, requester.id(), agent.id());
    }
}
