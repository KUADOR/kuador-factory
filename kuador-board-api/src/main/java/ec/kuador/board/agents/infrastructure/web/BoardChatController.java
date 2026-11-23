package ec.kuador.board.agents.infrastructure.web;

import ec.kuador.board.agents.application.BoardChat;
import ec.kuador.board.auth.AuthenticatedMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** El chat del tablero: en qué va, qué falta y qué épicas convendría abrir. */
@RestController
class BoardChatController {

    private final BoardChat chat;

    BoardChatController(BoardChat chat) {
        this.chat = chat;
    }

    private ec.kuador.board.agents.application.BoardDesigner designer;

    @org.springframework.beans.factory.annotation.Autowired
    void setDesigner(ec.kuador.board.agents.application.BoardDesigner designer) {
        this.designer = designer;
    }

    /** {@code answers}: lo respondido a las preguntas; {@code previous} y {@code change}: ajustar una propuesta ya hecha. */
    record DesignRequest(@jakarta.validation.constraints.Size(max = 3000) String description,
            @jakarta.validation.constraints.Size(max = 10) java.util.List<ec.kuador.board.agents.application.BoardDesigner.Answer> answers,
            ec.kuador.board.agents.application.BoardDesigner.Design previous, @jakarta.validation.constraints.Size(max = 1500) String change) {}

    record CreateRequest(ec.kuador.board.agents.application.BoardDesigner.Design design, @jakarta.validation.constraints.Size(max = 10) String key,
            @jakarta.validation.constraints.Size(max = 120) String name) {}

    /** «Cuéntame tu negocio»: propone un tablero para ese negocio, sin crear nada. */
    @PostMapping("/api/boards/design")
    ec.kuador.board.agents.application.BoardDesigner.Design design(@AuthenticationPrincipal AuthenticatedMember who, @jakarta.validation.Valid @RequestBody DesignRequest r) {
        return designer.propose(who, r.description(), r.answers(), r.previous(), r.change());
    }

    /** Las preguntas que faltan para que el tablero quede bien conectado: de dónde llega el trabajo, contra qué se revisa, a dónde va. */
    @PostMapping("/api/boards/design/questions")
    java.util.Map<String, Object> questions(@AuthenticationPrincipal AuthenticatedMember who, @jakarta.validation.Valid @RequestBody DesignRequest r) {
        return java.util.Map.of("questions", designer.questions(who, r.description()));
    }

    /** Crea el tablero de una propuesta (con su plantilla y su objetivo). */
    @PostMapping("/api/boards/design/create")
    java.util.Map<String, String> create(@AuthenticationPrincipal AuthenticatedMember who, @jakarta.validation.Valid @RequestBody CreateRequest r) {
        return java.util.Map.of("key", designer.create(who, r.design(), r.key(), r.name()));
    }

    @PostMapping("/api/projects/{key}/chat")
    BoardChat.Answer ask(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestBody BoardChat.Question q) {
        return chat.ask(who, key, q);
    }
}
