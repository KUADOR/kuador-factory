package ec.kuador.board.audit.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Qué significa cada ruta para una persona y si es de seguridad o del trabajo diario. Lo que no está aquí se registra
 * igual, con su ruta como etiqueta.
 */
public final class AuditActions {

    public record Meaning(String label, AuditEntry.Category category) {}

    private static final Map<String, Meaning> MEANINGS = new HashMap<>();

    static {
        // Acceso
        security("POST /api/auth/login", "Inicio de sesión");
        security("POST /api/auth/logout", "Cierre de sesión");
        security("PUT /api/auth/password", "Cambio de contraseña");
        security("POST /api/auth/password-reset", "Pidió recuperar la contraseña");
        security("POST /api/auth/password-reset/confirm", "Restableció la contraseña");
        security("POST /api/signup/code", "Pidió código de registro");
        security("POST /api/signup/verify", "Verificó correo de registro");
        security("POST /api/signup/workspace", "Creó un espacio");
        security("POST /api/public/invitations/accept", "Aceptó una invitación");
        security("POST /api/oauth/authorize", "Autorizó un cliente de IA");
        security("POST /oauth/token", "Canje de token de cliente de IA");
        // Personas y permisos
        security("POST /api/invitations", "Invitó a una persona");
        security("POST /api/invitations/{id}/resend", "Reenvió una invitación");
        security("DELETE /api/invitations/{id}", "Anuló una invitación");
        security("POST /api/members", "Agregó un miembro");
        security("PUT /api/members/{memberId}/access", "Cambió el acceso de un miembro");
        security("DELETE /api/members/{memberId}", "Quitó a un miembro");
        security("POST /api/members/{memberId}/tokens", "Creó un token de agente");
        security("DELETE /api/auth/tokens/{tokenId}", "Revocó un token");
        security("POST /api/auth/runner-tokens", "Conectó agentes locales");
        security("DELETE /api/auth/runner-tokens/{tokenId}", "Desconectó agentes locales");
        // Empresa, conexiones y pagos
        security("PUT /api/workspace", "Cambió los datos de la empresa");
        security("PUT /api/workspace/languages", "Cambió los idiomas");
        security("PUT /api/workspace/ai", "Cambió la IA de la empresa");
        security("DELETE /api/workspace/ai", "Quitó la clave de IA");
        security("PUT /api/workspace/ai/budget", "Cambió el tope mensual de IA");
        security("PUT /api/workspace/telegram", "Conectó el bot de Telegram");
        security("DELETE /api/workspace/telegram", "Desconectó el bot de Telegram");
        security("POST /api/workspace/telegram/group-code", "Pidió código para vincular un grupo de Telegram");
        security("PUT /api/workspace/telegram/groups/{id}", "Cambió un grupo de Telegram");
        security("DELETE /api/workspace/telegram/groups/{id}", "Quitó un grupo de Telegram");
        security("POST /api/github/app", "Registró la app de GitHub");
        security("POST /api/github/app/manifest", "Registró la app de GitHub");
        security("POST /api/github/installations", "Conectó una organización de GitHub");
        security("POST /api/github/installations/start", "Empezó a conectar GitHub");
        security("DELETE /api/github/installations/{installationId}", "Desconectó una organización de GitHub");
        security("PUT /api/workspace/plan", "Cambió de plan");
        security("POST /api/workspace/plan/checkout", "Inició un pago");
        security("POST /api/workspace/plan/payments/{id}/confirm", "Confirmó un pago");
        security("POST /api/payments/webhook/{code}", "Aviso de la pasarela de pago");
        // Proyectos y datos
        security("POST /api/projects", "Creó un tablero");
        security("DELETE /api/projects/{key}", "Eliminó un tablero");
        security("POST /api/projects/{key}/restore", "Restauró un tablero");
        security("PUT /api/projects/{key}/transitions", "Cambió las reglas de transición");
        security("PUT /api/projects/{key}/stages", "Cambió etapas y aprobaciones");
        security("PUT /api/agents/{memberId}/profile", "Cambió dónde trabaja un agente");
        security("POST /api/projects/{key}/team", "Sumó un agente al tablero");
        security("POST /api/projects/{key}/team/agents", "Creó un agente");
        security("DELETE /api/projects/{key}/team/{memberId}", "Quitó un agente del tablero");
        security("GET /api/attachments/{id}/content", "Descargó un adjunto");
        security("DELETE /api/attachments/{id}", "Borró un adjunto");
        security("GET /api/workspace/audit/export", "Exportó la auditoría");
        security("GET /api/agent-kit.zip", "Descargó el kit de agentes");
        security("PUT /api/notifications", "Cambió sus avisos");
        security("POST /api/notifications/telegram/link", "Vinculó su Telegram");
        security("DELETE /api/notifications/telegram", "Desvinculó su Telegram");
        // Plataforma
        security("PUT /api/platform/plans/{code}", "Cambió un plan");
        security("PUT /api/platform/billing-settings", "Cambió los avisos de vencimiento");
        security("PUT /api/platform/workspaces/{workspaceId}/license", "Cambió la licencia de un espacio");
        security("PUT /api/platform/payment-providers/{code}", "Cambió una pasarela de pago");
        security("PUT /api/platform/ai-providers/{code}", "Cambió un proveedor de IA");
        security("PUT /api/platform/ai-prices/{model}", "Cambió el precio de un modelo");
        security("DELETE /api/platform/ai-prices/{model}", "Quitó el precio de un modelo");
        security("PUT /api/platform/packages/{code}", "Cambió un paquete por industria");
        security("POST /api/platform/catalog/publish", "Publicó el catálogo de la plataforma");
        // Trabajo diario
        work("POST /api/projects/{projectKey}/issues", "Creó una tarjeta");
        work("PUT /api/issues/{key}", "Editó una tarjeta");
        work("DELETE /api/issues/{key}", "Borró una tarjeta");
        work("POST /api/issues/{key}/move", "Movió una tarjeta");
        work("PUT /api/issues/{key}/assignee", "Asignó una tarjeta");
        work("POST /api/issues/{key}/comments", "Comentó una tarjeta");
        work("POST /api/issues/{key}/review", "Aprobó o devolvió una tarjeta");
        work("POST /api/issues/{key}/attachments", "Adjuntó un archivo");
        work("PUT /api/projects/{key}", "Cambió un tablero");
        work("PUT /api/projects/{key}/columns", "Cambió las columnas");
        work("PUT /api/docs/{id}", "Editó un documento");
        work("POST /api/projects/{projectKey}/docs", "Creó un documento");
    }

