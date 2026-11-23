package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.Capability;
import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.EstimateUnit;
import ec.kuador.board.catalog.FieldDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.Requirement;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Lo que responde el modelo se convierte en una plantilla válida, diga lo que diga. */
class BoardDesignerTest {

    private final BoardDesigner designer = new BoardDesigner(null, null, null, null, List.of(), null, new ObjectMapper(), null,
            new org.springframework.jdbc.support.JdbcTransactionManager());

    private static final List<String> ROLES = List.of("ANALYST", "MARKETING");

    @Test
    void aClinicGetsItsOwnStepsRolesGatesAndCardData() {
        BoardDesigner.Design d = designer.design("""
                Claro, aquí va:
                {"name":"Atención de pacientes","key":"cli","goal":"Que ningún paciente espere más de un día por su cita.","summary":"El analista prepara el resumen.",
                 "workItem":"Cita","group":null,"split":true,
                 "fields":[{"label":"Paciente","kind":"TEXT"},{"label":"Fecha de la cita","kind":"DATE"},{"label":"Valor","kind":"MONEY"},
                           {"label":"Seguro","kind":"CHOICE","options":["Particular","IESS"]},{"label":"Otro","kind":"RARO"}],
                 "steps":[
                   {"name":"Pedido de cita","category":"IN_PROGRESS","roles":["ANALYST"]},
                   {"name":"Resumen del paciente","category":"IN_PROGRESS","roles":["analyst","BACKEND","INVENTADO"],"requiresFile":true},
                   {"name":"Confirmación","category":"IN_PROGRESS","gate":true,"roles":["ANALYST"],"checklist":["El paciente tiene teléfono","La fecha está libre"]},
                   {"name":"Segunda aprobación","category":"IN_PROGRESS","gate":true},
                   {"name":"Atención","category":"IN_PROGRESS","roles":[]},
                   {"name":"Atendida","category":"TODO"}]}
                """, ROLES);

        assertThat(d.key()).isEqualTo("CLI");
        assertThat(d.goal()).startsWith("Que ningún paciente");
        var t = d.template();
        assertThat(t.columns()).extracting(ColumnDef::key).containsExactly("PEDIDO_DE_CITA", "RESUMEN_DEL_PACIENTE", "CONFIRMACION", "SEGUNDA_APROBACION",
                "ATENCION", "ATENDIDA");
        assertThat(t.columns().getFirst().category()).as("entra por el primer paso").isEqualTo(ColumnCategory.TODO);
        assertThat(t.columns().getLast().category()).as("termina en el último").isEqualTo(ColumnCategory.DONE);

        assertThat(t.stages()).extracting(StageDef::column).as("ni la entrada ni el final llevan etapa; dos puertas seguidas no")
                .containsExactly("RESUMEN_DEL_PACIENTE", "CONFIRMACION");
        StageDef work = t.stages().get(0);
        assertThat(work.roles()).as("solo roles del catálogo que no escriben código").containsExactly("ANALYST");
        assertThat(work.requires()).containsExactly(new Requirement(Requirement.Kind.ATTACHMENT, null));
        StageDef gate = t.stages().get(1);
        assertThat(gate.gate()).isTrue();
        assertThat(gate.roles()).as("una puerta la cierra una persona").isEmpty();
        assertThat(gate.rejectTo()).isEqualTo("RESUMEN_DEL_PACIENTE");
        assertThat(gate.checklist()).hasSize(2);

        assertThat(t.issueTypes()).hasSize(1);
        assertThat(t.issueTypes().getFirst().type()).isEqualTo(IssueType.TASK);
        assertThat(t.issueTypes().getFirst().label()).isEqualTo("Cita");
        assertThat(t.issueTypes().getFirst().fields()).extracting(FieldDef::key, FieldDef::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("paciente", FieldDef.Kind.TEXT), org.assertj.core.groups.Tuple.tuple("fecha_de_la_cita", FieldDef.Kind.DATE),
                org.assertj.core.groups.Tuple.tuple("valor", FieldDef.Kind.MONEY), org.assertj.core.groups.Tuple.tuple("seguro", FieldDef.Kind.CHOICE),
                org.assertj.core.groups.Tuple.tuple("otro", FieldDef.Kind.TEXT));
        assertThat(t.capabilities()).as("sin grupo no hay reparto; nunca repositorio").containsExactly(Capability.DOCUMENTS);
        assertThat(t.estimate()).isEqualTo(EstimateUnit.NONE);
        assertThat(t.teamRoles()).containsExactly("ANALYST");
    }

    @Test
    void anUnusableAnswerIsRejectedWithAMessageForThePerson() {
        assertThatThrownBy(() -> designer.design("No puedo ayudarte con eso.", ROLES)).isInstanceOf(DomainException.class).hasMessageContaining("vuelve a intentarlo");
        assertThatThrownBy(() -> designer.design("{\"name\":\"x\",\"steps\":[{\"name\":\"Uno\"}]}", ROLES)).isInstanceOf(DomainException.class)
                .hasMessageContaining("incompleta");
    }
}
