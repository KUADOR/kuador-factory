package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Los agentes no leen archivos de secretos del repo, aunque un comentario se lo pida. */
class SecretFilesTest {

    @Test
    void secretFilesAreBlockedAndCodeIsNot() {
        assertThat(CodeReviewService.secretLooking(".env")).isTrue();
        assertThat(CodeReviewService.secretLooking("api/.env.production")).isTrue();
        assertThat(CodeReviewService.secretLooking("deploy/server.pem")).isTrue();
        assertThat(CodeReviewService.secretLooking("src/main/resources/application-prod.yml")).isTrue();
        assertThat(CodeReviewService.secretLooking("config/secrets.yaml")).isTrue();
        assertThat(CodeReviewService.secretLooking(".git/config")).isTrue();
        assertThat(CodeReviewService.secretLooking("home/.ssh/id_ed25519")).isTrue();

        assertThat(CodeReviewService.secretLooking(".env.example")).as("las plantillas sí").isFalse();
        assertThat(CodeReviewService.secretLooking("src/main/java/ec/kuador/board/shared/crypto/SecretBox.java")).isFalse();
        assertThat(CodeReviewService.secretLooking("src/main/resources/application.yaml")).isFalse();
        assertThat(CodeReviewService.secretLooking("docs/keyboard.md")).isFalse();
    }

    @Test
    void aCardKeyMatchesWholeNotAsAPrefix() {
        assertThat(CodeReviewService.contains("MED-1: login", "med-1")).isTrue();
        assertThat(CodeReviewService.contains("feature/med-1-login", "med-1")).isTrue();
        assertThat(CodeReviewService.contains("MED-12: pagos", "med-1")).isFalse();
        assertThat(CodeReviewService.contains("XMED-1", "med-1")).isFalse();
    }
}
