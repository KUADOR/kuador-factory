package ec.kuador.board.billing;

import java.time.Instant;
import java.util.UUID;

/** El plan de un espacio vence pronto ({@code trial}: termina la prueba; si no, el periodo pagado). */
public record LicenseExpiring(UUID workspaceId, String planName, Instant dueAt, int daysLeft, boolean trial, String fallbackPlanName) {}
