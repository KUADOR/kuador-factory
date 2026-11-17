package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * El catálogo de la plataforma: se siembra, se edita en borrador y al publicar llega a los espacios (lo nuevo a todos;
 * lo actualizado solo a quienes no lo modificaron). Los paquetes por industria arrancan un espacio con su tablero.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "board.platform.admins=pia@cat.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class PlatformCatalogIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

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

    private JsonNode call(String method, String path, Object body, String token) {
        try {
            var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (token != null) r.header("Authorization", "Bearer " + token);
            HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            ObjectNode out = json.createObjectNode().put("_status", res.statusCode());
            if (!res.body().isBlank()) out.set("body", json.readTree(res.body()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String account(String email, String company, String packageCode) {
        signup.requestCode(email);
        signup.complete(signup.verifyCode(email, INBOX.get(email)), new SignupService.Company(company, null, null, null, null, null),
                "Persona", "clave-larga-de-prueba-1", "TEAM", packageCode);
        return auth.login(email, "clave-larga-de-prueba-1").token();
    }

    private synchronized String admin() {
        try {
            return auth.login("pia@cat.test", "clave-larga-de-prueba-1").token();
        } catch (RuntimeException e) {
            return account("pia@cat.test", "Plataforma", null);
        }
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 75 && !condition.getAsBoolean(); i++) Thread.sleep(200);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    @Test
    void theOwnerPolishesTheCatalogAndCompaniesReceiveIt() throws Exception {
        String admin = admin();
        String untouched = account("ana@cat.test", "Sin cambios", null);
        String edited = account("beto@cat.test", "Con cambios", null);
        assertThat(call("GET", "/api/platform/catalog", null, untouched).get("_status").asInt()).isEqualTo(403);

        JsonNode summary = call("GET", "/api/platform/catalog", null, admin).get("body");
        assertThat(summary.get("version").asInt()).isEqualTo(8);
        assertThat(summary.get("pending").asInt()).isZero();
        JsonNode analyst = call("GET", "/api/platform/catalog/roles", null, admin).get("body").valueStream()
                .filter(e -> e.get("key").asString().equals("ANALYST")).findFirst().orElseThrow();
        assertThat(analyst.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(analyst.get("memberName").asString()).isEqualTo("Agente Analista");

        // Un espacio personaliza su rol de Marketing: esa versión se respeta.
        JsonNode own = call("GET", "/api/catalog/roles/MARKETING", null, edited).get("body");
        ((ObjectNode) own).put("instructions", "Nuestras reglas de marketing.");
        assertThat(call("PUT", "/api/catalog/roles/MARKETING", own, edited).get("_status").asInt()).isEqualTo(200);

        // La plataforma pule el Analista y Marketing, y agrega un rol nuevo: quedan en borrador.
        ObjectNode better = (ObjectNode) analyst.get("entry").deepCopy();
        better.put("instructions", "Manual mejorado del analista.");
        assertThat(call("PUT", "/api/platform/catalog/roles/ANALYST", Map.of("entry", better), admin).get("body").get("status").asString())
                .isEqualTo("MODIFIED");
        ObjectNode marketing = (ObjectNode) call("GET", "/api/platform/catalog/roles", null, admin).get("body").valueStream()
                .filter(e -> e.get("key").asString().equals("MARKETING")).findFirst().orElseThrow().get("entry").deepCopy();
        marketing.put("instructions", "Manual mejorado de marketing.");
        call("PUT", "/api/platform/catalog/roles/MARKETING", Map.of("entry", marketing), admin);
        Map<String, Object> community = new LinkedHashMap<>();
        community.put("key", "COMMUNITY");
        community.put("name", "Comunidad y redes");
        community.put("description", "Responde y modera comentarios en redes.");
        community.put("instructions", "Eres quien cuida la comunidad.");
        community.put("tools", List.of());
        community.put("automation", "NONE");
        JsonNode created = call("PUT", "/api/platform/catalog/roles/COMMUNITY",
                Map.of("entry", community, "memberName", "Agente Comunidad", "color", "#22d3ee"), admin).get("body");
        assertThat(created.get("status").asString()).isEqualTo("NEW");
        assertThat(call("GET", "/api/catalog/roles/ANALYST", null, untouched).get("body").get("instructions").asString())
                .as("el borrador no llega a nadie").doesNotContain("mejorado");

        JsonNode published = call("POST", "/api/platform/catalog/publish", null, admin).get("body");
        assertThat(published.get("version").asInt()).isEqualTo(9);
        assertThat(published.get("pending").asInt()).isZero();
        assertThat(call("POST", "/api/platform/catalog/publish", null, admin).get("_status").asInt()).as("sin cambios no hay versión").isEqualTo(422);

        eventually("la mejora llega a quien no la tocó", () -> call("GET", "/api/catalog/roles/ANALYST", null, untouched).get("body")
                .get("instructions").asString().equals("Manual mejorado del analista."));
        eventually("el rol nuevo llega a todos", () -> call("GET", "/api/catalog/roles/COMMUNITY", null, edited).get("_status").asInt() == 200);
        assertThat(call("GET", "/api/catalog/roles/MARKETING", null, edited).get("body").get("instructions").asString())
                .as("se respeta lo que el espacio editó").isEqualTo("Nuestras reglas de marketing.");
        assertThat(call("GET", "/api/catalog/roles/MARKETING", null, untouched).get("body").get("instructions").asString())
                .isEqualTo("Manual mejorado de marketing.");
    }

    @Test
    void aPackageStartsTheWorkspaceWithItsBoardAndTeam() throws Exception {
        JsonNode packages = call("GET", "/api/public/packages", null, null).get("body");
        assertThat(packages.findValuesAsString("code")).containsExactly("COMMERCE", "SERVICES", "SOFTWARE");

        String owner = account("flor@cat.test", "Florería", "COMMERCE");
        assertThat(call("GET", "/api/workspace/package", null, owner).get("body").get("code").asString()).isEqualTo("COMMERCE");
        JsonNode board = call("GET", "/api/projects/VEN", null, owner);
        assertThat(board.get("_status").asInt()).isEqualTo(200);
        assertThat(board.get("body").get("name").asString()).isEqualTo("Ventas");
        eventually("con su equipo", () -> call("GET", "/api/projects/VEN/team", null, owner).get("body").valueStream()
                .filter(a -> a.get("inTeam").asBoolean()).map(a -> a.get("role").asString()).toList().containsAll(List.of("ANALYST", "MARKETING")));

        // La plataforma edita un paquete.
        String admin = admin();
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", "Comercio y ventas");
        pkg.put("description", "Tiendas y distribuidoras.");
        pkg.put("templates", List.of("SALES"));
        pkg.put("starterTemplate", "TASKS");
        pkg.put("starterKey", "VEN");
        pkg.put("starterName", "Ventas");
        pkg.put("sortOrder", 10);
        pkg.put("active", true);
        assertThat(call("PUT", "/api/platform/packages/COMMERCE", pkg, admin).get("body").get("detail").asString()).contains("plantillas del paquete");
        pkg.put("starterTemplate", "SALES");
        assertThat(call("PUT", "/api/platform/packages/COMMERCE", pkg, admin).get("body").get("templates").toString()).isEqualTo("[\"SALES\"]");
    }
}
