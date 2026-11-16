package ec.kuador.board.agents.application;

import ec.kuador.board.sync.RepositoriesApi;
import java.util.List;

/** Las piezas del proyecto en Markdown, para el pedido de un agente: stack, dónde viven, comandos y notas del equipo. */
final class Pieces {

    private Pieces() {}

    static String markdown(RepositoriesApi repositories, String projectKey) {
        List<RepositoriesApi.Piece> all;
        try {
            all = repositories.pieces(projectKey);
        } catch (RuntimeException e) {
            return "";
        }
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nPiezas del proyecto (lo que se usa de verdad; manda sobre lo que diga una plantilla):\n");
        for (RepositoriesApi.Piece p : all) {
            sb.append("- ").append(p.name()).append(" (").append(p.kind()).append("): ").append(p.stack()).append(" · ").append(p.repository())
                    .append(p.path() == null || p.path().isEmpty() ? "" : "/" + p.path());
            if (p.build() != null || p.test() != null) {
                sb.append(" · compilar: ").append(p.build() == null ? "—" : p.build()).append(" · probar: ").append(p.test() == null ? "—" : p.test());
            }
            sb.append("\n");
            if (p.notes() != null) sb.append("  Notas del equipo: ").append(p.notes().strip().replace("\n", "\n  ")).append("\n");
        }
        return sb.toString();
    }
}
