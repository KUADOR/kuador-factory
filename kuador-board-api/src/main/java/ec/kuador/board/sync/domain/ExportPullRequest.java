package ec.kuador.board.sync.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PR abierto por el tablero. {@code items}: qué versión de cada documento lleva. */
public record ExportPullRequest(
        UUID id, String projectKey, int number, String url, String branch, RepositoryFiles.PullRequestState state,
        Instant createdAt, UUID createdBy, List<Item> items) {

    public record Item(UUID documentId, int version, String contentSha256) {}

    public ExportPullRequest withState(RepositoryFiles.PullRequestState newState) {
        return new ExportPullRequest(id, projectKey, number, url, branch, newState, createdAt, createdBy, items);
    }

    /** Agrega o reemplaza la versión de cada documento exportado en un nuevo commit al mismo PR. */
    public ExportPullRequest withItems(List<Item> added) {
        List<UUID> ids = added.stream().map(Item::documentId).toList();
        List<Item> merged = new java.util.ArrayList<>(items.stream().filter(i -> !ids.contains(i.documentId())).toList());
        merged.addAll(added);
        return new ExportPullRequest(id, projectKey, number, url, branch, state, createdAt, createdBy, List.copyOf(merged));
    }
}
