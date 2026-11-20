package ec.kuador.board.sync.application;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.sync.PullMerged;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Después de cada fusión hecha por el tablero, revisa los demás PR abiertos hacia la misma rama: el que quedó en
 * conflicto vuelve a quien lo desarrolló (o se le avisa, si todavía lo trabaja). Es una regla fija, sin IA: así un
 * conflicto se resuelve apenas aparece y no cuando una persona intenta fusionar y GitHub lo rechaza.
 * GitHub calcula si un PR se puede fusionar en segundo plano: se pregunta varias veces hasta tener respuesta.
 */
@Component
class ConflictWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConflictWatcher.class);
    /** Claves de tarjeta en el título o la rama del PR (la rama va en minúsculas: feature/kua-12-login). */
    private static final Pattern KEY = Pattern.compile("(?i)\\b([a-z][a-z0-9]{1,9}-\\d+)\\b");

    private final CodeReviewApi code;
    private final IssueApi issues;
    private final TransactionTemplate tx;
    private final long pollMillis;
    private final int attempts;

    ConflictWatcher(CodeReviewApi code, IssueApi issues, PlatformTransactionManager txManager,
            @Value("${board.conflicts.poll-ms:5000}") long pollMillis, @Value("${board.conflicts.attempts:8}") int attempts) {
        this.code = code;
        this.issues = issues;
        this.tx = new TransactionTemplate(txManager);
        this.pollMillis = pollMillis;
        this.attempts = attempts;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(PullMerged e) {
        try {
            TenantContext.run(e.workspaceId(), () -> check(e));
        } catch (RuntimeException ex) {
            log.warn("No se pudieron revisar los conflictos tras fusionar {} #{}: {}", e.repository(), e.number(), ex.getMessage());
        }
    }

    void check(PullMerged e) {
        String base = code.pullRequest(e.projectKey(), e.repository(), e.number()).pull().baseBranch();
        List<CodeReviewApi.PullSummary> pending = new ArrayList<>(code.pullRequests(e.projectKey(), "").stream()
                .filter(p -> p.repository().equalsIgnoreCase(e.repository()) && p.number() != e.number())
                .filter(p -> !p.merged() && "open".equalsIgnoreCase(p.state()) && base != null && base.equals(p.baseBranch()))
                .toList());
        for (int i = 0; i < attempts && !pending.isEmpty(); i++) {
            if (i > 0) pause();
            for (CodeReviewApi.PullSummary p : List.copyOf(pending)) {
                Optional<Boolean> mergeable = code.mergeable(e.projectKey(), p.repository(), p.number());
                if (mergeable.isEmpty()) continue;
                pending.remove(p);
                if (!mergeable.get()) conflicted(e, p);
            }
        }
        if (!pending.isEmpty()) log.info("GitHub no terminó de calcular si {} PR de {} tienen conflictos.", pending.size(), e.repository());
    }

    private void conflicted(PullMerged e, CodeReviewApi.PullSummary p) {
        String note = "el PR " + p.repository() + " #" + p.number() + " quedó en conflicto con «" + p.baseBranch() + "» después de fusionar el #"
                + e.number() + ". Actualiza tu rama con «" + p.baseBranch() + "», resuelve los conflictos conservando los dos cambios, verifica "
                + "que el CI quede en verde y vuelve a entregar.";
        for (String key : cardKeys(p)) {
            boolean ours = tx.execute(s -> issues.context(key).map(c -> c.projectKey().equals(e.projectKey())).orElse(false));
            if (!ours) continue;
            tx.execute(s -> issues.returnForConflict(key, note, e.actorId()))
                    .ifPresent(column -> log.info("{} volvió a «{}» por conflictos en {} #{}.", key, column, p.repository(), p.number()));
        }
    }

    /** Las tarjetas que menciona el PR (título y rama), sin repetir. */
    static Set<String> cardKeys(CodeReviewApi.PullSummary p) {
        Set<String> keys = new LinkedHashSet<>();
        for (String text : new String[] {p.title(), p.branch()}) {
            if (text == null) continue;
            Matcher m = KEY.matcher(text);
            while (m.find()) keys.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return keys;
    }

    private void pause() {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
