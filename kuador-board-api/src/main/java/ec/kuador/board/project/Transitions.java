package ec.kuador.board.project;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los caminos del flujo: desde qué columna se puede ir a cuál. Sin restricción ({@code restricted = false})
 * cualquier movimiento vale. Reordenar dentro de la misma columna siempre vale.
 *
 * <p>Un camino con condición ({@code when}: «si todo coincide», «si falta información») es una decisión del flujo: quien
 * entrega en esa columna elige por cuál sigue según su resultado. Puede ir hacia adelante (saltando pasos) o volver a uno
 * anterior (un bucle, con el freno de vueltas del tablero). Valen aunque el tablero no restrinja los movimientos.
 */
public record Transitions(boolean restricted, List<Transition> allowed) {

    public static final int MAX_CONDITION = 200;

    public record Transition(String from, String to,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String when) {

        public Transition {
            when = when == null || when.isBlank() ? null : when.strip();
        }

        public Transition(String from, String to) {
            this(from, to, null);
        }
    }

    public static final Transitions FREE = new Transitions(false, List.of());

    public Transitions {
        // Un camino por par de columnas (el primero que se declaró).
        java.util.Map<String, Transition> unique = new java.util.LinkedHashMap<>();
        for (Transition t : allowed == null ? List.<Transition>of() : allowed) {
            if (t != null) unique.putIfAbsent(t.from() + "→" + t.to(), t);
        }
        allowed = List.copyOf(unique.values());
    }

    /** Los caminos con condición que salen de {@code from}: las decisiones de ese paso. */
    public List<Transition> paths(String from) {
        return allowed.stream().filter(t -> t.when() != null && t.from().equals(from)).toList();
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
                throw new DomainException("La regla " + t.from() + " → " + t.to() + " usa un paso que no existe en el flujo.");
            }
            if (t.from().equals(t.to())) throw new DomainException("Un paso no necesita regla hacia sí mismo.");
            if (t.when() != null && t.when().length() > MAX_CONDITION) {
                throw new DomainException("La condición del camino " + t.from() + " → " + t.to() + " tiene hasta " + MAX_CONDITION + " caracteres.");
            }
        }
        if (restricted) {
            for (ColumnDef c : columns) {
                boolean reachable = c == columns.getFirst() || allowed.stream().anyMatch(t -> t.to().equals(c.key()));
                if (!reachable) {
                    throw new DomainException("A «" + c.name() + "» no se puede llegar desde ningún paso: agrega al menos una regla hacia él.");
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
