package ec.kuador.board.agents.infrastructure.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoProposal;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifica la petición que arma el adaptador y cómo interpreta las respuestas, con una API falsa. */
class ClaudeProductOwnerModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROPOSAL = """
            {"understanding":"Recordar citas","userStory":"Como paciente quiero un recordatorio","acceptanceCriteria":["Dado…, cuando…, entonces…"],
             "openQuestions":[],"outOfScope":[],"storyPoints":3,"complexity":"SUBSTANTIAL","document":"# Spec"}""";

    private HttpServer server;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastBeta = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            lastBody.set(JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            lastBeta.set(ex.getRequestHeaders().getFirst("anthropic-beta"));
            // El SDK 3.x envía la clave en Authorization (x-api-key si fuera una versión anterior).
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            lastKey.set(auth != null ? auth : ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ProductOwnerModel model(String key) {
        return new ClaudeProductOwnerModel(key, "claude-opus-5-5", "medium", true, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String message(String stopReason, String text, String extra) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":%s}],"stop_reason":"%s","stop_sequence":null%s,
                 "usage":{"input_tokens":1500,"output_tokens":700}}""".formatted(JSON.writeValueAsString(text), stopReason, extra);
    }

    private static PoContext context() {
        return new PoContext("Agente PO", "Convierte ideas.", "Eres el Product Owner / Analista.", "KUA", "Kuador", "SOFTWARE", "SDD", "La spec dirige.", List.of("regla"),
                List.of(), new PoContext.Issue("KUA-8", "Recordatorio por WhatsApp", null, "STORY", "Backlog", "HIGH", null, List.of()),
                null, List.of(), new PoContext.DocTask("SPEC", "Spec", false, null), null);
    }

    @Test
    void sendsAStructuredRequestAndParsesTheProposal() {
        reply = message("end_turn", PROPOSAL, "");

        ProductOwnerModel.Result r = model("sk-ant-prueba").propose(context());

        assertThat(r.proposal().complexity()).isEqualTo(PoProposal.Complexity.SUBSTANTIAL);
        assertThat(r.proposal().acceptanceCriteria()).hasSize(1);
        assertThat(r.model()).isEqualTo("claude-opus-5-5");
        assertThat(r.inputTokens()).isEqualTo(1500);
        JsonNode body = lastBody.get();
        assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(body.path("system").asString()).contains("Product Owner");
        assertThat(body.path("messages").get(0).path("content").toString()).contains("Recordatorio por WhatsApp");
        assertThat(body.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("properties").has("acceptanceCriteria")).isTrue();
        assertThat(body.has("thinking")).as("Opus 5.5: thinking siempre activo, no se envía").isFalse();
        assertThat(body.path("fallbacks").asString()).isEqualTo("default");
        assertThat(lastBeta.get()).contains("server-side-fallback-2026-07-01");
        assertThat(lastKey.get()).as("la clave viaja solo en el encabezado").endsWith("sk-ant-prueba");
    }

    @Test
    void refusalAndTruncationBecomeClearErrors() {
        reply = message("refusal", "", ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"x\"}");
        assertThatThrownBy(() -> model("k").propose(context())).isInstanceOf(AgentModelException.class).hasMessageContaining("declinó")
                .hasMessageContaining("cyber");

        reply = message("max_tokens", "{\"understanding\":", "");
        assertThatThrownBy(() -> model("k").propose(context())).hasMessageContaining("se cortó por longitud");
    }

    @Test
    void badKeyIsReportedWithoutLeakingIt() {
        status = 401;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";

        assertThatThrownBy(() -> model("sk-ant-secreta").propose(context())).isInstanceOf(AgentModelException.class)
                .hasMessageContaining("rechazó la clave").hasMessageNotContaining("sk-ant-secreta");
    }

    @Test
    void withoutKeyAgentsAreDisabled() {
        ProductOwnerModel m = model("");
        assertThat(m.available()).isFalse();
        assertThat(m.unavailableReason()).contains("ANTHROPIC_API_KEY");
    }
}
