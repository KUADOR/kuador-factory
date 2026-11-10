package ec.kuador.board.activity.infrastructure.web;

import ec.kuador.board.activity.application.ActivityService;
import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ActivityController {

    private final ActivityService service;

    ActivityController(ActivityService service) {
        this.service = service;
    }

    @GetMapping("/api/issues/{key}/activity")
    List<ActivityResponse> ofIssue(@PathVariable String key, @RequestParam(defaultValue = "100") int limit) {
        return service.ofIssue(key, limit).stream().map(ActivityResponse::from).toList();
    }

    @GetMapping("/api/projects/{projectKey}/activity")
    List<ActivityResponse> ofProject(@PathVariable String projectKey, @RequestParam(defaultValue = "50") int limit) {
        return service.ofProject(projectKey, limit).stream().map(ActivityResponse::from).toList();
    }

    record ActivityResponse(
            UUID id, UUID eventId, String issueKey, UUID actorId, ActivityType type, String field, String from, String to,
            Instant occurredAt) {

        static ActivityResponse from(ActivityEntry a) {
            return new ActivityResponse(a.id(), a.eventId(), a.issueKey(), a.actorId(), a.type(), a.field(), a.from(), a.to(),
                    a.occurredAt());
        }
    }
}
