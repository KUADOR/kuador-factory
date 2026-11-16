package ec.kuador.board.catalog.domain;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;

/** Clave de cualquier entrada del catálogo. */
public final class CatalogKeys {

    private CatalogKeys() {}

    public static String of(Object entry) {
        return switch (entry) {
            case WorkflowTemplate t -> t.key();
            case Methodology m -> m.key();
            case AgentRole r -> r.key();
            case McpServer s -> s.key();
            case ComponentTemplate c -> c.key();
            case ec.kuador.board.catalog.AgentPolicy p -> p.key();
            default -> throw new IllegalArgumentException("No es una entrada del catálogo: " + entry);
        };
    }
}
