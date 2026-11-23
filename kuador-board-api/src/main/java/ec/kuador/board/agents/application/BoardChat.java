package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.TextModel;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.BoardCard;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El chat del tablero: un asistente ligero que mira el estado real (objetivo, Producto, épicas y tarjetas por columna) y
 * responde en qué va el tablero, qué falta y qué épicas convendría abrir. No toca el tablero: las épicas que sugiere
 * las crea la persona con un clic. Usa el modelo más liviano del proveedor y cuenta como trabajo del Agente PO (gasto y
 * topes). No guarda la conversación: la trae la web en cada pregunta.
 */
@Service
public class BoardChat {

    static final int MAX_TOKENS = 1_500;
    static final int MAX_QUESTION = 1_000;
    static final int MAX_TURNS = 6;
    static final int MAX_EPICS = 3;
    /** Cuántas tarjetas se listan por grupo: el resto solo se cuenta. */
    static final int LIST_LIMIT = 30;

    public record Turn(String role, String text) {}

    public record Question(String question, List<Turn> history) {}

    public record SuggestedEpic(String title, String description) {}

    public record Answer(String text, List<SuggestedEpic> epics) {}

    private final ProjectApi projects;
    private final IssueApi issues;
    private final DocsApi docs;
    private final MemberApi members;
    private final WorkspaceAi ai;
    private final List<TextModel> models;
    private final AgentRunRepository runs;
    private final ObjectMapper json;
    private final Clock clock;
    private final String chatModel;

    BoardChat(ProjectApi projects, IssueApi issues, DocsApi docs, MemberApi members, WorkspaceAi ai, List<TextModel> models, AgentRunRepository runs,
            ObjectMapper json, Clock clock, @Value("${board.chat.model:claude-haiku-5-5}") String chatModel) {
        this.projects = projects;
        this.issues = issues;
        this.docs = docs;
        this.members = members;
        this.ai = ai;
        this.models = models;
        this.runs = runs;
        this.json = json;
        this.clock = clock;
        this.chatModel = chatModel;
    }

    public Answer ask(AuthenticatedMember who, String projectKey, Question q) {
        if (who.agent() || who.runner()) throw new ForbiddenException("El chat del tablero es para personas.");
        String question = q == null || q.question() == null ? "" : q.question().strip();
        if (question.isEmpty()) throw new DomainException("Escribe tu pregunta.");
        if (question.length() > MAX_QUESTION) throw new DomainException("La pregunta puede tener hasta " + MAX_QUESTION + " caracteres.");
        ProjectView p = board(projectKey);
        WorkspaceAi.Key key = ai.key().orElseThrow(() -> new DomainException("El espacio no tiene clave de IA: configúrala en Conexiones → IA."));
        MemberView agent = accountable();
        Optional<String> blocked = ai.budgetBlock(agent.id(), null);
        if (blocked.isPresent()) throw new DomainException(blocked.get());
        TextModel model = models.stream().filter(m -> m.protocol() == key.provider().protocol()).findFirst()
                .orElseThrow(() -> new DomainException("El proveedor de IA del espacio no sirve para el chat todavía."));
        String user = context(p) + "\n\n" + conversation(q.history()) + "## Pregunta\n" + question;
        AgentRun run = AgentRun.start(agent.id(), p.key() + "-CHAT", p.key(), UUID.randomUUID(), who.memberId(), clock.instant());
        try {
            TextModel.Reply reply = model.complete(key.endpoint(), key.modelFor(chatModel), SYSTEM, user, MAX_TOKENS);
            runs.save(run.succeeded(reply.model(), reply.inputTokens(), reply.outputTokens(), reply.cacheReadTokens(), reply.cacheWriteTokens(),
                    "Respondió en el chat del tablero.", clock.instant()));
            return parse(reply.text());
        } catch (AgentModelException e) {
            runs.save(run.failed(e.getMessage(), clock.instant()));
            throw new DomainException(e.getMessage());
        }
    }

    static final String SYSTEM = """
            Eres el asistente del tablero: ayudas a una persona a no perder el rumbo. Respondes en español, breve y concreto
            (máximo 10 líneas, viñetas cortas), solo con lo que muestra el contexto: no inventes tarjetas ni avances.
            Cita las tarjetas por su clave (p. ej. ABC-12).
            Si te preguntan dónde quedaron o cómo va: qué está hecho, qué está en curso o esperando a una persona, y qué sigue.
            Si conviene avanzar con trabajo nuevo, o te lo piden, sugiere hasta 3 épicas que acerquen el tablero a su
            objetivo y que no repitan las que ya existen; si no hace falta, no sugieras ninguna.
            Responde SOLO con JSON, sin texto alrededor:
            {"answer": "texto en markdown", "epics": [{"title": "título corto", "description": "qué resuelve y por qué ahora, 1-3 líneas"}]}
            """;

