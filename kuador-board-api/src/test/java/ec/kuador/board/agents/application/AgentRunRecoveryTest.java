package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.testing.NoTx;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Una ejecución que se quedó sin réplica: se retoma sola una vez, y solo si la tarjeta sigue en manos del agente. */
class AgentRunRecoveryTest {

    static final Instant NOW = Instant.parse("2026-11-25T15:00:00Z");
    static final UUID WORKSPACE = UUID.randomUUID();
    static final UUID AGENT = UUID.randomUUID();
    static final UUID PERSON = UUID.randomUUID();

    AgentRunRepository runs = mock(AgentRunRepository.class);
    IssueApi issues = mock(IssueApi.class);
    AgentDispatcher dispatcher = mock(AgentDispatcher.class);
    AgentRunRecovery recovery;
    AgentRun running;

    @BeforeEach
    void setUp() {
        recovery = new AgentRunRecovery(null, runs, new LiveRuns(), issues, dispatcher, null, new NoTx(), Clock.fixed(NOW, ZoneOffset.UTC));
        running = AgentRun.start(AGENT, "PLA-7", "PLA", UUID.randomUUID(), PERSON, NOW.minusSeconds(600));
        when(runs.findById(running.id())).thenReturn(Optional.of(running));
        when(runs.findRecent(AGENT, "PLA-7", 6)).thenReturn(List.of(running));
    }

    private void cardAssignedTo(UUID assignee) {
        when(issues.context("PLA-7")).thenReturn(Optional.of(new IssueApi.IssueContext("PLA-7", "PLA", "Pantallas", "", "TASK", "DESIGN", "MEDIUM", null,
                List.of(), null, assignee, PERSON, List.of())));
    }

    private AgentRun saved() {
        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runs).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anInterruptedRunIsRetakenForTheSamePersonWhileTheCardIsStillTheAgents() {
        cardAssignedTo(AGENT);

        assertThat(recovery.recover(new AgentRunRecovery.Silent(running.id(), WORKSPACE), NOW)).isTrue();

        assertThat(saved().status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(saved().error()).isEqualTo(AgentRunRecovery.RETAKEN);
        ArgumentCaptor<IssueAssigned> event = ArgumentCaptor.forClass(IssueAssigned.class);
        verify(dispatcher).retry(event.capture());
        assertThat(event.getValue().workspaceId()).isEqualTo(WORKSPACE);
        assertThat(event.getValue().issueKey()).isEqualTo("PLA-7");
        assertThat(event.getValue().toAssigneeId()).isEqualTo(AGENT);
        assertThat(event.getValue().actorId()).as("responde a quien lo pidió").isEqualTo(PERSON);
        assertThat(event.getValue().eventId()).as("es trabajo nuevo, no el evento de antes").isNotEqualTo(running.triggerEventId());
    }

    @Test
    void aSecondInterruptionIsLeftForAPerson() {
        cardAssignedTo(AGENT);
        AgentRun before = AgentRun.start(AGENT, "PLA-7", "PLA", UUID.randomUUID(), PERSON, NOW.minusSeconds(1200))
                .failed(AgentRunRecovery.RETAKEN, NOW.minusSeconds(600));
        when(runs.findRecent(AGENT, "PLA-7", 6)).thenReturn(List.of(running, before));

        assertThat(recovery.recover(new AgentRunRecovery.Silent(running.id(), WORKSPACE), NOW)).isFalse();

        assertThat(saved().error()).isEqualTo(AgentRunRecovery.GIVEN_UP);
        verify(dispatcher, never()).retry(any());
    }

    @Test
    void aCardThatMovedOnIsNotRetaken() {
        cardAssignedTo(PERSON);

        assertThat(recovery.recover(new AgentRunRecovery.Silent(running.id(), WORKSPACE), NOW)).isFalse();

        assertThat(saved().error()).isEqualTo(AgentRunRecovery.GIVEN_UP);
        verify(dispatcher, never()).retry(any());
    }

    @Test
    void aChatOrDraftRunHasNoCardToRetake() {
        when(issues.context("PLA-7")).thenReturn(Optional.empty());

        assertThat(recovery.recover(new AgentRunRecovery.Silent(running.id(), WORKSPACE), NOW)).isFalse();

        assertThat(saved().status()).isEqualTo(AgentRun.Status.FAILED);
        verify(dispatcher, never()).retry(any());
    }

    @Test
    void aRunThatFinishedMeanwhileIsLeftAlone() {
        when(runs.findById(running.id())).thenReturn(Optional.of(running.succeeded("modelo", 1, 1, "listo", NOW)));

        assertThat(recovery.recover(new AgentRunRecovery.Silent(running.id(), WORKSPACE), NOW)).isFalse();

        verify(runs, never()).save(any());
        verify(dispatcher, never()).retry(any());
    }
}
