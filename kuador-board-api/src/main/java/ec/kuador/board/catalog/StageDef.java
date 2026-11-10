package ec.kuador.board.catalog;

import ec.kuador.board.shared.error.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cómo trabaja una columna del flujo:
 *
 * <ul>
 *   <li>{@code roles}: quiénes la cubren (roles de agentes o de personas). Un issue que llega aquí
 *       queda sin asignar y lo toma cualquiera con uno de esos roles. Vacío = cualquiera.
 *   <li>{@code gate}: es una puerta: solo una persona la cierra (aprobar o rechazar); un agente no.
 *   <li>{@code approvers}: roles de personas que pueden cerrar la puerta. Vacío = cualquier persona.
 *   <li>{@code rejectTo}: columna a la que vuelve un issue rechazado en la puerta.
 * </ul>
 */
public record StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo) {

    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    public StageDef {
        roles = roles == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(roles));
        approvers = approvers == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(approvers));
        rejectTo = rejectTo == null || rejectTo.isBlank() ? null : rejectTo;
    }

    public boolean coveredBy(String role) {
        return roles.isEmpty() || roles.contains(role);
    }

    public boolean approvableBy(String humanRole) {
        return approvers.isEmpty() || approvers.contains(humanRole);
    }

    /** Valida las etapas contra las columnas del flujo. */
    public static List<StageDef> validate(List<StageDef> stages, List<ColumnDef> columns) {
        if (stages == null) return List.of();
        Set<String> keys = new HashSet<>(columns.stream().map(ColumnDef::key).toList());
        Set<String> seen = new HashSet<>();
        for (StageDef s : stages) {
            if (s.column() == null || !keys.contains(s.column())) {
                throw new DomainException("La etapa " + s.column() + " no corresponde a ninguna columna del flujo.");
            }
            if (!seen.add(s.column())) throw new DomainException("La columna " + s.column() + " tiene dos configuraciones de etapa.");
            for (String r : s.roles()) if (!ROLE.matcher(r).matches()) throw new DomainException("Rol no válido en la etapa " + s.column() + ": " + r + ".");
            for (String r : s.approvers()) if (!ROLE.matcher(r).matches()) throw new DomainException("Rol aprobador no válido en " + s.column() + ": " + r + ".");
            if (!s.gate() && !s.approvers().isEmpty()) throw new DomainException("Solo una puerta tiene aprobadores (columna " + s.column() + ").");
            if (s.rejectTo() != null && (!keys.contains(s.rejectTo()) || s.rejectTo().equals(s.column()))) {
                throw new DomainException("«Rechazar a» de " + s.column() + " debe ser otra columna del flujo.");
            }
        }
        return List.copyOf(stages);
    }

    /** Tras cambiar las columnas: descarta etapas de columnas que ya no existen. */
    public static List<StageDef> prunedTo(List<StageDef> stages, List<ColumnDef> columns) {
        Set<String> keys = new HashSet<>(columns.stream().map(ColumnDef::key).toList());
        return stages.stream().filter(s -> keys.contains(s.column()))
                .map(s -> s.rejectTo() != null && !keys.contains(s.rejectTo()) ? new StageDef(s.column(), s.roles(), s.gate(), s.approvers(), null) : s)
                .toList();
    }
}
