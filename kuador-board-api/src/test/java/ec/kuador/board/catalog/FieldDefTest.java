package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldDefTest {

    private static final List<FieldDef> OPPORTUNITY = List.of(
            new FieldDef("monto", "Monto", FieldDef.Kind.MONEY, null),
            new FieldDef("cliente", "Cliente", FieldDef.Kind.TEXT, null),
            new FieldDef("cierre", "Fecha de cierre", FieldDef.Kind.DATE, null),
            new FieldDef("origen", "Origen", FieldDef.Kind.CHOICE, List.of("Referido", "Web", "Llamada")));

    @Test
    void keepsWhatTheBoardDefinesAndNormalizesEachValue() {
        Map<String, String> clean = FieldDef.clean(OPPORTUNITY, Map.of("monto", " 1250,50 ", "cliente", "Clínica Andes", "cierre", "2026-12-01", "origen", "web",
                "inventado", "x", "otro", ""));
        assertThat(clean).containsExactly(Map.entry("monto", "1250.5"), Map.entry("cliente", "Clínica Andes"), Map.entry("cierre", "2026-12-01"),
                Map.entry("origen", "Web"));
        assertThat(FieldDef.clean(OPPORTUNITY, Map.of("monto", "3000"))).containsExactly(Map.entry("monto", "3000"));
        assertThat(FieldDef.clean(OPPORTUNITY, null)).isEmpty();
    }

    @Test
    void rejectsValuesOfTheWrongKind() {
        assertThatThrownBy(() -> FieldDef.clean(OPPORTUNITY, Map.of("monto", "mucho"))).isInstanceOf(DomainException.class).hasMessageContaining("Monto");
        assertThatThrownBy(() -> FieldDef.clean(OPPORTUNITY, Map.of("cierre", "mañana"))).hasMessageContaining("fecha");
        assertThatThrownBy(() -> FieldDef.clean(OPPORTUNITY, Map.of("origen", "Feria"))).hasMessageContaining("Referido");
    }

    @Test
    void definitionsAreValidated() {
        assertThat(FieldDef.validate(OPPORTUNITY, "Oportunidad")).hasSize(4);
        assertThatThrownBy(() -> FieldDef.validate(List.of(new FieldDef("Monto", "Monto", FieldDef.Kind.MONEY, null)), "Oportunidad")).hasMessageContaining("Clave");
        assertThatThrownBy(() -> FieldDef.validate(List.of(OPPORTUNITY.get(0), OPPORTUNITY.get(0)), "Oportunidad")).hasMessageContaining("dos veces");
        assertThatThrownBy(() -> FieldDef.validate(List.of(new FieldDef("origen", "Origen", FieldDef.Kind.CHOICE, List.of())), "Oportunidad"))
                .hasMessageContaining("opciones");
    }
}