    /** El estado del tablero, en texto: lo que el modelo necesita para resumir y sugerir. */
    String context(ProjectView p) {
        StringBuilder s = new StringBuilder("# Tablero ").append(p.key()).append(" — ").append(p.name()).append('\n');
        projects.goal(p.key()).filter(g -> !g.isBlank()).ifPresent(g -> s.append("Objetivo: ").append(clip(g, 1500)).append('\n'));
        if (p.documents()) {
            docs.ofProject(p.key()).stream().filter(d -> "PRODUCT".equals(d.kind())).findFirst()
                    .ifPresent(d -> s.append("\n## Documento de Producto (extracto)\n").append(clip(d.content(), 5000)).append('\n'));
        }
        List<BoardCard> cards = issues.boardCards(p.key());
        Map<String, BoardCard> byKey = cards.stream().collect(Collectors.toMap(BoardCard::key, c -> c, (a, b) -> a, LinkedHashMap::new));

        s.append("\n## Columnas\n");
        cards.stream().collect(Collectors.groupingBy(BoardCard::statusName, LinkedHashMap::new, Collectors.counting()))
                .forEach((col, n) -> s.append("- ").append(col).append(": ").append(n).append('\n'));

        List<BoardCard> epics = cards.stream().filter(c -> "EPIC".equals(c.type())).toList();
        s.append("\n## Épicas\n");
        if (epics.isEmpty()) s.append("(ninguna)\n");
        for (BoardCard e : epics) {
            List<BoardCard> children = cards.stream().filter(c -> e.key().equals(c.parentKey())).toList();
            long done = children.stream().filter(c -> "DONE".equals(c.category())).count();
            s.append("- ").append(e.key()).append(" ").append(e.title()).append(" [").append(e.statusName()).append("]");
            if (!children.isEmpty()) s.append(" — ").append(done).append('/').append(children.size()).append(" hechas");
            s.append('\n');
        }

        list(s, "En curso", cards.stream().filter(c -> "IN_PROGRESS".equals(c.category())).toList(), byKey);
        list(s, "Pendientes", cards.stream().filter(c -> "TODO".equals(c.category()) && !"EPIC".equals(c.type())).toList(), byKey);
        list(s, "Hechas hace poco", cards.stream().filter(c -> "DONE".equals(c.category()) && !"EPIC".equals(c.type()))
                .sorted(Comparator.comparing(BoardCard::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList(), byKey);
        return s.toString();
    }

    private static void list(StringBuilder s, String title, List<BoardCard> cards, Map<String, BoardCard> byKey) {
        s.append("\n## ").append(title).append(" (").append(cards.size()).append(")\n");
        for (BoardCard c : cards.stream().limit(LIST_LIMIT).toList()) {
            s.append("- ").append(c.key()).append(" ").append(clip(c.title(), 120)).append(" [").append(c.statusName()).append("]");
            if (c.parentKey() != null && byKey.containsKey(c.parentKey())) s.append(" (épica ").append(c.parentKey()).append(")");
            s.append('\n');
        }
        if (cards.size() > LIST_LIMIT) s.append("- … y ").append(cards.size() - LIST_LIMIT).append(" más\n");
    }

    private static String conversation(List<Turn> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder s = new StringBuilder("## Conversación hasta ahora\n");
        history.stream().skip(Math.max(0, history.size() - MAX_TURNS)).filter(t -> t != null && t.text() != null)
                .forEach(t -> s.append("assistant".equals(t.role()) ? "Asistente: " : "Persona: ").append(clip(t.text(), 1500)).append('\n'));
        return s.append('\n').toString();
    }

    Answer parse(String text) {
        String t = text == null ? "" : text.strip();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = json.readTree(t.substring(start, end + 1));
                String answer = node.path("answer").asString("").strip();
                List<SuggestedEpic> epics = new ArrayList<>();
                for (JsonNode e : node.path("epics")) {
                    if (epics.size() >= MAX_EPICS) break;
                    String title = clip(e.path("title").asString("").strip(), 200);
                    if (!title.isEmpty()) epics.add(new SuggestedEpic(title, clip(e.path("description").asString("").strip(), 1000)));
                }
                if (!answer.isEmpty() || !epics.isEmpty()) return new Answer(answer, epics);
            } catch (RuntimeException e) {
                // No era JSON: se muestra el texto tal cual.
            }
        }
        if (t.isEmpty()) throw new DomainException("La IA no respondió: vuelve a intentarlo.");
        return new Answer(t, List.of());
    }

    /** El agente al que se carga el gasto: el PO; si no hay, el primer agente del espacio. */
    private MemberView accountable() {
        List<MemberView> agents = members.agents();
        return agents.stream().filter(a -> "PRODUCT_OWNER".equals(a.role())).findFirst()
                .or(() -> agents.stream().findFirst())
                .orElseThrow(() -> new DomainException("El espacio no tiene agentes."));
    }

    private ProjectView board(String projectKey) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        if (!BoardScope.allows(key)) throw new NotFoundException("No existe el proyecto " + key + ".");
        return projects.find(key).orElseThrow(() -> new NotFoundException("No existe el proyecto " + key + "."));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
