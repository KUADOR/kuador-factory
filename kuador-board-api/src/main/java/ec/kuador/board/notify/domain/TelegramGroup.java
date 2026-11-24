package ec.kuador.board.notify.domain;

import java.util.UUID;

/** Grupo de Telegram vinculado a un espacio: recibe los avisos del equipo que elija. */
public record TelegramGroup(UUID id, UUID workspaceId, long chatId, String title, boolean onAssigned, boolean onGates, boolean onPlans) {

    public boolean wants(NoticeKind kind) {
        return switch (kind) {
            case ASSIGNED -> onAssigned;
            case GATE, REVIEW -> onGates;
            case PLAN -> onPlans;
            case BILLING, BUDGET, MENTION -> false;
            case TEST -> true;
        };
    }
}
