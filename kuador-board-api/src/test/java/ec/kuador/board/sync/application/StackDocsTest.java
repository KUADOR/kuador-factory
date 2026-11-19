package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** El stack de Arquitectura lo escribe el tablero desde las piezas: reemplaza su sección y no toca el resto. */
class StackDocsTest {

    private static final List<StackDocs.Line> PIECES = List.of(
            new StackDocs.Line("api", "BACKEND", "Java 25 · Spring Boot 4", "api/", "./mvnw -B package", "./mvnw -B verify", "./mvnw spring-boot:run"),
            new StackDocs.Line("app", "MOBILE", "Flutter · Dart", "app/", "flutter build apk --release", "flutter analyze && flutter test", null));

    @Test
    void replacesTheStackSectionAndKeepsTheRest() {
        String doc = """
                # Arquitectura de Clínica

                ## Stack

                | Área | Tecnología |
                |---|---|
                | Backend | Spring Boot 3 y React |

                ## Módulos

                Pacientes y citas.
                """;

        String updated = StackDocs.apply(doc, PIECES);

        assertThat(updated).startsWith("# Arquitectura de Clínica\n\n## Stack\n\n" + StackDocs.NOTE)
                .contains("| api | Backend | Java 25 · Spring Boot 4 | `api/` | `./mvnw -B package` |", "| app | Móvil | Flutter · Dart | `app/` |")
                .as("lo que decía el documento sobre el stack desaparece").doesNotContain("Spring Boot 3 y React")
                .contains("## Módulos\n\nPacientes y citas.");
        assertThat(StackDocs.apply(updated, PIECES)).as("aplicarlo otra vez no cambia nada").isEqualTo(updated);
    }

    @Test
    void addsTheSectionAfterTheTitleWhenThereIsNone() {
        String updated = StackDocs.apply("# Arquitectura\n\nTexto del equipo.\n", PIECES);

        assertThat(updated).startsWith("# Arquitectura\n\n## Stack\n").contains("| api |").endsWith("Texto del equipo.\n");
    }

    @Test
    void escapesPipesSoTheTableDoesNotBreak() {
        String table = StackDocs.table(List.of(new StackDocs.Line("web", "FRONTEND", "A | B", "web/", "npm run build || true", null, null)));

        assertThat(table).contains("A \\| B", "`npm run build \\|\\| true`", "| — |");
    }
}
