package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoPrompt;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.AgentRules;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Ver qué recibe»: arma, sin llamar al modelo, el pedido que recibiría un agente para una tarjeta (las reglas del
 * espacio ya aplicadas). Sirve para revisar las Reglas de agentes y entender por qué un agente hizo lo que hizo.
 */
@Service
public class AgentPromptPreview {

    /**
     * {@code kind}: REFINE (PO/Arquitecto con automatización) o BOARD (agente del tablero). {@code core}: las reglas
     * fijas que no se editan. {@code environment}: lo que suma el proveedor según cómo trabaja (no editable).
     */
    public record Preview(String agent, String kind, String system, String user, List<String> tools, List<String> core, String note) {}

    private final MemberApi members;
    private final CatalogApi catalog;
    private final BoardAgent board;
    private final ProductOwnerAgent refine;

    AgentPromptPreview(MemberApi members, CatalogApi catalog, BoardAgent board, ProductOwnerAgent refine) {
        this.members = members;
        this.catalog = catalog;
        this.board = board;
        this.refine = refine;
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID memberId, String issueKey) {
        MemberView agent = members.find(memberId).filter(MemberView::agent).orElseThrow(() -> new NotFoundException("No existe ese agente."));
        Optional<AgentRole> role = catalog.role(agent.role());
        String key = issueKey.strip().toUpperCase();
        if (role.map(r -> r.automation() == AgentRole.Automation.REFINE_ISSUE).orElse(false)) {
            PoContext context = refine.buildContext(agent, role.get(), key);
            if (context == null) throw new NotFoundException("No existe el issue " + key + ".");
            return new Preview(agent.name(), "REFINE", PoPrompt.system(context), PoPrompt.user(context), List.of("propuesta estructurada"),
                    AgentRules.CORE, "Responde con una propuesta (resumen, historia, criterios, preguntas, estimación y documento); el tablero comenta y mueve la tarjeta.");
        }
        BoardAgentModel.Task task = board.task(agent, key);
        if (task == null) throw new NotFoundException("No existe el issue " + key + ".");
        List<String> tools = new ArrayList<>(task.tools().stream().map(BoardAgentModel.BoardTool::name).toList());
        tools.add("deliver");
        tools.add("ask_human");
        if (task.sendBackTo() != null) tools.add("send_back");
        if (task.webSearch()) tools.add("web_search / web_fetch");
        if (task.codeExecution()) tools.add("ejecución de código (Claude)");
        String note = "Además, cada proveedor suma cómo es su entorno (lectura de archivos y consultas fuera de Claude). "
                + "Ejecuta código solo si su rol tiene CodeExecution o la tarjeta trae archivos. "
                + "Archivos de la tarjeta que recibe: " + (task.files().isEmpty() ? "ninguno" : String.join(", ", task.files().stream().map(BoardAgentModel.InputFile::name).toList())) + ".";
        return new Preview(agent.name(), "BOARD", task.system(), task.user(), tools, AgentRules.CORE, note);
    }
}
