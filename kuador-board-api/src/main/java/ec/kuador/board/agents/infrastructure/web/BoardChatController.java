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

    @PostMapping("/api/projects/{key}/chat")
    BoardChat.Answer ask(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestBody BoardChat.Question q) {
        return chat.ask(who, key, q);
    }
}
