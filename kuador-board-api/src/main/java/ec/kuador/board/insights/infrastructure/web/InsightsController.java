package ec.kuador.board.insights.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.insights.application.InsightsService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El dashboard: cómo va la empresa (quien administra) y mi trabajo (cada persona). */
@RestController
class InsightsController {

    private final InsightsService insights;

    InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    @GetMapping("/api/insights/company")
    InsightsService.Company company(@AuthenticationPrincipal AuthenticatedMember who, @RequestParam(required = false) Integer days) {
        return insights.company(who, days);
    }

    @GetMapping("/api/insights/me")
    InsightsService.Mine mine(@AuthenticationPrincipal AuthenticatedMember who) {
        return insights.mine(who);
    }

    @GetMapping("/api/insights/settings")
    Map<String, BigDecimal> settings(@AuthenticationPrincipal AuthenticatedMember who) {
        return Map.of("hoursPerPoint", insights.hoursPerPoint(who.workspaceId()));
    }

    @PutMapping("/api/insights/settings")
    Map<String, BigDecimal> save(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody Settings r) {
        return Map.of("hoursPerPoint", insights.setHoursPerPoint(who, r.hoursPerPoint()));
    }

    record Settings(BigDecimal hoursPerPoint) {}
}
