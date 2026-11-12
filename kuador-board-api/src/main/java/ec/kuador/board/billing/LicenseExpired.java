package ec.kuador.board.billing;

import java.time.Instant;
import java.util.UUID;

/** Venció el plan de un espacio: desde ahora rige {@code fallbackPlanName} hasta que pague o cambie de plan. */
public record LicenseExpired(UUID workspaceId, String planName, Instant dueAt, boolean trial, String fallbackPlanName) {}
