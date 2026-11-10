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
}
