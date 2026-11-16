package ec.kuador.board.catalog.infrastructure.web;

import ec.kuador.board.catalog.AgentRules;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** El núcleo de seguridad de los agentes: se muestra en Catálogo → Reglas de agentes como lo que no se edita. */
@RestController
class AgentRulesController {

    @GetMapping("/api/agent-rules/core")
    List<String> core() {
        return AgentRules.CORE;
    }
}
