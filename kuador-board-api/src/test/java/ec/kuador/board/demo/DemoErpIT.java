package ec.kuador.board.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El ERP de demostración conectado con el conector de API, de punta a punta: se lee su documentación, se eligen las
 * operaciones, el agente consulta un pedido y, cuando se habilita, registra la factura; el ERP rechaza la repetida.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.connections.allow-private=true",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class DemoErpIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        registry.add("board.secrets-key", () -> java.util.Base64.getEncoder().encodeToString(key));
    }

    static final Map<String, String> CODES = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return CODES::put;
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired ConnectionsApi connections;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body, String raw) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = null;
        try {
            parsed = res.body().isBlank() ? null : json.readTree(res.body());
        } catch (RuntimeException e) {
            // PDF u otro contenido.
        }
        return new Reply(res.statusCode(), parsed, res.body());
    }

    @Test
    void theAgentChecksTheOrderAndRegistersTheInvoiceThroughTheApi() throws Exception {
        signup.requestCode("flor@demo.test");
        Workspace w = signup.complete(signup.verifyCode("flor@demo.test", CODES.get("flor@demo.test")),
                new SignupService.Company("Flores Demo", null, null, null, null, null), "Flor", "clave-de-flor-123", "FACTORY");
        String owner = auth.login("flor@demo.test", "clave-de-flor-123").token();
        call("POST", "/api/projects", Map.of("key", "FAC", "name", "Facturas", "templateKey", "TASKS"), owner);

        assertThat(call("GET", "/demo-erp/pedidos-compra", null, null).status()).as("los datos piden la clave").isEqualTo(401);
        Reply how = call("GET", "/api/connection-directory/demo-erp", null, owner);
        assertThat(how.body().get("samples")).hasSize(3);

        String specUrl = "http://localhost:" + port + "/demo-erp/openapi.json";
        Reply spec = call("POST", "/api/connection-directory/openapi", Map.of("url", specUrl), owner);
        assertThat(spec.status()).as(spec.raw()).isEqualTo(200);
        assertThat(spec.body().get("baseUrl").asString()).isEqualTo("http://localhost:" + port + "/demo-erp");
        assertThat(spec.body().get("operations")).hasSize(5);

        Reply saved = call("PUT", "/api/connections/ERP/api", Map.of("name", "ERP de compras", "specUrl", specUrl, "header", "X-API-Key", "prefix", "",
                "credential", how.body().get("credential").asString(), "operations", List.of("obtener_pedido_compra", "registrar_factura_proveedor")), owner);
        assertThat(saved.status()).as(saved.raw()).isEqualTo(200);
        assertThat(saved.body().get("url").asString()).startsWith("api+http://localhost");
        assertThat(saved.raw()).as("la clave no vuelve").doesNotContain("demo-flores-2026");
        call("PUT", "/api/connections/ERP/boards", Map.of("boards", List.of(Map.of("projectKey", "FAC", "roles", List.of()))), owner);

        UUID agent = UUID.randomUUID();
        TenantContext.run(w.id(), () -> {
            assertThat(connections.toolsFor("FAC", "ANALYST")).as("registrar escribe: apagado hasta habilitarlo").extracting(ConnectionsApi.Tool::name)
                    .containsExactly("erp__obtener_pedido_compra");
            assertThat(connections.call("FAC", "ANALYST", "erp__obtener_pedido_compra", Map.of("numero", "OC-2026-0451"), agent, "FAC-1"))
                    .contains("Caja de cartón quarter box", "\"precio_unitario\":3.20", "Cartones del Sur");
            assertThatThrownBy(() -> connections.call("FAC", "ANALYST", "erp__obtener_pedido_compra", Map.of("numero", "OC-2026-0999"), agent, "FAC-1"))
                    .hasMessageContaining("404").hasMessageContaining("No existe el pedido");
        });

        call("PUT", "/api/connections/ERP/writes", Map.of("tools", List.of("registrar_factura_proveedor")), owner);
        Map<String, Object> invoice = Map.of("numero_factura", "001-002-000004518", "ruc_proveedor", "1790012345001", "numero_pedido", "OC-2026-0451", "total", 2047.00);
        TenantContext.run(w.id(), () -> {
            assertThat(connections.call("FAC", "ANALYST", "erp__registrar_factura_proveedor", Map.of("body", invoice), agent, "FAC-1"))
                    .contains("REG-1001", "REGISTRADA");
            assertThatThrownBy(() -> connections.call("FAC", "ANALYST", "erp__registrar_factura_proveedor", invoice, agent, "FAC-1"))
                    .as("argumentos sueltos también valen como cuerpo; el ERP rechaza la repetida").hasMessageContaining("ya tiene una factura");
        });

        Reply pdf = call("GET", "/demo-erp/muestras/factura-precio-distinto.pdf", null, null);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.raw()).startsWith("%PDF");
        call("POST", "/demo-erp/reiniciar", null, null);
    }
}
