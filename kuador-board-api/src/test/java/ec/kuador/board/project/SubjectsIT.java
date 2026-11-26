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
import java.util.ArrayList;
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
 * Un tablero es un equipo y su flujo, y trabaja en uno o varios proyectos del espacio (sistemas, marcas). Cada tarjeta
 * pertenece a un proyecto: la épica lo elige y sus tarjetas lo heredan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class SubjectsIT {

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

    private String owner(String mail, String company) {
        signup.requestCode(mail);
        signup.complete(signup.verifyCode(mail, INBOX.get(mail)), new SignupService.Company(company, null, null, null, null, null), "Sol", "clave-de-sol-123", "FACTORY");
        return auth.login(mail, "clave-de-sol-123").token();
    }

    private static List<String> keys(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("key").asString()));
        return out;
    }

    @Test
    void aBoardStartsWithItsOwnProjectAndItsCardsBelongToIt() throws Exception {
        String owner = owner("sol@unproyecto.test", "Un proyecto");
        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner);

        JsonNode subjects = call("GET", "/api/projects/VEN/subjects", null, owner).body();
        assertThat(keys(subjects)).containsExactly("VEN");
        assertThat(subjects.get(0).get("name").asString()).isEqualTo("Ventas");

        JsonNode card = call("POST", "/api/projects/VEN/issues", Map.of("title", "Llamar al cliente", "type", "TASK"), owner).body();
        assertThat(card.get("subjectKey").asString()).isEqualTo("VEN");
        // El único proyecto del tablero no se quita.
        assertThat(call("DELETE", "/api/projects/VEN/subjects/VEN", null, owner).status()).isEqualTo(422);
    }

    @Test
    void aTeamBoardWorksOnSeveralProjectsAndTheEpicChoosesOne() throws Exception {
        String owner = owner("sol@varios.test", "Varios");
        // Los proyectos se crean una vez en el espacio; la clave sale del nombre si no se escribe.
        JsonNode medica = call("POST", "/api/subjects", Map.of("name", "Kuador Médica"), owner).body();
        assertThat(medica.get("key").asString()).isEqualTo("KM");
        call("POST", "/api/subjects", Map.of("key", "KUA", "name", "Kuador"), owner);
        assertThat(call("POST", "/api/subjects", Map.of("key", "KUA", "name", "Otro"), owner).status()).isEqualTo(409);

        // Marketing atiende los dos; Desarrollo nace con uno y suma otro nuevo desde el tablero.
        call("POST", "/api/projects", Map.of("key", "MKT", "name", "Marketing", "templateKey", "TASKS", "subjects", List.of("KUA", "KM")), owner);
        assertThat(keys(call("GET", "/api/projects/MKT/subjects", null, owner).body())).containsExactly("KUA", "KM");
        call("POST", "/api/projects", Map.of("key", "DEV", "name", "Desarrollo", "templateKey", "SOFTWARE", "subjects", List.of("KUA")), owner);
        assertThat(keys(call("POST", "/api/projects/DEV/subjects", Map.of("name", "Playtodoo"), owner).body())).containsExactly("KUA", "PLAY");

        // El mismo proyecto lo atienden dos tableros.
        JsonNode all = call("GET", "/api/subjects", null, owner).body();
        JsonNode kuador = null;
        for (JsonNode s : all) if (s.get("key").asString().equals("KUA")) kuador = s;
        assertThat(kuador.get("boards").toString()).isEqualTo("[\"DEV\",\"MKT\"]");

        // La épica elige el proyecto; sin elegir, queda en el primero del tablero.
        JsonNode epic = call("POST", "/api/projects/MKT/issues", Map.of("title", "Campaña de diciembre", "type", "EPIC", "subjectKey", "KM"), owner).body();
        assertThat(epic.get("subjectKey").asString()).isEqualTo("KM");
        assertThat(call("POST", "/api/projects/MKT/issues", Map.of("title", "Suelta", "type", "TASK"), owner).body().get("subjectKey").asString()).isEqualTo("KUA");
        // Un proyecto que el tablero no atiende no se acepta.
        assertThat(call("POST", "/api/projects/MKT/issues", Map.of("title", "Ajena", "type", "TASK", "subjectKey", "PLAY"), owner).status()).isEqualTo(422);

        // Las tarjetas de la épica heredan su proyecto, aunque pidan otro, y lo siguen si la épica cambia.
        String epicKey = epic.get("key").asString();
        JsonNode child = call("POST", "/api/projects/MKT/issues", Map.of("title", "Piezas gráficas", "type", "TASK", "parentKey", epicKey, "subjectKey", "KUA"), owner).body();
        assertThat(child.get("subjectKey").asString()).isEqualTo("KM");
        call("PUT", "/api/issues/" + epicKey, Map.of("title", "Campaña de diciembre", "type", "EPIC", "subjectKey", "KUA"), owner);
        assertThat(call("GET", "/api/issues/" + child.get("key").asString(), null, owner).body().get("subjectKey").asString()).isEqualTo("KUA");
        // Editar sin decir el proyecto lo conserva.
        call("PUT", "/api/issues/" + epicKey, Map.of("title", "Campaña de fin de año", "type", "EPIC"), owner);
        assertThat(call("GET", "/api/issues/" + epicKey, null, owner).body().get("subjectKey").asString()).isEqualTo("KUA");

        // Con tarjetas, el proyecto no se quita del tablero; sin tarjetas, sí. Y solo se borra cuando nadie lo atiende.
        assertThat(call("DELETE", "/api/projects/MKT/subjects/KUA", null, owner).status()).isEqualTo(422);
        assertThat(keys(call("DELETE", "/api/projects/MKT/subjects/KM", null, owner).body())).containsExactly("KUA");
        assertThat(call("DELETE", "/api/subjects/KUA", null, owner).status()).isEqualTo(422);
        assertThat(call("DELETE", "/api/subjects/KM", null, owner).status()).isEqualTo(204);

        // Borrar un tablero no borra sus proyectos: siguen en el espacio.
        assertThat(call("DELETE", "/api/projects/DEV?confirm=DEV", null, owner).status()).isEqualTo(204);
        assertThat(keys(call("GET", "/api/subjects", null, owner).body())).contains("KUA", "PLAY");
    }
}
