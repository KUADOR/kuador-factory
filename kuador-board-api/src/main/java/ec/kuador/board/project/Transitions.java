package ec.kuador.board.project;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.shared.error.DomainException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reglas de transición del flujo, como en Jira: desde qué columna se puede mover un issue a cuál.
 * Sin restricción ({@code restricted = false}) cualquier movimiento vale. Reordenar dentro de la
 * misma columna siempre vale.
 */
public record Transitions(boolean restricted, List<Transition> allowed) {

    public record Transition(String from, String to) {}

    public static final Transitions FREE = new Transitions(false, List.of());

    public Transitions {
        allowed = allowed == null ? List.of() : List.copyOf(new LinkedHashSet<>(allowed));
    }

    public boolean allows(String from, String to) {
        if (!restricted || from == null || from.equals(to)) return true;
        return allowed.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }

    /** Columnas a las que se puede ir desde {@code from}, en el orden del flujo. */
    public List<String> targets(String from, List<ColumnDef> columns) {
        return columns.stream().map(ColumnDef::key).filter(k -> !k.equals(from) && allows(from, k)).toList();
    }

    /** Valida contra el flujo actual: solo columnas existentes y ninguna columna sin salida ni entrada sin sentido. */
    public Transitions validatedFor(List<ColumnDef> columns) {
        Set<String> keys = columns.stream().map(ColumnDef::key).collect(Collectors.toSet());
        for (Transition t : allowed) {
            if (!keys.contains(t.from()) || !keys.contains(t.to())) {
                throw new DomainException("La regla " + t.from() + " → " + t.to() + " usa una columna que no existe en el flujo.");
            }
            if (t.from().equals(t.to())) throw new DomainException("Una columna no necesita regla hacia sí misma.");
        }
        if (restricted) {
            for (ColumnDef c : columns) {
                boolean reachable = c == columns.getFirst() || allowed.stream().anyMatch(t -> t.to().equals(c.key()));
                if (!reachable) {
                    throw new DomainException("A «" + c.name() + "» no se puede llegar desde ninguna columna: agrega al menos una regla hacia ella.");
                }
            }
        }
        return this;
    }

    /** Tras cambiar las columnas: descarta reglas de columnas que ya no existen. */
    public Transitions prunedTo(List<ColumnDef> columns) {
        Set<String> keys = columns.stream().map(ColumnDef::key).collect(Collectors.toSet());
        return new Transitions(restricted, allowed.stream().filter(t -> keys.contains(t.from()) && keys.contains(t.to())).toList());
    }
}
