package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.IssueApiStub;
import ec.kuador.board.testing.NoTx;
import ec.kuador.board.testing.TestTenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentChainTest {

    static final UUID PERSON = UUID.randomUUID();
    static final UUID ARCHITECT = UUID.randomUUID();
    static final UUID BACKEND = UUID.randomUUID();
    static final UUID QA_LOCAL = UUID.randomUUID();
    static final UUID FRONTEND_SERVER = UUID.randomUUID();

    final List<String> assigned = new ArrayList<>();

    private AgentChain chain(List<IssueApi.QueueItem> open) {
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(PERSON, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        members.add(new MemberView(ARCHITECT, TestTenant.WORKSPACE, "Arquitecto", true, "ARCHITECT", "#fff", null));
        members.add(new MemberView(BACKEND, TestTenant.WORKSPACE, "Backend", true, "BACKEND", "#fff", null));
        members.add(new MemberView(QA_LOCAL, TestTenant.WORKSPACE, "QA", true, "QA", "#fff", null));
        members.add(new MemberView(FRONTEND_SERVER, TestTenant.WORKSPACE, "Frontend", true, "FRONTEND", "#fff", null));
        IssueApi issues = new IssueApiStub() {
            @Override public List<QueueItem> openForAgents(String projectKey) { return open; }
            @Override public void reassign(String k, UUID m, UUID actor) { assigned.add(k + "→" + m + " por " + actor); }
        };
        AgentTeamStore team = new AgentTeamStore() {
            public Map<UUID, Profile> profiles(UUID ws) {
                return Map.of(ARCHITECT, new Profile(ARCHITECT, AgentMode.SERVER, null), BACKEND, new Profile(BACKEND, AgentMode.SERVER, null),
                        QA_LOCAL, new Profile(QA_LOCAL, AgentMode.LOCAL, null), FRONTEND_SERVER, new Profile(FRONTEND_SERVER, AgentMode.SERVER, null));
            }
            public Optional<Profile> profile(UUID id) { return Optional.ofNullable(profiles(null).get(id)); }
            public void saveProfile(UUID ws, Profile p, Instant now) {}
            public List<UUID> team(UUID ws, String projectKey) { return List.of(ARCHITECT, QA_LOCAL, FRONTEND_SERVER); } // Backend no está en este tablero
            public List<String> boardsOf(UUID ws, UUID id) { return List.of(); }
            public void addToTeam(UUID ws, String p, UUID id) {}
            public void removeFromTeam(UUID ws, String p, UUID id) {}
            public Optional<AiKey> aiKey(UUID ws) { return Optional.empty(); }
            public void saveAiKey(UUID ws, AiKey k, Instant now) {}
            public void deleteAiKey(UUID ws) {}
        };
        return new AgentChain(issues, members, team, new InMemoryCatalog(), new NoTx());
    }

    private static IssueApi.QueueItem open(String key, String... roles) {
        return new IssueApi.QueueItem(key, "MED", "t", "STORY", "HIGH", "DESIGN", "Diseño", false, List.of(roles), null, null, 0);
    }

    @Test
    void assignsOpenWorkToTheServerAgentOfTheBoardThatCoversTheStage() {
        chain(List.of(open("MED-1", "ARCHITECT"), open("MED-2", "QA"), open("MED-3", "BACKEND"), open("MED-4", "FRONTEND")))
                .assignOpenWork(TestTenant.WORKSPACE, "MED", PERSON);

        assertThat(assigned).as("QA trabaja local, Backend no está en el tablero y Frontend, guardado «en el tablero», trabaja sobre el código")
                .containsExactly("MED-1→" + ARCHITECT + " por " + PERSON);
    }

    @Test
    void nothingOpenNothingAssigned() {
        chain(List.of()).assignOpenWork(TestTenant.WORKSPACE, "MED", PERSON);
        assertThat(assigned).isEmpty();
    }
}
