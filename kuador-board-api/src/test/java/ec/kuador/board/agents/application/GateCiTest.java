package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.sync.CodeReviewApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateCiTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static CodeReviewApi.PullDetail pr(int number, String note, CodeReviewApi.Check... checks) {
        var summary = new CodeReviewApi.PullSummary("kuador/app", number, "KUA-1: algo", "feature/kua-1", "develop", "open", false, "dev", null, null, null);
        return new CodeReviewApi.PullDetail(summary, "abc1234", List.of(), false, List.of(checks), note);
    }

    private static CodeReviewApi.Check check(String name, String status, String conclusion) {
        return new CodeReviewApi.Check(name, status, conclusion, null);
    }

    @Test
    void aRunningCheckWaitsUntilTheLimitThenGoesToAPerson() {
        GateCi.State state = GateCi.of(List.of(pr(1, null, check("build", "completed", "success"), check("e2e", "in_progress", null))));
        assertThat(state.status()).isEqualTo(GateCi.Status.RUNNING);
        assertThat(state.detail()).contains("#1: e2e");
        assertThat(GateCi.decide(state, NOW.minus(Duration.ofMinutes(5)), NOW)).isEqualTo(GateCi.Decision.WAIT);
        assertThat(GateCi.decide(state, NOW.minus(GateCi.MAX_WAIT), NOW)).isEqualTo(GateCi.Decision.ESCALATE);
    }

    @Test
    void aFailedCheckReturnsEvenIfOthersStillRun() {
        GateCi.State state = GateCi.of(List.of(pr(1, null, check("e2e", "in_progress", null)), pr(2, null, check("build", "completed", "failure"))));
        assertThat(state.status()).isEqualTo(GateCi.Status.FAILED);
        assertThat(state.detail()).isEqualTo("kuador/app #2: build");
        assertThat(GateCi.decide(state, NOW, NOW)).isEqualTo(GateCi.Decision.RETURN);
    }

    @Test
    void greenOrUnreadableGoesToTheReviewer() {
        assertThat(GateCi.decide(GateCi.of(List.of(pr(1, null, check("build", "completed", "success"), check("lint", "completed", "skipped")))), NOW, NOW))
                .isEqualTo(GateCi.Decision.REVIEW);
        // Sin permiso para leer el CI no se espera: el revisor lo comprueba y, sin CI legible, decide una persona.
        assertThat(GateCi.decide(GateCi.of(List.of(pr(1, "La app no tiene permiso para leer el CI."))), NOW, NOW)).isEqualTo(GateCi.Decision.REVIEW);
        assertThat(GateCi.decide(GateCi.of(List.of()), NOW, NOW)).isEqualTo(GateCi.Decision.REVIEW);
    }

    @Test
    void aPullWithoutChecksWaitsOnlyForTheStartupGrace() {
        GateCi.State state = GateCi.of(List.of(pr(1, null)));
        assertThat(state.status()).isEqualTo(GateCi.Status.NO_CHECKS);
        assertThat(GateCi.decide(state, NOW.minus(Duration.ofMinutes(2)), NOW)).isEqualTo(GateCi.Decision.WAIT);
        assertThat(GateCi.decide(state, NOW.minus(GateCi.NO_CHECKS_GRACE), NOW)).isEqualTo(GateCi.Decision.REVIEW);
    }

    @Test
    void lockfilesAndGeneratedCodeDoNotCountAsChangedLines() {
        assertThat(GateCi.generated("web/package-lock.json")).isTrue();
        assertThat(GateCi.generated("mobile/pubspec.lock")).isTrue();
        assertThat(GateCi.generated("mobile/lib/models/user.g.dart")).isTrue();
        assertThat(GateCi.generated("mobile/lib/models/user.freezed.dart")).isTrue();
        assertThat(GateCi.generated("web/src/__snapshots__/app.spec.ts.snap")).isTrue();
        assertThat(GateCi.generated("api/src/main/java/com/kuador/ErrorCode.java")).isFalse();
        assertThat(GateCi.generated("web/package.json")).isFalse();
        assertThat(GateCi.generated("mobile/lib/config.dart")).isFalse();
    }
}
