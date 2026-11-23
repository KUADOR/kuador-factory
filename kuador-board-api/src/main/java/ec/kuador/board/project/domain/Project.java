package ec.kuador.board.project.domain;

import ec.kuador.board.catalog.Capability;
import ec.kuador.board.catalog.EstimateUnit;
import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.ProjectKind;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un proyecto. Nace de una plantilla de flujo del catálogo: copia su tipo, sus columnas y sus tipos
 * de issue, así cambiar la plantilla después no altera proyectos existentes.
 */
public record Project(
        UUID id, String key, String name, String description, ProjectKind kind, String templateKey, String methodology,
        List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework, Instant createdAt,
        List<Capability> capabilities, EstimateUnit estimate) {

    public static final int DEFAULT_MAX_REWORK = 3;

    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

    public Project {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new DomainException("La clave del proyecto va en mayúsculas, de 2 a 10 caracteres (ej. KUA).");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new DomainException("El nombre del proyecto es obligatorio y tiene máximo 120 caracteres.");
        }
        if (kind == null || templateKey == null) {
            throw new DomainException("El proyecto necesita una plantilla de flujo.");
        }
        // Tableros guardados antes de las capacidades: valen las de su tipo.
        capabilities = capabilities == null ? Capability.defaultsFor(kind) : Capability.normalize(capabilities);
        kind = Capability.kindOf(capabilities);
        if (methodology != null && !capabilities.contains(Capability.DOCUMENTS)) {
            throw new DomainException("Este tablero no usa documentos: no lleva manual de trabajo.");
        }
        name = name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        if (description != null && description.length() > 2000) {
            throw new DomainException("La descripción del proyecto puede tener hasta 2000 caracteres.");
        }
        issueTypes = List.copyOf(issueTypes);
        estimate = estimate == null ? EstimateUnit.POINTS : estimate;
        transitions = transitions == null ? Transitions.FREE : transitions;
        stages = stages == null ? List.of() : List.copyOf(stages);
        if (maxRework < 1 || maxRework > 20) throw new DomainException("El freno de bucles va entre 1 y 20 vueltas.");
    }

    public static Project create(String key, String name, String description, List<Capability> capabilities, String templateKey,
            List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, EstimateUnit estimate, Instant now) {
        return new Project(UUID.randomUUID(), key == null ? null : key.strip().toUpperCase(), name, description,
                Capability.kindOf(Capability.normalize(capabilities)), templateKey, null, issueTypes, transitions, stages, DEFAULT_MAX_REWORK, now, capabilities, estimate);
    }

    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }

    /** {@code null} deja el proyecto sin metodología elegida. */
    public Project withMethodology(String methodologyKey) {
        return new Project(id, key, name, description, kind, templateKey, methodologyKey, issueTypes, transitions, stages, maxRework, createdAt, capabilities, estimate);
    }

    public Project withCapabilities(List<Capability> newCapabilities) {
        return new Project(id, key, name, description, kind, templateKey, methodology, issueTypes, transitions, stages, maxRework, createdAt,
                newCapabilities == null ? List.of() : newCapabilities, estimate);
    }

    /**
     * La forma de la tarjeta: cómo se llama cada tipo, sus datos propios y en qué se mide. Los tipos son los que el
     * tablero ya usa (no se agregan ni se quitan: hay tarjetas de cada uno).
     */
    public Project withCard(List<IssueTypeDef> newTypes, EstimateUnit newEstimate) {
        if (newTypes == null || newTypes.size() != issueTypes.size()
                || !newTypes.stream().map(IssueTypeDef::type).collect(java.util.stream.Collectors.toSet())
                        .equals(issueTypes.stream().map(IssueTypeDef::type).collect(java.util.stream.Collectors.toSet()))) {
            throw new DomainException("Los tipos de tarjeta del tablero no se agregan ni se quitan: cambia su nombre y sus datos.");
        }
        for (IssueTypeDef t : newTypes) {
            if (t.label() == null || t.label().isBlank() || t.label().length() > 40) throw new DomainException("Cada tipo de tarjeta necesita un nombre de hasta 40 caracteres.");
            ec.kuador.board.catalog.FieldDef.validate(t.fields(), t.label());
        }
        return new Project(id, key, name, description, kind, templateKey, methodology, newTypes, transitions, stages, maxRework, createdAt, capabilities,
                newEstimate == null ? estimate : newEstimate);
    }

    public Project withDetails(String newName, String newDescription) {
        return new Project(id, key, newName, newDescription, kind, templateKey, methodology, issueTypes, transitions, stages, maxRework, createdAt, capabilities, estimate);
    }

    public Project withTransitions(Transitions newTransitions) {
        return new Project(id, key, name, description, kind, templateKey, methodology, issueTypes, newTransitions, stages, maxRework, createdAt, capabilities, estimate);
    }

    public Project withStages(List<StageDef> newStages, int newMaxRework) {
        return new Project(id, key, name, description, kind, templateKey, methodology, issueTypes, transitions, newStages, newMaxRework, createdAt, capabilities, estimate);
    }
}
