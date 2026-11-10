package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoProposal;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.member.MemberView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.NoTx;
import ec.kuador.board.testing.TestTenant;
import org.junit.jupiter.api.Test;

class ProductOwnerAgentTest {

    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();
    private static final UUID QA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-10T17:00:00Z");

    private final Map<UUID, AgentRun> runs = new LinkedHashMap<>();
    private final Map<String, IssueApi.IssueContext> issueStore = new HashMap<>();
    private final List<String> comments = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private final List<PoContext> seen = new ArrayList<>();
    private InMemoryProjects projects;
    private boolean available = true;
    private Function<PoContext, ProductOwnerModel.Result> modelBehaviour;
    private ProductOwnerAgent agent;

    @BeforeEach
    void setUp() {
        InMemoryMembers memberApi = new InMemoryMembers();
        memberApi.add(new MemberView(PERSON, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        memberApi.add(new MemberView(PO, TestTenant.WORKSPACE, "Agente PO", true, "PRODUCT_OWNER", "#fff", "Convierte ideas en historias."));
        memberApi.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        issueStore.put("KUA-1", issue("KUA-1", "Plataforma de citas", "EPIC", null, null, PERSON, List.of()));
        issueStore.put("KUA-8", issue("KUA-8", "Recordatorio por WhatsApp", "STORY", null, "KUA-1", PO,
                List.of(new IssueApi.CommentView(PERSON, "Debe salir 24 h antes", NOW))));
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SUBSTANTIAL, 4, List.of()), "claude-opus-5-5", 1200, 800);

        AgentRunRepository runRepo = new AgentRunRepository() {
            public boolean existsByTriggerEventId(UUID id) { return runs.values().stream().anyMatch(r -> r.triggerEventId().equals(id)); }
            public void save(AgentRun r) { runs.put(r.id(), r); }
            public List<AgentRun> findRecent(UUID a, String i, int l) { return List.copyOf(runs.values()); }
            public List<AgentRun> findStuck(Instant b) { return runs.values().stream().filter(r -> r.status() == AgentRun.Status.RUNNING).toList(); }
        };
        ProductOwnerModel model = new ProductOwnerModel() {
            public boolean available() { return available; }
            public String unavailableReason() { return "falta ANTHROPIC_API_KEY"; }
            public Result propose(PoContext c) { seen.add(c); return modelBehaviour.apply(c); }
        };
        IssueApi issues = new ec.kuador.board.testing.IssueApiStub() {
            public Optional<IssueRef> find(String k) { return Optional.empty(); }
            public IssueRef createForImport(String p, String t, UUID a) { throw new UnsupportedOperationException(); }
            public Optional<IssueContext> context(String k) { return Optional.ofNullable(issueStore.get(k)); }
            public void addComment(String k, UUID author, String body) { comments.add(author + "|" + body); }
            public void reassign(String k, UUID m, UUID actor) { issueStore.computeIfPresent(k, (key, i) -> withAssignee(i, m)); }
            public boolean setStoryPointsIfEmpty(String k, int points, UUID actor) {
                IssueContext i = issueStore.get(k);
                if (i.storyPoints() != null) return false;
                issueStore.put(k, new IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type(), i.status(), i.priority(),
                        points, i.labels(), i.parentKey(), i.assigneeId(), i.reporterId(), i.comments()));
                return true;
            }
        };
        DocsApi docs = new DocsApi() {
            public List<DocContent> ofProject(String p) {
                return List.of(new DocContent(UUID.randomUUID(), "KUA", null, "CONSTITUTION", "Constitución", ".specify/memory/constitution.md", 3,
                        "# Constitución\n\nI. Privacidad primero"));
            }
            public DocContent saveFromRepository(UUID id, int b, String c, String n, UUID a) { throw new UnsupportedOperationException(); }
            public DocContent createFromRepository(String p, String k, String i, String path, String c, UUID a) { throw new UnsupportedOperationException(); }
            public List<String> allowedKinds(String p) { return List.of(); }
            public Optional<MethodologyRules> methodologyRules(String p) { return Optional.empty(); }
            public DocContent write(String p, String issueKey, String kind, String content, String note, UUID actor) {
                writes.add(kind + "|" + issueKey + "|" + actor + "|" + note);
                return new DocContent(UUID.randomUUID(), p, issueKey, kind, kind, "specs/008-recordatorio/spec.md", 1, content);
            }
        };
        projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", "SDD");
        agent = new ProductOwnerAgent(runRepo, model, issues, docs, projects, memberApi, new InMemoryCatalog(), new NoTx(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IssueApi.IssueContext issue(String key, String title, String type, Integer points, String parent, UUID assignee,
            List<IssueApi.CommentView> comments) {
        return new IssueApi.IssueContext(key, "KUA", title, "Enviar recordatorios a pacientes", type, "BACKLOG", "HIGH", points,
                List.of("whatsapp"), parent, assignee, PERSON, comments);
    }

    private static IssueApi.IssueContext withAssignee(IssueApi.IssueContext i, UUID assignee) {
        return new IssueApi.IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type(), i.status(), i.priority(), i.storyPoints(),
                i.labels(), i.parentKey(), assignee, i.reporterId(), i.comments());
    }

    private static PoProposal proposal(PoProposal.Complexity complexity, int points, List<String> questions) {
        return new PoProposal("Recordar citas para reducir ausencias.", "Como paciente quiero un recordatorio para no faltar",
                List.of("Dado una cita mañana, cuando falten 24 h, entonces recibo un WhatsApp"), questions, List.of("SMS"), points,
                complexity, "# Spec: Recordatorio\n\n## Requisitos");
    }

    private IssueAssigned assignedBy(UUID actor, UUID to) {
        return new IssueAssigned(UUID.randomUUID(), TestTenant.WORKSPACE, "KUA-8", "KUA", actor, NOW, null, to);
    }

    private AgentRun onlyRun() {
        assertThat(runs).hasSize(1);
        return runs.values().iterator().next();
    }

    @Test
    void whenAPersonAssignsItTheAgentRefinesWritesTheSpecAndHandsItBack() {
        agent.onAssigned(assignedBy(PERSON, PO));

        PoContext ctx = seen.getFirst();
        assertThat(ctx.methodologyRules()).contains("No se implementa nada sin una spec aprobada por una persona.");
        assertThat(ctx.roleInstructions()).as("instrucciones del rol en el catálogo").contains("Product Owner");
        assertThat(ctx.projectDocs()).extracting(PoContext.Doc::kind).containsExactly("CONSTITUTION");
        assertThat(ctx.epic().title()).isEqualTo("Plataforma de citas");
        assertThat(ctx.comments()).extracting(PoContext.Comment::author).containsExactly("Diana");
        assertThat(ctx.docTask().kind()).as("el documento que SDD asigna al PO").isEqualTo("SPEC");
        assertThat(ctx.issue().status()).as("nombre de la columna, no la clave").isEqualTo("Backlog");

        assertThat(writes).containsExactly("SPEC|KUA-8|" + PO + "|Propuesta de Agente PO");
        assertThat(issueStore.get("KUA-8").storyPoints()).as("4 se redondea a 5 (Fibonacci)").isEqualTo(5);
        assertThat(comments).singleElement().satisfies(c -> {
            assertThat(c).startsWith(PO + "|**Propuesta de Agente PO**");
            assertThat(c).contains("1. Dado una cita mañana").contains("Escribí «Spec» (v1)").contains("lo muevas a la siguiente columna");
        });
        assertThat(issueStore.get("KUA-8").assigneeId()).as("devuelto a quien lo asignó").isEqualTo(PERSON);
        AgentRun run = onlyRun();
        assertThat(run.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(run.inputTokens()).isEqualTo(1200);
        assertThat(run.summary()).contains("comentó").contains("spec v1").contains("5 pts").contains("devuelto a Diana");
    }

    @Test
    void oddSimpleIssuesGetNoDocument() {
        projects.add("KUA", "SOFTWARE", "ODD");
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SIMPLE, 2, List.of()), "m", 1, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen.getFirst().docTask().kind()).isEqualTo("TRACKING");
        assertThat(writes).as("ODD: tarea simple sin documentos").isEmpty();
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test
    void neverOverwritesAHumanEstimateAndSaysSo() {
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "x", null, "STORY", "BACKLOG", "HIGH", 8, List.of(), null, PO, PERSON, List.of()));

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(issueStore.get("KUA-8").storyPoints()).isEqualTo(8);
        assertThat(comments.getFirst()).contains("ya tenía estimación; no la cambié");
    }

    @Test
    void openQuestionsAreHighlightedForThePerson() {
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SUBSTANTIAL, 3, List.of("¿Aplica fines de semana?")), "m", 1, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(comments.getFirst()).contains("**Preguntas para decidir**").contains("- ¿Aplica fines de semana?")
                .contains("necesito que respondas las preguntas");
    }

    @Test
    void skipsWhenAssignedByAnotherAgentOrForARoleWithoutAutomationOrWithoutKey() {
        agent.onAssigned(assignedBy(QA, PO));
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SKIPPED);
        assertThat(onlyRun().summary()).contains("bucles");
        runs.clear();

        agent.onAssigned(assignedBy(PERSON, QA));
        assertThat(onlyRun().summary()).contains("«QA / validación» no tiene automatización");
        runs.clear();

        available = false;
        agent.onAssigned(assignedBy(PERSON, PO));
        assertThat(onlyRun().summary()).contains("ANTHROPIC_API_KEY");

        assertThat(seen).as("en ningún caso se llamó al modelo").isEmpty();
        assertThat(comments).isEmpty();
    }

    @Test
    void assigningToAPersonDoesNothing() {
        agent.onAssigned(assignedBy(PERSON, PERSON));
        assertThat(runs).isEmpty();
    }

    @Test
    void aRedeliveredEventDoesNotRunTwice() {
        IssueAssigned event = assignedBy(PERSON, PO);

        agent.onAssigned(event);
        agent.onAssigned(event);

        assertThat(seen).hasSize(1);
        assertThat(comments).hasSize(1);
    }

    @Test
    void modelFailuresAreRecordedAndTouchNothing() {
        modelBehaviour = c -> { throw new AgentModelException("El modelo declinó la solicitud (categoría: cyber)."); };

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(onlyRun().error()).contains("declinó");
        assertThat(comments).isEmpty();
        assertThat(writes).isEmpty();
        assertThat(issueStore.get("KUA-8").assigneeId()).as("sigue asignado al agente para reintentar").isEqualTo(PO);
    }

    @Test
    void doesNotTakeTheIssueBackIfSomeoneReassignedItMeanwhile() {
        modelBehaviour = c -> {
            issueStore.put("KUA-8", withAssignee(issueStore.get("KUA-8"), QA)); // alguien lo reasignó mientras el modelo pensaba
            return new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SIMPLE, 1, List.of()), "m", 1, 1);
        };

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(issueStore.get("KUA-8").assigneeId()).isEqualTo(QA);
        assertThat(onlyRun().summary()).doesNotContain("devuelto");
    }

    @Test
    void rejectsEmptyProposals() {
        modelBehaviour = c -> new ProductOwnerModel.Result(new PoProposal("", "", List.of(), List.of(), List.of(), 1, null, ""), "m", 1, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(comments).isEmpty();
    }

}
