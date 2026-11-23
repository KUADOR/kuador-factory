package ec.kuador.board.connections.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.connections.domain.ApiAccount;
import ec.kuador.board.shared.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OpenApiReaderTest {

    @Test
    void readsAnOpenApi3DocumentIntoToolsTheAgentCanUse() throws Exception {
        String text = new String(new ClassPathResource("demo-erp/openapi.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        OpenApiReader.Spec spec = OpenApiReader.read(text, "https://go-api.kuador.example/demo-erp/openapi.json");

        assertThat(spec.baseUrl()).as("servidor relativo: se completa con el origen de la documentación").isEqualTo("https://go-api.kuador.example/demo-erp");
        assertThat(spec.operations()).extracting(ApiAccount.Operation::name).containsExactly("listar_proveedores", "buscar_pedidos_compra", "obtener_pedido_compra",
                "listar_facturas_proveedor", "registrar_factura_proveedor");
        ApiAccount.Operation get = spec.operations().get(2);
        assertThat(get.readOnly()).isTrue();
        assertThat(get.params()).singleElement().isEqualTo(new ApiAccount.Param("numero", "path", true));
        assertThat(get.description()).startsWith("Detalle de un pedido de compra").endsWith("(GET /pedidos-compra/{numero})");

        ApiAccount.Operation post = spec.operations().get(4);
        assertThat(post.readOnly()).isFalse();
        assertThat(post.body()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) post.schema().get("properties")).get("body");
        assertThat(body).as("la referencia se resuelve").containsEntry("type", "object");
        assertThat(((Map<?, ?>) body.get("properties")).keySet()).map(Object::toString).contains("numero_factura", "ruc_proveedor", "numero_pedido", "total");
        assertThat(post.schema()).containsEntry("required", java.util.List.of("body"));
    }

    @Test
    void alsoReadsSwagger2InYaml() {
        String yaml = """
                swagger: "2.0"
                info: {title: Tienda}
                host: erp.flores.example
                basePath: /api/v1
                schemes: [https]
                paths:
                  /orders/{id}:
                    get:
                      summary: Get order
                      parameters:
                        - {name: id, in: path, required: true, type: string}
                        - {name: Authorization, in: header, type: string}
                  /invoices:
                    post:
                      parameters:
                        - name: invoice
                          in: body
                          required: true
                          schema: {type: object, properties: {number: {type: string}}}
                """;
        OpenApiReader.Spec spec = OpenApiReader.read(yaml, null);
        assertThat(spec.baseUrl()).isEqualTo("https://erp.flores.example/api/v1");
        assertThat(spec.operations()).extracting(ApiAccount.Operation::name).containsExactly("get_orders_id", "post_invoices");
        assertThat(spec.operations().get(0).params()).as("la credencial la pone el tablero, no el modelo").extracting(ApiAccount.Param::name).containsExactly("id");
        assertThat(spec.operations().get(1).body()).isTrue();
    }

    @Test
    void saysWhatIsWrong() {
        assertThatThrownBy(() -> OpenApiReader.read("{\"hola\": 1}", null)).isInstanceOf(DomainException.class).hasMessageContaining("OpenAPI");
        assertThatThrownBy(() -> OpenApiReader.read("::: no es yaml :::\n  - [", null)).isInstanceOf(DomainException.class);
    }

    @Test
    void leavesOutArgumentNamesTheAiProvidersReject() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"T"},"servers":[{"url":"https://erp.example/api"}],"paths":{
                  "/pedidos":{"get":{"operationId":"listar","parameters":[{"name":"page[size]","in":"query","schema":{"type":"integer"}},
                                                                          {"name":"estado","in":"query","schema":{"type":"string"}}]}},
                  "/raro/{id de pedido}":{"get":{"operationId":"raro","parameters":[{"name":"id de pedido","in":"path","required":true,"schema":{"type":"string"}}]}}}}
                """;
        OpenApiReader.Spec spec = OpenApiReader.read(json, null);
        assertThat(spec.operations()).extracting(ApiAccount.Operation::name).as("sin su parámetro de ruta no se puede llamar").containsExactly("listar");
        assertThat(spec.operations().getFirst().params()).extracting(ApiAccount.Param::name).containsExactly("estado");
    }
}
