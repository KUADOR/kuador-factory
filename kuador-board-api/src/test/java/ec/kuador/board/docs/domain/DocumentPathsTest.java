package ec.kuador.board.docs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DocumentPathsTest {

    @Test
    void resolvesSpecKitAndOddPatterns() {
        assertThat(DocumentPaths.forIssue("specs/{feature}/spec.md", "KUA-8", 8, "Prueba de eventos"))
                .isEqualTo("specs/008-prueba-de-eventos/spec.md");
        assertThat(DocumentPaths.forIssue("specs/{feature}/plan.md", "KUA-12", 12, "Login con Google"))
                .isEqualTo("specs/012-login-con-google/plan.md");
        assertThat(DocumentPaths.forIssue("odd/tasks/{task}.md", "KUA-8", 8, "Recuperación de contraseña"))
                .isEqualTo("odd/tasks/kua-8-recuperacion-de-contrasena.md");
    }

    @Test
    void slugIsShortAndSafe() {
        assertThat(DocumentPaths.slug("¿Qué pasa con ÑANDÚ & co.?!")).isEqualTo("que-pasa-con-nandu-co");
        assertThat(DocumentPaths.slug("x".repeat(80))).hasSize(40);
        assertThat(DocumentPaths.slug("!!!")).isEqualTo("sin-titulo");
    }

    @Test
    void templatePlaceholdersAreFilled() {
        assertThat(DocumentTemplate.render("# {title} ({key}) · {project} · {date}", "Kuador", "KUA-1", "Login", LocalDate.of(2026, 11, 10)))
                .isEqualTo("# Login (KUA-1) · Kuador · 2026-11-10");
    }
}
