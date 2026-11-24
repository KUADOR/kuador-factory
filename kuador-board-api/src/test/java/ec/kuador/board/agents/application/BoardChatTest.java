package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.BoardCard;
import ec.kuador.board.testing.InMemoryProjects;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** El chat del tablero: el contexto que ve el modelo y cómo se lee su respuesta. */
class BoardChatTest {

    private final InMemoryProjects projects = new InMemoryProjects();

    private BoardChat chat(List<BoardCard> cards) {
        IssueApi issues = proxy(IssueApi.class, cards);
        DocsApi docs = proxy(DocsApi.class, List.of());
        return new BoardChat(projects, issues, docs, null, null, List.of(), null, new ObjectMapper(), Clock.systemUTC(), "claude-haiku-5-5");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> answer);
    }

    private static BoardCard card(String key, String title, String type, String column, String category, String parent, int ageMinutes) {
        return new BoardCard(key, title, type, column, category, parent, null, Instant.parse("2026-11-20T12:00:00Z").minusSeconds(ageMinutes * 60L));
    }

    @Test
    void theContextShowsEpicsWithTheirProgressAndWhatIsInFlightPendingAndDone() {
        var p = projects.add("CLI", "SOFTWARE_FACTORY", "ODD");
        String context = chat(List.of(
                card("CLI-1", "Agenda en línea", "EPIC", "Desarrollo", "IN_PROGRESS", null, 0),
                card("CLI-2", "Elegir horario", "TASK", "Hecho", "DONE", "CLI-1", 30),
                card("CLI-3", "Recordatorio por correo", "TASK", "Desarrollo", "IN_PROGRESS", "CLI-1", 5),
                card("CLI-4", "Pantalla de ingreso", "STORY", "Por hacer", "TODO", null, 60),
                card("CLI-5", "Logo", "TASK", "Hecho", "DONE", null, 2))).context(p);

        assertThat(context).contains("CLI-1 Agenda en línea [Desarrollo] — 1/2 hechas");
        assertThat(context).contains("## En curso (2)", "CLI-3 Recordatorio por correo [Desarrollo] (épica CLI-1)");
        assertThat(context).contains("## Pendientes (1)", "CLI-4 Pantalla de ingreso");
        assertThat(context.indexOf("CLI-5 Logo")).as("lo más reciente primero").isLessThan(context.indexOf("CLI-2 Elegir horario"));
    }

    @Test
    void theAnswerKeepsUpToThreeEpicsAndFallsBackToPlainText() {
        BoardChat chat = chat(List.of());
        var answer = chat.parse("""
                ```json
                {"answer":"Vas bien: **CLI-3** en curso.","epics":[{"title":"Pagos","description":"Cobrar la cita."},{"title":" "},
                 {"title":"Reportes"},{"title":"Encuestas"},{"title":"Chat"}]}
                ```""");
        assertThat(answer.text()).isEqualTo("Vas bien: **CLI-3** en curso.");
        assertThat(answer.epics()).extracting(BoardChat.SuggestedEpic::title).containsExactly("Pagos", "Reportes", "Encuestas");

        assertThat(chat.parse("Solo texto, sin JSON.").text()).isEqualTo("Solo texto, sin JSON.");
    }

    @Test
    void aReplyCutForLengthKeepsWhatWasWrittenAndTheEpicsThatClosed() {
        var chat = chat(List.of());
        var answer = chat.salvage("""
                {"answer": "Pasar a **hexagonal**:\\n- puertos de entrada y salida", "epics": [
                  {"title": "Monolito modular", "description": "Módulos con eventos de Spring"},
                  {"title": "Puertos y adaptadores", "descrip""");

        assertThat(answer.text()).startsWith("Pasar a **hexagonal**:\n- puertos de entrada y salida").contains("se cortó por largo");
        assertThat(answer.epics()).extracting(BoardChat.SuggestedEpic::title).containsExactly("Monolito modular");
    }

    @Test
    void aReplyCutInsideTheAnswerShowsTheBeginning() {
        var chat = chat(List.of());

        var answer = chat.salvage("{\"answer\": \"La arquitectura actual mezcla capas y conviene sep");

        assertThat(answer.text()).startsWith("La arquitectura actual mezcla capas y conviene sep").contains("se cortó por largo");
        assertThat(answer.epics()).isEmpty();
    }
}
