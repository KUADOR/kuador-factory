package ec.kuador.board.catalog;

import java.util.List;

/**
 * Un tipo de tarjeta que usa una plantilla: el nombre con que se muestra (p. ej. «Oportunidad») y sus datos propios
 * ({@code fields}; vacío en lo guardado antes de que existieran).
 */
public record IssueTypeDef(IssueType type, String label, List<FieldDef> fields) {

    public IssueTypeDef {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public IssueTypeDef(IssueType type, String label) {
        this(type, label, List.of());
    }
}
