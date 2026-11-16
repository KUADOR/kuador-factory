package ec.kuador.board.mcp.application;

import ec.kuador.board.catalog.AgentRules;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.workspace.WorkspaceApi;
import ec.kuador.board.workspace.WorkspaceApi.Identity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reglas que reciben los agentes locales (Claude Code, Codex) en su manual, en el AGENTS.md y en cada subagente: la
 * firma (propia del MCP), el núcleo de seguridad y las reglas comunes y de preguntas del espacio (Catálogo → Reglas de
 * agentes), más la de idiomas. Son las mismas que reciben los agentes del tablero.
 */
@Component
public class FactoryRules {

    /** Propia del MCP: en los subagentes ya va en su encabezado, por eso es la primera. */
    static final String SIGNATURE = "Actúas siempre con la clave de tu rol en «agent»: todo lo que haces queda firmado por ese agente.";

    private final WorkspaceApi workspace;
    private final CatalogApi catalog;

    FactoryRules(WorkspaceApi workspace, CatalogApi catalog) {
        this.workspace = workspace;
        this.catalog = catalog;
    }

    /** Firma, núcleo, reglas del espacio e idioma. */
    public List<String> all() {
        Identity ws = workspace.identity();
        List<String> rules = new ArrayList<>();
        rules.add(SIGNATURE);
        rules.addAll(AgentRules.common(catalog.agentPolicy(), ws.teamLanguage(), ws.codeLanguage()));
        return List.copyOf(rules);
    }

    /** Idiomas del espacio, para el manual del rol. */
    public Map<String, String> languages() {
        Identity ws = workspace.identity();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("team", ws.teamLanguage());
        m.put("code", ws.codeLanguage());
        return m;
    }
}
