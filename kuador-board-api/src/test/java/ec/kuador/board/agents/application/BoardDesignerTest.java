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

    @Test
    void anInvoiceBoardUsesTheConnectedMailboxAndErpAndSaysWhatEachAgentDoes() {
        var tool = (java.util.function.BiFunction<String, String, ec.kuador.board.connections.ConnectionsApi.Tool>) (conn, name) ->
                new ec.kuador.board.connections.ConnectionsApi.Tool(conn.toLowerCase() + "__" + name, conn, conn, name, "", java.util.Map.of(), !name.startsWith("registrar"));
        var systems = List.of(
                new ec.kuador.board.connections.ConnectionsApi.Summary("CORREO_FACTURAS", "Correo de facturas", true, List.of(tool.apply("CORREO_FACTURAS", "leer_adjunto")),
                        List.of()),
                new ec.kuador.board.connections.ConnectionsApi.Summary("ERP", "ERP de compras", false, List.of(tool.apply("ERP", "obtener_pedido_compra")),
                        List.of(tool.apply("ERP", "registrar_factura_proveedor"))));
        BoardDesigner.Design d = designer.design("""
                {"name":"Facturas de proveedores","key":"FAC","goal":"Que cada factura quede ingresada si coincide con su pedido.","summary":"…",
                 "workItem":"Factura","uses":["ERP","CORREO_FACTURAS","INVENTADO"],"mailbox":"correo_facturas",
                 "writes":["ERP:registrar_factura_proveedor","ERP:borrar_todo","CRM:registrar"],"missing":["La lista de precios acordados"],
                 "fields":[{"label":"Proveedor","kind":"TEXT"}],
                 "steps":[
                   {"name":"Factura recibida","category":"TODO"},
                   {"name":"Leer la factura","category":"IN_PROGRESS","roles":["ANALYST"],"instructions":"Lee el PDF adjunto y llena proveedor, número y total."},
                   {"name":"Cruzar y registrar","category":"IN_PROGRESS","roles":["ANALYST"],
                    "instructions":"Consulta el pedido con obtener_pedido_compra. Si todo coincide, regístrala con registrar_factura_proveedor. Si no, pregunta a una persona."},
                   {"name":"Revisión","category":"IN_PROGRESS","gate":true,"instructions":"esto no va en una puerta"},
                   {"name":"Ingresada","category":"DONE"}]}
                """, ROLES, systems);

        assertThat(d.connections()).as("solo sistemas que existen").containsExactly("ERP", "CORREO_FACTURAS");
        assertThat(d.mailbox()).isEqualTo("CORREO_FACTURAS");
        assertThat(d.writes()).as("solo acciones reales que cambian datos").containsExactly("ERP:registrar_factura_proveedor");
        assertThat(d.missing()).containsExactly("La lista de precios acordados");
        assertThat(d.template().stages()).filteredOn(st -> st.column().equals("CRUZAR_Y_REGISTRAR")).singleElement()
                .satisfies(st -> assertThat(st.instructions()).contains("registrar_factura_proveedor", "pregunta a una persona"));
        assertThat(d.template().stages()).filteredOn(StageDef::gate).singleElement().satisfies(st -> assertThat(st.instructions()).isNull());
    }

    @Test
    void theQuestionsComeBackCleanWhateverTheModelSays() {
        List<BoardDesigner.Question> qs = designer.questions("""
                Antes de armarlo:
                {"questions":[
                  {"id":"entrada","text":"¿Por dónde llegan las facturas?","kind":"single","options":[{"value":"CORREO","label":"Al buzón Correo de facturas"},{"label":"A mano"}],
                   "help":"Así entran solas como tarjetas."},
                  {"id":"entrada","text":"¿Dónde están los pedidos?","kind":"RARO","options":[]},
                  {"text":""},
                  {"id":"x","text":"1"},{"id":"x","text":"2"},{"id":"x","text":"3"},{"id":"x","text":"4"},{"id":"x","text":"5"}]}
                """);
        assertThat(qs).as("hasta 6, sin las vacías").hasSize(6);
        assertThat(qs.get(0)).satisfies(q -> {
            assertThat(q.kind()).isEqualTo("SINGLE");
            assertThat(q.options()).extracting(BoardDesigner.Option::label).containsExactly("Al buzón Correo de facturas", "A mano");
            assertThat(q.help()).startsWith("Así entran");
        });
        assertThat(qs.get(1).kind()).as("sin opciones, se responde escribiendo").isEqualTo("TEXT");
        assertThat(qs.get(1).id()).as("ids sin repetir").isNotEqualTo(qs.get(0).id());
        assertThat(designer.questions("no es json")).isEmpty();
    }

    @Test
    void decisionsBecomeConditionalPathsBetweenRealSteps() {
        BoardDesigner.Design d = designer.design("""
                {"name":"Facturas","key":"FAC","goal":"g","summary":"s","workItem":"Factura",
                 "steps":[
                   {"name":"Recibida","category":"TODO"},
                   {"name":"Cruce","category":"IN_PROGRESS","roles":["ANALYST"],"instructions":"Compara.",
                    "paths":[{"to":"Ingresada","when":"si todo coincide"},{"to":"Desfase","when":"si algo no coincide"},
                             {"to":"Recibida","when":"si la factura no se puede leer"},{"to":"Inventado","when":"x"},{"to":"Desfase","when":""}]},
                   {"name":"Desfase","category":"IN_PROGRESS","gate":true},
                   {"name":"Ingresada","category":"DONE"}]}
                """, ROLES);
        assertThat(d.template().transitions()).extracting(t -> t.from() + "→" + t.to() + ": " + t.when())
                .as("a pasos que existen, con condición, también hacia atrás").containsExactly("CRUCE→INGRESADA: si todo coincide",
                        "CRUCE→DESFASE: si algo no coincide", "CRUCE→RECIBIDA: si la factura no se puede leer");
    }

    @Test
    void softwareDevelopmentUsesTheSoftwareTemplateAsIs() {
        var factory = ec.kuador.board.testing.InMemoryCatalog.factoryTemplate("SOFTWARE_FACTORY");
        var sales = ec.kuador.board.testing.InMemoryCatalog.factoryTemplate("SALES");
        BoardDesigner.Design d = designer.design("""
                {"template":"software_factory","name":"Mi SaaS","key":"saas","goal":"Lanzar la primera versión.","summary":"Usa la fábrica de software."}
                """, ROLES, List.of(), List.of(sales, factory));
        assertThat(d.baseTemplate()).isEqualTo("SOFTWARE_FACTORY");
        assertThat(d.template()).as("la plantilla tal cual, con sus pasos").isSameAs(factory);
        assertThat(d.key()).isEqualTo("SAAS");
        assertThatThrownBy(() -> designer.design("{\"template\":\"NO_EXISTE\",\"name\":\"x\",\"steps\":[]}", ROLES, List.of(), List.of(factory)))
                .as("una plantilla que no existe no se usa: hacen falta los pasos").isInstanceOf(DomainException.class);
    }
}