    private AuditActions() {}

    private static void security(String action, String label) {
        MEANINGS.put(action, new Meaning(label, AuditEntry.Category.SECURITY));
    }

    private static void work(String action, String label) {
        MEANINGS.put(action, new Meaning(label, AuditEntry.Category.WORK));
    }

    /** Una ruta concreta y su patrón conocido, con los valores de la ruta (p. ej. VEN). */
    public record Match(String action, java.util.List<String> values) {}

    private static final java.util.List<java.util.Map.Entry<java.util.regex.Pattern, String>> PATTERNS = new java.util.ArrayList<>();

    /**
     * Para peticiones que la seguridad rechazó antes de llegar al controlador (sin patrón resuelto): busca a qué acción
     * conocida corresponde la ruta. Sin coincidencia, la ruta tal cual.
     */
    public static Match resolve(String method, String path) {
        synchronized (PATTERNS) {
            if (PATTERNS.isEmpty()) {
                for (String action : MEANINGS.keySet()) {
                    String regex = java.util.regex.Pattern.quote(action).replaceAll("\\{[^}]+}", "\\\\E([^/]+)\\\\Q");
                    PATTERNS.add(java.util.Map.entry(java.util.regex.Pattern.compile("^" + regex + "$"), action));
                }
            }
        }
        String candidate = method + " " + path;
        for (var e : PATTERNS) {
            var m = e.getKey().matcher(candidate);
            if (m.matches()) {
                java.util.List<String> values = new java.util.ArrayList<>();
                for (int i = 1; i <= m.groupCount(); i++) values.add(m.group(i));
                return new Match(e.getValue(), values);
            }
        }
        return new Match(candidate, java.util.List.of());
    }

    /** Si una petición se audita: toda escritura y las lecturas que sacan datos (descargas, exportaciones). */
    public static boolean audited(String method, String action) {
        return !method.equals("GET") || MEANINGS.containsKey(action);
    }

    public static Meaning meaning(String action, int status) {
        Meaning m = MEANINGS.get(action);
        if (m != null) return m;
        if (status == 401) return new Meaning("Petición sin sesión válida", AuditEntry.Category.SECURITY);
        if (status == 403) return new Meaning("Acceso denegado", AuditEntry.Category.SECURITY);
        return new Meaning(action, action.contains("/api/platform/") || action.contains("/api/catalog/") ? AuditEntry.Category.SECURITY : AuditEntry.Category.WORK);
    }
}
