package ec.kuador.board.catalog;

import ec.kuador.board.shared.error.DomainException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
 *   <li>{@code review}: quién cierra la puerta: una persona (nulo o {@link ReviewMode#PERSON}) o un agente revisor.
 *   <li>{@code checklist}: lo que hay que verificar antes de sacar la tarjeta de aquí. En una puerta lo comprueba quien
 *       aprueba (persona o agente revisor); en una etapa de trabajo, quien entrega.
 *   <li>{@code requires}: lo que la tarjeta debe tener para salir de aquí (un adjunto, un documento, un dato lleno). Lo
 *       hace cumplir el tablero, no el criterio de nadie.
 *   <li>{@code instructions}: qué hace el agente en esta etapa, en las palabras del negocio (qué mirar, qué sistema usar,
 *       qué hacer si algo no coincide). La escribe quien configura el tablero: es una orden del equipo, no un dato.
 *   <li>{@code autoPlan}: en una etapa de trabajo, el reparto de tareas de una épica arranca solo al llegar aquí (sin
 *       esperar que una persona lo apruebe). El spec ya pasó por su puerta; el reparto se puede corregir después.
 * </ul>
 */
public record StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo, AgentReview review, Boolean autoPlan,
        List<String> checklist, List<Requirement> requires,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String instructions) {

    public static final int MAX_INSTRUCTIONS = 3_000;

    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern FIELD = Pattern.compile("^[a-z][a-z0-9_]{0,29}$");

    /** Rol del agente que revisa una puerta si no se elige otro. */
    public static final String DEFAULT_REVIEWER = "PR_REVIEWER";

    /** Quién cierra una puerta. */
    public enum ReviewMode {
        /** Una persona aprueba o rechaza (como siempre). */
        PERSON,
        /** Un agente revisor aprueba o devuelve; solo lo que no puede decidir pasa a una persona. */
        AGENT,
        /**
         * Un agente revisor aprueba lo claro (CI en verde, criterios con evidencia, nada sensible ni demasiado grande) y
         * devuelve lo que falla; lo dudoso o riesgoso lo deja a una persona con su análisis.
         */
        AGENT_ESCALATE
    }

    /**
     * Revisión de la puerta por un agente. {@code reviewerRole}: el rol del agente (nulo = {@link #DEFAULT_REVIEWER});
     * {@code sensitive}: rutas que siempre escalan a una persona (patrones glob, p. ej. {@code **}{@code /db/migration/**});
     * {@code maxChangedLines}: más líneas cambiadas que esto escala (nulo o 0 = sin límite). Las dos últimas valen en
     * {@link ReviewMode#AGENT_ESCALATE}. {@code merge}: cuando el agente aprueba, el tablero fusiona los PR de la tarjeta
     * (exactamente los commits que revisó); si no puede, la devuelve (conflictos) o la deja a una persona.
     */
    public record AgentReview(ReviewMode mode, String reviewerRole, List<String> sensitive, Integer maxChangedLines, Boolean merge) {

        public AgentReview {
            mode = mode == null ? ReviewMode.PERSON : mode;
            reviewerRole = reviewerRole == null || reviewerRole.isBlank() ? DEFAULT_REVIEWER : reviewerRole.strip();
            sensitive = sensitive == null ? List.of() : sensitive.stream().filter(x -> x != null && !x.isBlank()).map(String::strip).distinct().toList();
            maxChangedLines = maxChangedLines == null || maxChangedLines <= 0 ? null : maxChangedLines;
            // Puertas guardadas antes de este campo no lo traen (null): no fusionan.
            merge = Boolean.TRUE.equals(merge);
        }

        public AgentReview(ReviewMode mode, String reviewerRole, List<String> sensitive, Integer maxChangedLines) {
            this(mode, reviewerRole, sensitive, maxChangedLines, false);
        }

        /** Si al aprobar el agente se fusionan los PR de la tarjeta. */
        public boolean merges() {
            return Boolean.TRUE.equals(merge);
        }

        /** Si un archivo cambiado cae en una ruta sensible. */
        public boolean sensitive(String path) {
            if (path == null) return false;
            Path p = Path.of(path.startsWith("/") ? path.substring(1) : path);
            for (String glob : sensitive) {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                if (m.matches(p) || (glob.startsWith("**/") && FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)).matches(p))) return true;
            }
            return false;
        }
    }

    public StageDef {
        roles = roles == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(roles));
        approvers = approvers == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(approvers));
        rejectTo = rejectTo == null || rejectTo.isBlank() ? null : rejectTo;
        review = !gate || review == null || review.mode() == ReviewMode.PERSON ? null : review;
        // Etapas guardadas antes de este campo no lo traen (null): vale falso.
        autoPlan = Boolean.TRUE.equals(autoPlan) && !gate;
        // Etapas guardadas antes de estos campos no los traen (null): quedan vacíos.
        checklist = checklist == null ? List.of() : checklist.stream().filter(x -> x != null && !x.isBlank()).map(String::strip).distinct().toList();
        requires = requires == null ? List.of() : requires.stream().filter(java.util.Objects::nonNull).distinct().toList();
        instructions = instructions == null || instructions.isBlank() ? null : instructions.strip();
    }

    public StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo, AgentReview review, Boolean autoPlan,
            List<String> checklist, List<Requirement> requires) {
        this(column, roles, gate, approvers, rejectTo, review, autoPlan, checklist, requires, null);
    }

    /** La misma etapa con otras instrucciones. */
    public StageDef withInstructions(String text) {
        return new StageDef(column, roles, gate, approvers, rejectTo, review, autoPlan, checklist, requires, text);
    }

    public StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo, AgentReview review, Boolean autoPlan) {
        this(column, roles, gate, approvers, rejectTo, review, autoPlan, null, null);
    }

    public StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo, AgentReview review) {
        this(column, roles, gate, approvers, rejectTo, review, false);
    }

    public StageDef(String column, List<String> roles, boolean gate, List<String> approvers, String rejectTo) {
        this(column, roles, gate, approvers, rejectTo, null);
    }

    /**
     * Quién cierra esta puerta para una tarjeta: lo del tablero, salvo que la tarjeta pida otra cosa ({@code PERSON}:
     * siempre una persona; {@code AGENT}: que la revise el agente, con escalamiento si el tablero no tenía revisor).
     */
    public ReviewMode reviewFor(String cardPreference) {
        if (!gate) return ReviewMode.PERSON;
        ReviewMode board = review == null ? ReviewMode.PERSON : review.mode();
        if ("PERSON".equals(cardPreference)) return ReviewMode.PERSON;
        if ("AGENT".equals(cardPreference) && board == ReviewMode.PERSON) return ReviewMode.AGENT_ESCALATE;
        return board;
    }

    /** El rol del agente revisor de esta puerta (aunque el tablero no la tenga en automático, por si una tarjeta lo pide). */
    public String reviewerRole() {
        return review == null ? DEFAULT_REVIEWER : review.reviewerRole();
    }

    /** La revisión configurada (o la de por defecto con escalamiento, sin rutas sensibles). */
    public AgentReview agentReview() {
        return review != null ? review : new AgentReview(ReviewMode.AGENT_ESCALATE, null, null, null);
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
            if (s.review() != null) {
                if (!ROLE.matcher(s.review().reviewerRole()).matches()) throw new DomainException("Rol revisor no válido en " + s.column() + ".");
                if (s.review().sensitive().size() > 30) throw new DomainException("Hasta 30 rutas sensibles por puerta.");
                for (String g : s.review().sensitive()) {
                    if (g.length() > 120) throw new DomainException("Una ruta sensible es demasiado larga: " + g.substring(0, 40) + "…");
                    try {
                        FileSystems.getDefault().getPathMatcher("glob:" + g);
                    } catch (RuntimeException e) {
                        throw new DomainException("Ruta sensible no válida: «" + g + "». Usa patrones como **/db/migration/** o src/security/**.");
                    }
                }
            }
            if (s.checklist().size() > 12) throw new DomainException("Hasta 12 puntos en la lista de verificación de " + s.column() + ".");
            if (s.instructions() != null && s.instructions().length() > MAX_INSTRUCTIONS) {
                throw new DomainException("Las instrucciones de " + s.column() + " tienen hasta " + MAX_INSTRUCTIONS + " caracteres.");
            }
            for (String item : s.checklist()) if (item.length() > 200) throw new DomainException("Un punto de la lista de verificación de " + s.column() + " es demasiado largo (máximo 200).");
            if (s.requires().size() > 8) throw new DomainException("Hasta 8 requisitos por etapa (" + s.column() + ").");
            for (Requirement r : s.requires()) {
                if (r.kind() == null) throw new DomainException("Requisito sin tipo en " + s.column() + ".");
                if (r.kind() == Requirement.Kind.DOCUMENT && (r.ref() == null || !ROLE.matcher(r.ref()).matches())) {
                    throw new DomainException("El requisito de documento de " + s.column() + " necesita el tipo de documento (p. ej. SPEC).");
                }
                if (r.kind() == Requirement.Kind.FIELD && (r.ref() == null || !FIELD.matcher(r.ref()).matches())) {
                    throw new DomainException("El requisito de dato de " + s.column() + " necesita la clave del dato (p. ej. monto).");
                }
            }
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
                .map(s -> s.rejectTo() != null && !keys.contains(s.rejectTo()) ? new StageDef(s.column(), s.roles(), s.gate(), s.approvers(), null, s.review(), s.autoPlan(), s.checklist(), s.requires()) : s)
                .toList();
    }
}
