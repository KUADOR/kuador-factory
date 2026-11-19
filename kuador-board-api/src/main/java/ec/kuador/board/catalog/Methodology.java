package ec.kuador.board.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Metodología: qué documentos pide y cómo deben trabajar los agentes. */
public record Methodology(
        UUID id, String key, String name, String summary, List<String> agentRules, List<DocumentDef> documents, boolean builtIn,
        long version) {

    public Optional<DocumentDef> document(String kind) {
        return documents.stream().filter(d -> d.kind().equals(kind)).findFirst();
    }

    public List<DocumentDef> documents(DocumentDef.Scope scope) {
        return documents.stream().filter(d -> d.scope() == scope).toList();
    }

    /** Lo mínimo de ODD: el resto de sus documentos se escribe cuando hace falta. */
    public static final List<String> ODD_MINIMUM = List.of("PRODUCT", "AGENT_RULES");

    /**
     * Los documentos base: se redactan al empezar un proyecto nuevo y sin ellos no se crea el repo (van en su primer
     * commit y guían a los agentes). En ODD, lo mínimo; en las demás, todos los del proyecto.
     */
    public List<DocumentDef> baseDocuments() {
        return documents(DocumentDef.Scope.PROJECT).stream().filter(d -> !"ODD".equals(key) || ODD_MINIMUM.contains(d.kind())).toList();
    }
}
