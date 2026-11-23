package ec.kuador.board.project;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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
 * Un tablero que no es de software se adapta a su negocio: enciende lo que usa (documentos, reparto) y su tarjeta lleva
 * los datos propios del negocio, validados por tipo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AdaptableBoardIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    @Test
    void aSalesBoardTurnsOnWhatItUsesAndItsCardCarriesTheBusinessData() throws Exception {
        signup.requestCode("sol@adapta.test");
        signup.complete(signup.verifyCode("sol@adapta.test", INBOX.get("sol@adapta.test")), new SignupService.Company("Adapta", null, null, null, null, null),
                "Sol", "clave-de-sol-123", "FACTORY");
        String owner = auth.login("sol@adapta.test", "clave-de-sol-123").token();

        JsonNode board = call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner).body();
        assertThat(board.get("kind").asString()).isEqualTo("GENERAL");
        assertThat(board.get("capabilities").toString()).isEqualTo("[\"DOCUMENTS\"]");
        assertThat(board.get("estimate").asString()).isEqualTo("POINTS");

        // Enciende el reparto; el repositorio de código no se enciende después de creado.
        assertThat(call("PUT", "/api/projects/VEN/capabilities", Map.of("capabilities", List.of("DOCUMENTS", "SPLIT")), owner).body().get("capabilities").toString())
                .isEqualTo("[\"DOCUMENTS\",\"SPLIT\"]");
        assertThat(call("PUT", "/api/projects/VEN/capabilities", Map.of("capabilities", List.of("REPOSITORY")), owner).status()).isEqualTo(422);
        // Un tablero de software no pierde su repositorio.
        call("POST", "/api/projects", Map.of("key", "SOF", "name", "Software", "templateKey", "SOFTWARE"), owner);
        assertThat(call("PUT", "/api/projects/SOF/capabilities", Map.of("capabilities", List.of("DOCUMENTS")), owner).status()).isEqualTo(422);
        // Y ya puede llevar documentos: elige su manual sin tener repositorio.
        assertThat(call("PUT", "/api/projects/VEN/methodology", Map.of("methodology", "FREE"), owner).status()).isEqualTo(200);
        assertThat(call("GET", "/api/projects/VEN/docs", null, owner).status()).isEqualTo(200);

        // La tarjeta de una oportunidad lleva monto, cliente y origen; el tablero no estima.
        List<Map<String, Object>> types = List.of(
                Map.of("type", "TASK", "label", "Oportunidad", "fields", List.of(
                        Map.of("key", "monto", "label", "Monto", "kind", "MONEY"),
                        Map.of("key", "cliente", "label", "Cliente", "kind", "TEXT"),
                        Map.of("key", "origen", "label", "Origen", "kind", "CHOICE", "options", List.of("Referido", "Web")))),
                Map.of("type", "EPIC", "label", "Campaña"));
        JsonNode shaped = call("PUT", "/api/projects/VEN/card", Map.of("issueTypes", types, "estimate", "NONE"), owner).body();
        assertThat(shaped.get("estimate").asString()).isEqualTo("NONE");
        assertThat(shaped.get("issueTypes").get(0).get("fields")).hasSize(3);
        assertThat(call("PUT", "/api/projects/VEN/card", Map.of("issueTypes", List.of(Map.of("type", "TASK", "label", "Oportunidad")), "estimate", "NONE"), owner).status())
                .as("los tipos no se quitan").isEqualTo(422);

        Map<String, Object> card = Map.of("title", "Clínica Andes", "type", "TASK", "priority", "MEDIUM",
                "fields", Map.of("monto", "1250,50", "cliente", "Clínica Andes", "origen", "web", "inventado", "x"));
        JsonNode created = call("POST", "/api/projects/VEN/issues", card, owner).body();
        String key = created.get("key").asString();
        assertThat(created.get("fields").toString()).isEqualTo("{\"monto\":\"1250.5\",\"cliente\":\"Clínica Andes\",\"origen\":\"Web\"}");
        assertThat(call("POST", "/api/projects/VEN/issues", Map.of("title", "Mal", "type", "TASK", "priority", "MEDIUM", "fields", Map.of("monto", "mucho")), owner).status())
                .isEqualTo(422);

        // Editar sin mandar los datos los conserva; mandarlos los reemplaza.
        Map<String, Object> edit = Map.of("title", "Clínica Andes · renovación", "type", "TASK", "priority", "HIGH");
        assertThat(call("PUT", "/api/issues/" + key, edit, owner).body().get("fields").get("monto").asString()).isEqualTo("1250.5");
        Map<String, Object> edit2 = Map.of("title", "Clínica Andes · renovación", "type", "TASK", "priority", "HIGH", "fields", Map.of("monto", "3000"));
        assertThat(call("PUT", "/api/issues/" + key, edit2, owner).body().get("fields").toString()).isEqualTo("{\"monto\":\"3000\"}");
        assertThat(call("GET", "/api/issues/" + key, null, owner).body().get("fields").get("monto").asString()).isEqualTo("3000");
    }
}
