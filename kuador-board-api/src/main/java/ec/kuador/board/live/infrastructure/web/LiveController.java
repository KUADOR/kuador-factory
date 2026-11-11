package ec.kuador.board.live.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.live.application.LiveHub;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class LiveController {

    private final LiveHub hub;

    LiveController(LiveHub hub) {
        this.hub = hub;
    }

    /** Server-Sent Events del espacio de quien pide; con {@code project}, solo de ese proyecto. */
    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter live(@RequestParam(required = false) String project, @AuthenticationPrincipal AuthenticatedMember who) {
        return hub.subscribe(who.workspaceId(), project == null || project.isBlank() ? null : project.strip().toUpperCase());
    }
}
