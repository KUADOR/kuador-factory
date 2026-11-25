package ec.kuador.board.agents;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Qué ejecuciones se dan por interrumpidas: las del servidor que dejaron de dar señal de vida. Las que la dieron hace
 * poco (siguen vivas en otra réplica) y las que corren en la máquina de una persona no se tocan.
 */
@SpringBootTest(properties = {"board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AgentRunRecoveryIT {

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
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext context;

    private UUID run(UUID workspace, String model, Instant createdAt, Instant heartbeatAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_run (id, workspace_id, agent_id, issue_key, project_key, trigger_event_id, status, model, created_at, heartbeat_at)
                values (?, ?, ?, 'X-1', 'X', ?, 'RUNNING', ?, ?, ?)
                """).params(id, workspace, UUID.randomUUID(), UUID.randomUUID(), model, Timestamp.from(createdAt),
                heartbeatAt == null ? null : Timestamp.from(heartbeatAt)).update();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("select status from agent_run where id = ?").param(id).query(String.class).single();
    }

    @Test
    void onlyServerRunsThatWentSilentAreInterrupted() {
        signup.requestCode("rita@recupera.test");
        signup.complete(signup.verifyCode("rita@recupera.test", INBOX.get("rita@recupera.test")),
                new SignupService.Company("Recupera", null, null, null, null, null), "Rita", "clave-larga-de-prueba-1", "TEAM", null);
        UUID workspace = jdbc.sql("select id from workspace where name = 'Recupera'").query(UUID.class).single();
        Instant now = Instant.now();

        UUID silent = run(workspace, null, now.minusSeconds(900), now.minusSeconds(600));
        UUID neverBeat = run(workspace, null, now.minusSeconds(900), null);
        UUID aliveElsewhere = run(workspace, null, now.minusSeconds(900), now.minusSeconds(10));
        UUID justStarted = run(workspace, null, now.minusSeconds(5), null);
        UUID onSomeonesPc = run(workspace, "local:claude-code", now.minusSeconds(900), null);

        Object recovery = context.getBean("agentRunRecovery");
        ReflectionTestUtils.invokeMethod(recovery, "recover");

        assertThat(status(silent)).isEqualTo("FAILED");
        assertThat(status(neverBeat)).isEqualTo("FAILED");
        assertThat(jdbc.sql("select error from agent_run where id = ?").param(silent).query(String.class).single()).contains("Vuelve a asignarle");
        assertThat(status(aliveElsewhere)).as("sigue viva en otra réplica").isEqualTo("RUNNING");
        assertThat(status(justStarted)).as("recién empezó").isEqualTo("RUNNING");
        assertThat(status(onSomeonesPc)).as("corre en la máquina de una persona").isEqualTo("RUNNING");
    }
}
