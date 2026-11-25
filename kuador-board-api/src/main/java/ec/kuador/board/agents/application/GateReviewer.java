package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.sync.CodeReviewApi;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las puertas que revisa un agente: espera el CI de los PR antes de gastar tokens, y con la revisión hecha aprueba (y
 * fusiona, si la puerta lo pide), devuelve o deja la decisión a una persona. Lo que no depende del criterio del modelo
 * (CI, rutas sensibles, tamaño del cambio) lo comprueba el tablero.
 */
class GateReviewer {

    private static final Logger log = LoggerFactory.getLogger(BoardAgent.class);

    private final IssueApi issues;
    private final CodeReviewApi code;
    private final AgentTools agentTools;
    private final GateCiWaits ciWaits;
    private final ec.kuador.board.issue.BranchSyncApi syncs;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    GateReviewer(IssueApi issues, CodeReviewApi code, AgentTools agentTools, GateCiWaits ciWaits, ec.kuador.board.issue.BranchSyncApi syncs,
            org.springframework.context.ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.issues = issues;
        this.code = code;
        this.agentTools = agentTools;
        this.ciWaits = ciWaits;
        this.syncs = syncs;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Antes de revisar una puerta, el CI de los PR de la tarjeta (ver {@link GateCi}): si corre, la tarjeta queda esperando
     * (la despierta {@link GateCiWatcher}); en rojo, vuelve a quien la desarrolló sin llamar al modelo; si lleva demasiado
     * esperando, decide una persona. Devuelve por qué no se revisa ahora, o vacío para seguir con la revisión.
     */
    Optional<String> awaitCi(MemberView agent, MemberView requester, IssueAssigned event) {
        String issueKey = event.issueKey();
        Optional<IssueApi.GateReview> gate = tx.execute(s -> issues.gateReview(issueKey, agent.id()));
        if (gate == null || gate.isEmpty() || event.projectKey() == null) {
            tx.executeWithoutResult(s -> ciWaits.done(issueKey));
            return Optional.empty();
        }
        java.time.Instant now = clock.instant();
        java.time.Instant since = tx.execute(s -> ciWaits.since(issueKey).orElse(now));
        // Con la rama en conflicto, el runner la está actualizando: se revisa cuando termine (lo avisa BranchSynced).
        if (Boolean.TRUE.equals(tx.execute(s -> syncs.pending(issueKey).isPresent()))) {
            tx.executeWithoutResult(s -> ciWaits.done(issueKey));
            return Optional.of("Espero que el runner actualice la rama de " + issueKey + " (conflictos) para revisar.");
        }
        // GitHub fuera de la transacción.
        GateCi.State state = GateCi.of(code, event.projectKey(), issueKey);
        GateCi.Decision decision = GateCi.decide(state, since, now);
        if (decision == GateCi.Decision.WAIT) {
            tx.executeWithoutResult(s -> ciWaits.wait(issueKey, event.projectKey(), agent.id(), requester.id(), now));
            return Optional.of("Espero que termine el CI (" + state.detail() + ") para revisar " + issueKey + ".");
        }
        tx.executeWithoutResult(s -> ciWaits.done(issueKey));
        return switch (decision) {
            case RETURN -> {
                String why = "El CI no está en verde: " + state.detail() + ". Corrígelo, verifica que quede en verde y vuelve a entregar.";
                tx.executeWithoutResult(s -> issues.reviewAs(issueKey, false, why, agent.id()));
                reviewed(agent, event.projectKey(), issueKey, gate.get(), ec.kuador.board.agents.GateReviewed.RETURNED, why);
                yield Optional.of("Devolví " + issueKey + " a " + nz(gate.get().backName(), "la etapa anterior") + " sin revisar: CI en rojo.");
            }
            case ESCALATE -> {
                String why = "El CI lleva más de " + GateCi.MAX_WAIT.toMinutes() + " minutos sin terminar (" + state.detail()
                        + "). Revisa qué pasa en GitHub: cuando termine, apruébala tú o vuelve a asignármela.";
                tx.executeWithoutResult(s -> issues.escalate(issueKey, why, agent.id()));
                yield Optional.of("Dejé " + issueKey + " a una persona: el CI no terminó en " + GateCi.MAX_WAIT.toMinutes() + " minutos.");
            }
            default -> Optional.empty();
        };
    }

    /**
     * El agente revisor terminó: aprueba (si el tablero lo confirma), devuelve o deja la decisión a una persona. Antes de
     * aprobar, el tablero comprueba lo que no depende del criterio del modelo: el CI del PR y, con escalamiento, las rutas
     * sensibles y el tamaño del cambio.
     */
    String closeGate(MemberView agent, String issueKey, IssueApi.GateReview gate, BoardAgentModel.Result result) {
        String text = result.text() == null || result.text().isBlank() ? "Sin detalle." : result.text().strip();
        String projectKey = tx.execute(s -> issues.context(issueKey).map(IssueApi.IssueContext::projectKey).orElse(null));
        return switch (result.outcome()) {
            case SENT_BACK -> {
                tx.executeWithoutResult(s -> issues.reviewAs(issueKey, false, text, agent.id()));
                reviewed(agent, projectKey, issueKey, gate, ec.kuador.board.agents.GateReviewed.RETURNED, text);
                yield "devolvió a " + nz(gate.backName(), "la etapa anterior");
            }
            case QUESTION -> {
                tx.executeWithoutResult(s -> issues.escalate(issueKey, text, agent.id()));
                yield "dejó la puerta a una persona";
            }
            // Sin una decisión explícita (deliver / send_back) no se aprueba: lo decide una persona.
            case ANSWERED -> {
                tx.executeWithoutResult(s -> issues.escalate(issueKey, "Terminé sin aprobar ni devolver de forma explícita; decide tú.\n\nMi revisión:\n" + text, agent.id()));
                yield "dejó la puerta a una persona (sin decisión explícita)";
            }
            case DELIVERED -> {
                ReviewCheck check = projectKey == null ? ReviewCheck.ok() : checkPulls(projectKey, issueKey, gate);
                if (check.reject() != null) {
                    tx.executeWithoutResult(s -> issues.reviewAs(issueKey, false, check.reject() + "\n\nMi revisión:\n" + text, agent.id()));
                    reviewed(agent, projectKey, issueKey, gate, ec.kuador.board.agents.GateReviewed.RETURNED, check.reject());
                    yield "devolvió a " + nz(gate.backName(), "la etapa anterior") + " (CI)";
                }
                // Si la puerta fusiona, lo sensible o demasiado grande lo decide siempre una persona (aunque el modo no escale).
                if (check.escalate() != null && (gate.escalates() || check.always() || gate.merge())) {
                    tx.executeWithoutResult(s -> issues.escalate(issueKey, "La aprobaría, pero " + check.escalate() + "\n\nMi revisión:\n" + text, agent.id()));
                    yield "dejó la puerta a una persona (" + check.escalate() + ")";
                }
                if (gate.merge() && !check.pulls().isEmpty()) yield approveAndMerge(agent, projectKey, issueKey, gate, check, text);
                try {
                    tx.executeWithoutResult(s -> issues.reviewAs(issueKey, true, "🔎 Revisión del agente:\n" + text + check.note(), agent.id()));
                } catch (ec.kuador.board.shared.error.DomainException missing) {
                    // A la tarjeta le falta algo que la puerta exige (un archivo, un documento, un dato): lo resuelve una persona.
                    tx.executeWithoutResult(s -> issues.escalate(issueKey, "La aprobaría, pero " + missing.getMessage() + "\n\nMi revisión:\n" + text, agent.id()));
                    yield "dejó la puerta a una persona (falta un requisito)";
                }
                reviewed(agent, projectKey, issueKey, gate, ec.kuador.board.agents.GateReviewed.APPROVED, text);
                yield "aprobó · pasó a " + nz(gate.nextName(), "la siguiente columna");
            }
        };
    }

    /**
     * La puerta fusiona al aprobar: el tablero fusiona los commits revisados. Con conflictos, la tarjeta vuelve a quien la
     * desarrolló para que actualice su rama; si llegaron commits sin revisar, espera su CI y se revisa otra vez; si GitHub falla, decide una persona.
     */
    private String approveAndMerge(MemberView agent, String projectKey, String issueKey, IssueApi.GateReview gate, ReviewCheck check, String text) {
        GateMerge.Result merged = GateMerge.merge(code, projectKey, issueKey, check.pulls());
        // Lo fusionado puede dejar en conflicto otros PR: el vigilante de conflictos los revisa.
        for (CodeReviewApi.PullDetail d : merged.merged()) {
            events.publishEvent(new ec.kuador.board.sync.PullMerged(TenantContext.require(), projectKey, d.pull().repository(), d.pull().number(), agent.id()));
        }
        return switch (merged.outcome()) {
            case MERGED -> {
                tx.executeWithoutResult(s -> issues.reviewAs(issueKey, true,
                        "🔎 Revisión del agente:\n" + text + check.note() + "\n\n🔀 Fusioné " + merged.detail() + ".", agent.id()));
                reviewed(agent, projectKey, issueKey, gate, ec.kuador.board.agents.GateReviewed.MERGED, "Fusioné " + merged.detail() + ".\n\n" + text);
                yield "aprobó y fusionó · pasó a " + nz(gate.nextName(), "la siguiente columna");
            }
            case CONFLICT -> {
                String why = "La aprobaría, pero el PR tiene conflictos con su rama destino (otro cambio se fusionó antes). Actualiza tu rama "
                        + "con la rama destino, resuelve los conflictos conservando los dos cambios, verifica que el CI quede en verde y vuelve a "
                        + "entregar.\n\n" + merged.detail();
                tx.executeWithoutResult(s -> issues.reviewAs(issueKey, false, why + "\n\nMi revisión:\n" + text, agent.id()));
                reviewed(agent, projectKey, issueKey, gate, ec.kuador.board.agents.GateReviewed.RETURNED, why);
                yield "devolvió a " + nz(gate.backName(), "la etapa anterior") + " (conflictos)";
            }
            // Llegaron commits después de la revisión (p. ej. el tablero actualizó la rama con develop): no se fusiona sin
            // revisar, pero tampoco se molesta a una persona. La tarjeta espera el CI de lo nuevo y el revisor la mira otra vez.
            case MOVED -> {
                tx.executeWithoutResult(s -> ciWaits.wait(issueKey, projectKey, agent.id(), null, clock.instant()));
                yield "llegaron commits nuevos después de revisar (" + merged.detail() + "): la reviso otra vez cuando termine el CI";
            }
            case FAILED -> {
                tx.executeWithoutResult(s -> issues.escalate(issueKey, "Decide tú: la aprobé, pero no pude fusionar (" + merged.detail() + ").\n\nMi revisión:\n"
                        + text, agent.id()));
                yield "dejó la puerta a una persona (no pudo fusionar)";
            }
        };
    }

    /** Avisa la decisión del revisor (Telegram, correo, push). Un aviso que falla no cambia la decisión. */
    private void reviewed(MemberView agent, String projectKey, String issueKey, IssueApi.GateReview gate, String verdict, String summary) {
        if (projectKey == null) return;
        try {
            events.publishEvent(new ec.kuador.board.agents.GateReviewed(TenantContext.require(), projectKey, issueKey, agent.name(), gate.gateName(),
                    verdict, summary));
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar la revisión de {}: {}", issueKey, e.getMessage());
        }
    }

    /**
     * Lo que el tablero comprueba antes de aprobar: {@code reject} devuelve, {@code escalate} deja a una persona, {@code note} se suma al
     * comentario. {@code pulls}: los PR abiertos revisados (con su commit), para fusionarlos si la puerta lo pide.
     */
    record ReviewCheck(String reject, String escalate, String note, boolean always, List<CodeReviewApi.PullDetail> pulls) {
        ReviewCheck(String reject, String escalate, String note, boolean always) {
            this(reject, escalate, note, always, List.of());
        }

        ReviewCheck(String reject, String escalate, String note) {
            this(reject, escalate, note, false);
        }

        static ReviewCheck ok() {
            return new ReviewCheck(null, null, "");
        }
    }

    private static final java.util.Set<String> FAILED = java.util.Set.of("failure", "timed_out", "cancelled", "action_required", "startup_failure", "error");
    private static final java.util.Set<String> PASSED = java.util.Set.of("success", "neutral", "skipped");

    /** El CI de los PRs de la tarjeta y, con escalamiento, rutas sensibles y tamaño. En tableros sin repos no hay nada que comprobar. */
    ReviewCheck checkPulls(String projectKey, String issueKey, IssueApi.GateReview gate) {
        List<String> repos = agentTools.repositories(projectKey);
        if (repos.isEmpty()) return ReviewCheck.ok();
        List<CodeReviewApi.PullDetail> pulls = new ArrayList<>();
        try {
            for (CodeReviewApi.PullSummary p : code.pullRequests(projectKey, issueKey)) {
                if (p.merged() || !"open".equalsIgnoreCase(p.state())) continue;
                pulls.add(code.pullRequest(projectKey, p.repository(), p.number()));
            }
        } catch (RuntimeException e) {
            return new ReviewCheck(null, "no pude leer el Pull Request en GitHub (" + e.getMessage() + ").", "", true);
        }
        if (pulls.isEmpty()) return new ReviewCheck(null, "no encontré un Pull Request abierto que mencione " + issueKey + ".", "", true);
        List<String> failed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> sensitive = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        int lines = 0;
        for (CodeReviewApi.PullDetail d : pulls) {
            if (d.filesIncomplete()) truncated.add(d.pull().repository() + " #" + d.pull().number());
            String pr = d.pull().repository() + " #" + d.pull().number();
            if (d.checks().isEmpty()) unknown.add(pr + (d.checksNote() != null ? " (" + d.checksNote() + ")" : " (sin CI)"));
            for (CodeReviewApi.Check c : d.checks()) {
                String result = c.conclusion() == null ? null : c.conclusion().toLowerCase(java.util.Locale.ROOT);
                if (result != null && FAILED.contains(result)) failed.add(pr + ": " + c.name());
                else if (result == null || !PASSED.contains(result)) unknown.add(pr + ": " + c.name() + " (" + (c.status() == null ? "sin resultado" : c.status()) + ")");
            }
            for (CodeReviewApi.ChangedFile f : d.files()) {
                // Lockfiles y código generado no cuentan para el tamaño: nadie los revisa línea por línea.
                if (!GateCi.generated(f.path())) lines += f.additions() + f.deletions();
                if (gate.sensitive().stream().anyMatch(g -> new ec.kuador.board.catalog.StageDef.AgentReview(null, null, List.of(g), null).sensitive(f.path()))) {
                    sensitive.add(f.path());
                }
            }
        }
        if (!failed.isEmpty()) return new ReviewCheck("El CI no está en verde: " + String.join(", ", failed) + ".", null, "");
        String commits = pulls.stream().map(d -> d.pull().repository() + " #" + d.pull().number() + " @ "
                + (d.headSha() == null || d.headSha().length() < 7 ? "?" : d.headSha().substring(0, 7))).collect(java.util.stream.Collectors.joining(", "));
        String note = "\n\n✅ CI en verde · " + lines + " líneas cambiadas (sin contar generados) en " + pulls.size() + " PR · revisado: " + commits
                + ". Si llegan commits nuevos, esta aprobación no los cubre.";
        // Sin CI en verde o sin ver todos los archivos, el agente no aprueba solo en ningún modo.
        if (!unknown.isEmpty()) return new ReviewCheck(null, "el CI no tiene resultado en verde: " + String.join(", ", unknown) + ".", "", true);
        if (!truncated.isEmpty()) {
            return new ReviewCheck(null, "el PR tiene más archivos de los que pude revisar: " + String.join(", ", truncated) + ".", "", true);
        }
        if (!sensitive.isEmpty()) {
            return new ReviewCheck(null, "toca rutas sensibles: " + String.join(", ", sensitive.stream().limit(10).toList()) + ".", "");
        }
        if (gate.maxChangedLines() != null && lines > gate.maxChangedLines()) {
            return new ReviewCheck(null, "cambia " + lines + " líneas sin contar archivos generados (el límite para aprobar solo es " + gate.maxChangedLines() + ").", "");
        }
        return new ReviewCheck(null, null, note, false, List.copyOf(pulls));
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
