package ec.kuador.board.mcp.application;

import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.member.MemberView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Los sistemas de la empresa (correo, agenda, facturación…) que el rol puede usar en el tablero del issue: verlos y llamarlos. */
class BusinessTools {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private final ToolScope scope;
    /** Conexiones del negocio: opcionales, para no atar las pruebas de las herramientas a ellas. */
    private ec.kuador.board.connections.ConnectionsApi connections;

    BusinessTools(ToolScope scope) {
        this.scope = scope;
    }

    void use(ec.kuador.board.connections.ConnectionsApi connections) {
        this.connections = connections;
    }

    Object list(Map<String, String> a, UUID person) {
        IssueContext c = scope.context(a);
        if (connections == null) return Map.of("tools", List.of());
        return Map.of("tools", connections.toolsFor(c.projectKey(), scope.agent(a).role()).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", t.name());
            m.put("system", t.connectionName());
            m.put("description", t.description());
            m.put("changesData", !t.readOnly());
            m.put("arguments", t.schema());
            return m;
        }).toList());
    }

    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> ARGUMENTS = new tools.jackson.core.type.TypeReference<>() {};

    Object use(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        if (connections == null) throw new ToolException("Este tablero no tiene sistemas de la empresa conectados.");
        MemberView agent = scope.agent(a);
        Map<String, Object> arguments;
        try {
            String raw = a.get("arguments");
            arguments = raw == null || raw.isBlank() ? Map.of() : JSON.readValue(raw, ARGUMENTS);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ToolException("«arguments» debe ser un objeto JSON en texto, p. ej. {\"dia\": \"2026-11-23\"}.");
        }
        try {
            return Map.of("result", connections.call(c.projectKey(), agent.role(), ToolScope.required(a, "tool"), arguments, agent.id(), c.key()));
        } catch (RuntimeException e) {
            throw new ToolException(e.getMessage() == null ? "No se pudo usar la herramienta." : e.getMessage());
        }
    }
}
