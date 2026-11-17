package ec.kuador.board.sync.application;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.sync.CodeReviewApi;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * «Aprobar y fusionar» desde el tablero (también desde el celular): quien aprueba la puerta fusiona el PR de la tarjeta sin
 * abrir GitHub. Solo si el PR menciona la tarjeta, sigue abierto y su CI está en verde; se fusiona exactamente el commit
 * revisado (si llegó otro después, GitHub lo rechaza).
 */
@Service
public class MergeService {

    private static final Set<String> PASSED = Set.of("success", "neutral", "skipped");

    private final IssueApi issues;
    private final CodeReviewApi code;

    MergeService(IssueApi issues, CodeReviewApi code) {
        this.issues = issues;
        this.code = code;
    }

    public record Merged(String repository, int number, String commit, String status) {}

    /** Un PR de la tarjeta con su CI resumido: GREEN, RED, PENDING o NONE. */
    public record CardPull(String repository, int number, String title, String url, String state, boolean merged, String ci) {}

    /** Los PRs que mencionan la tarjeta (los abiertos con su CI), para revisar y fusionar desde el tablero. */
    public java.util.List<CardPull> pulls(String issueKey) {
        IssueApi.IssueContext card = issues.context(issueKey).orElseThrow(() -> new NotFoundException("No existe " + issueKey + "."));
        java.util.List<CardPull> out = new java.util.ArrayList<>();
        for (CodeReviewApi.PullSummary p : code.pullRequests(card.projectKey(), issueKey).stream().limit(5).toList()) {
            String ci = "NONE";
            if (!p.merged() && "open".equalsIgnoreCase(p.state())) {
                CodeReviewApi.PullDetail d = code.pullRequest(card.projectKey(), p.repository(), p.number());
                ci = d.checks().isEmpty() ? "NONE" : "GREEN";
                for (CodeReviewApi.Check c : d.checks()) {
                    String r = c.conclusion() == null ? null : c.conclusion().toLowerCase(Locale.ROOT);
                    if (r == null || !PASSED.contains(r)) ci = r == null ? "PENDING" : "RED";
                    if ("RED".equals(ci)) break;
                }
            }
            out.add(new CardPull(p.repository(), p.number(), p.title(), p.url(), p.state(), p.merged(), ci));
        }
        return out;
    }

    public Merged merge(String issueKey, String repository, int number, boolean approve, UUID actorId) {
        IssueApi.IssueContext card = issues.context(issueKey).orElseThrow(() -> new NotFoundException("No existe " + issueKey + "."));
        if (!issues.canApproveGate(issueKey, actorId)) {
            throw new ForbiddenException("Fusiona quien aprueba la puerta en la que está " + issueKey + ".");
        }
        boolean mentions = code.pullRequests(card.projectKey(), issueKey).stream()
                .anyMatch(p -> p.repository().equalsIgnoreCase(repository) && p.number() == number);
        if (!mentions) throw new DomainException("El PR " + repository + " #" + number + " no menciona " + issueKey + ".");
        CodeReviewApi.PullDetail pr = code.pullRequest(card.projectKey(), repository, number);
        if (pr.pull().merged() || !"open".equalsIgnoreCase(pr.pull().state())) throw new ConflictException("El PR #" + number + " ya no está abierto.");
        if (pr.checks().isEmpty()) throw new ConflictException("El PR #" + number + " no tiene CI: revísalo en GitHub antes de fusionar.");
        for (CodeReviewApi.Check c : pr.checks()) {
            String result = c.conclusion() == null ? null : c.conclusion().toLowerCase(Locale.ROOT);
            if (result == null || !PASSED.contains(result)) {
                throw new ConflictException("El CI del PR #" + number + " no está en verde («" + c.name() + "»: " + (result == null ? "sin resultado" : result) + ").");
            }
        }
        String commit = code.merge(card.projectKey(), repository, number, pr.headSha(), issueKey + ": " + pr.pull().title() + " (#" + number + ")");
        String shortSha = commit.length() >= 7 ? commit.substring(0, 7) : commit;
        issues.addComment(issueKey, actorId, "🔀 Fusioné " + repository + " #" + number + " en la rama principal (" + shortSha + ").");
        String status = card.status();
        if (approve) status = issues.approveGate(issueKey, "Aprobado y fusionado desde el tablero.", actorId).status();
        return new Merged(repository, number, commit, status);
    }
}
