package ec.kuador.board.agents.application;

import ec.kuador.board.sync.CodeReviewApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puerta en automático con «fusionar al aprobar»: el tablero fusiona exactamente los commits que revisó el agente (si
 * llegó otro después, GitHub lo rechaza). El agente nunca fusiona por su cuenta: lo hace el tablero tras su aprobación y
 * las mismas comprobaciones que para una persona (CI en verde, PR abierto que menciona la tarjeta).
 */
final class GateMerge {

    /** MERGED: todo fusionado. CONFLICT: un PR choca con la rama destino. MOVED: llegaron commits sin revisar. FAILED: otra cosa. */
    enum Outcome { MERGED, CONFLICT, MOVED, FAILED }

    /** {@code detail}: lo fusionado (con su commit) o qué falló, en palabras de la persona. {@code merged}: los PR que sí se fusionaron. */
    record Result(Outcome outcome, String detail, List<CodeReviewApi.PullDetail> merged) {}

    private GateMerge() {}

    static Result merge(CodeReviewApi code, String projectKey, String issueKey, List<CodeReviewApi.PullDetail> pulls) {
        List<String> done = new ArrayList<>();
        List<CodeReviewApi.PullDetail> merged = new ArrayList<>();
        for (CodeReviewApi.PullDetail d : pulls) {
            String pr = d.pull().repository() + " #" + d.pull().number();
            try {
                String commit = code.merge(projectKey, d.pull().repository(), d.pull().number(), d.headSha(),
                        issueKey + ": " + d.pull().title() + " (#" + d.pull().number() + ")");
                done.add(pr + " (" + (commit == null || commit.length() < 7 ? "?" : commit.substring(0, 7)) + ")");
                merged.add(d);
            } catch (RuntimeException e) {
                String before = done.isEmpty() ? "" : "Ya fusioné " + String.join(", ", done) + ". ";
                return new Result(classify(e.getMessage()), before + pr + ": " + e.getMessage(), List.copyOf(merged));
            }
        }
        return new Result(Outcome.MERGED, String.join(", ", done), List.copyOf(merged));
    }

    /** GitHub responde 405 «not mergeable» ante un conflicto y 409 «Head branch was modified» si el commit ya no es el revisado. */
    static Outcome classify(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (m.contains("not mergeable") || m.contains("merge conflict") || m.contains(" 405")) return Outcome.CONFLICT;
        if (m.contains("was modified") || m.contains("head branch") || m.contains("sha")) return Outcome.MOVED;
        return Outcome.FAILED;
    }
}
