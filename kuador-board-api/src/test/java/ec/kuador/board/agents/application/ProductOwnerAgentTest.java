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
import ec.kuador.board.agents.domain.PoPrompt;
import ec.kuador.board.catalog.AgentPolicy;
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
    private final List<IssueApi.PlannedTask> planned = new ArrayList<>();
    private final List<PoContext> seen = new ArrayList<>();
    private final List<String> keysUsed = new ArrayList<>();
    private String workspaceKey;
    private InMemoryProjects projects;
    private Function<PoContext, ProductOwnerModel.Result> modelBehaviour;
    private ProductOwnerAgent agent;
    private InMemoryCatalog catalog;

    @BeforeEach
    void setUp() {
        InMemoryMembers memberApi = new InMemoryMembers();
        memberApi.add(new MemberView(PERSON, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        memberApi.add(new MemberView(PO, TestTenant.WORKSPACE, "Agente PO", true, "PRODUCT_OWNER", "#fff", "Convierte ideas en historias."));
        memberApi.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        issueStore.put("KUA-1", issue("KUA-1", "Plataforma de citas", "EPIC", null, null, PERSON, List.of()));
        issueStore.put("KUA-8", issue("KUA-8", "Recordatorio por WhatsApp", "STORY", null, "KUA-1", PO,
                List.of(new IssueApi.CommentView(PERSON, "Debe salir 24 h antes", NOW))));
        workspaceKey = "sk-ant-del-espacio"; // siempre la clave del espacio: no hay clave del servidor
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SUBSTANTIAL, 4, List.of()), "claude-opus-5-5", 1200, 800);

        AgentRunRepository runRepo = new AgentRunRepository() {
            public boolean existsByTriggerEventId(UUID id) { return runs.values().stream().anyMatch(r -> r.triggerEventId().equals(id)); }
            public void save(AgentRun r) { runs.put(r.id(), r); }
            public java.util.Optional<AgentRun> findById(UUID id) { return java.util.Optional.ofNullable(runs.get(id)); }
            public List<AgentRun> findRecent(UUID a, String i, int l) { return List.copyOf(runs.values()); }
            public void deleteByProject(UUID workspaceId, String projectKey) { runs.values().removeIf(r -> r.projectKey().equals(projectKey)); }
        };
        ProductOwnerModel model = new ProductOwnerModel() {
            public Result propose(String key, PoContext c) { keysUsed.add(key); seen.add(c); return modelBehaviour.apply(c); }
        };
        IssueApi issues = new ec.kuador.board.testing.IssueApiStub() {
            public Optional<IssueRef> find(String k) { return Optional.empty(); }
            public IssueRef createForImport(String p, String t, UUID a) { throw new UnsupportedOperationException(); }
            public Optional<IssueContext> context(String k) { return Optional.ofNullable(issueStore.get(k)); }
            public void addComment(String k, UUID author, String body) { comments.add(author + "|" + body); }
            public List<IssueRef> createEpicTasks(String epic, List<PlannedTask> tasks, UUID actor) {
                // Como el servicio real: una tarea para un rol que no escribe código se rechaza.
                if (tasks.stream().anyMatch(t -> "ARCHITECT".equals(t.role()))) {
                    throw new ec.kuador.board.shared.error.DomainException("La tarea 1 es para ARCHITECT, que no escribe en el repo.");
                }
                planned.addAll(tasks);
                List<IssueRef> refs = new ArrayList<>();
                for (int i = 0; i < tasks.size(); i++) refs.add(new IssueRef("KUA-" + (20 + i), "KUA", 20 + i, tasks.get(i).title()));
                return refs;
            }
            public void reassign(String k, UUID m, UUID actor) { issueStore.computeIfPresent(k, (key, i) -> withAssignee(i, m)); }
            public Progress deliverAs(String k, String comment, UUID agentId) {
                comments.add(agentId + "|" + comment);
                IssueContext i = issueStore.get(k);
                String next = projects.find(i.projectKey()).flatMap(p -> p.nextAfter(i.status())).orElseThrow().key();
                issueStore.put(k, new IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type(), next, i.priority(),
                        i.storyPoints(), i.labels(), i.parentKey(), null, i.reporterId(), i.comments()));
                return new Progress(k, next, next, null);
            }
            public boolean setStoryPointsIfEmpty(String k, int points, UUID actor) {
                IssueContext i = issueStore.get(k);
                if (i.storyPoints() != null) return false;
                issueStore.put(k, new IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type(), i.status(), i.priority(),
                        points, i.labels(), i.parentKey(), i.assigneeId(), i.reporterId(), i.comments()));
                return true;
            }
        };
        DocsApi docs = new ec.kuador.board.testing.DocsApiStub() {
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
        catalog = new InMemoryCatalog();
        agent = new ProductOwnerAgent(runRepo, model, issues, docs, projects, memberApi, catalog, new NoTx(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> Optional.ofNullable(workspaceKey), () -> new ec.kuador.board.workspace.WorkspaceApi.Identity("kuador", "Kuador", "es", "en"),
                projectKey -> List.of(new ec.kuador.board.sync.RepositoriesApi.Piece("api", "BACKEND", "Java 21 · Spring Boot 3", "API_SPRING_HEXAGONAL",
                        "acme/kua", "api", "./mvnw package", "./mvnw verify", null, "Bajamos de Java 25 a 21: no usar APIs de Java 22+.")));
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
        assertThat(ctx.projectDocs()).extracting(PoContext.Doc::kind).containsExactly("CONSTITUTION", "PIEZAS");
        assertThat(ctx.projectDocs().getLast().content()).as("el stack que ajustó el equipo y sus notas")
                .contains("Java 21 · Spring Boot 3").contains("no usar APIs de Java 22+");
        assertThat(ctx.epic().title()).isEqualTo("Plataforma de citas");
        assertThat(ctx.comments()).extracting(PoContext.Comment::author).containsExactly("Diana");
        assertThat(ctx.docTask().kind()).as("el documento que SDD asigna al PO").isEqualTo("SPEC");
        assertThat(ctx.issue().status()).as("nombre de la columna, no la clave").isEqualTo("Backlog");

        assertThat(writes).containsExactly("SPEC|KUA-8|" + PO + "|Propuesta de Agente PO");
        assertThat(issueStore.get("KUA-8").storyPoints()).as("4 se redondea a 5 (Fibonacci)").isEqualTo(5);
        assertThat(comments).singleElement().satisfies(c -> {
            assertThat(c).startsWith(PO + "|**Agente PO:** ");
            assertThat(c).as("enlaza al documento en vez de copiarlo").contains("Lee el documento completo:** [«Spec» v1](/projects/KUA/docs/")
                    .doesNotContain("Dado una cita mañana").contains("muévelo a la siguiente columna");
        });
        assertThat(issueStore.get("KUA-8").assigneeId()).as("devuelto a quien lo asignó").isEqualTo(PERSON);
        AgentRun run = onlyRun();
        assertThat(run.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(run.inputTokens()).isEqualTo(1200);
        assertThat(run.summary()).contains("comentó").contains("spec v1").contains("5 pts").contains("devuelto a Diana");
    }

    @Test
    void theArchitectPlanOfAnEpicAlsoCreatesItsTasks() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        // Una épica en Diseño: después viene Desarrollo (trabajo), así que el plan se reparte en tareas.
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "CRUD de pacientes", "Registro y búsqueda", "EPIC", "DESIGN", "HIGH", 8,
                List.of(), null, PO, PERSON, List.of()));
        modelBehaviour = c -> new ProductOwnerModel.Result(new PoProposal("Plan del CRUD.", "Como personal quiero registrar pacientes",
                List.of("Dado…, cuando…, entonces…"), List.of(), List.of(), 8, PoProposal.Complexity.SUBSTANTIAL, PLAN_WITH_DIAGRAM,
                List.of(new PoProposal.PlannedTask("API de pacientes", "BACKEND", "Endpoints y reglas", List.of()),
                        new PoProposal.PlannedTask("Pantallas de pacientes", "FRONTEND", "Listado y ficha", List.of(1)))), "m", 1, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen.getFirst().splitsIntoTasks()).isTrue();
        assertThat(seen.getFirst().taskRoles()).anyMatch(r -> r.startsWith("BACKEND"));
        assertThat(planned).extracting(IssueApi.PlannedTask::role).containsExactly("BACKEND", "FRONTEND");
        assertThat(planned.get(1).dependsOn()).containsExactly(1);
        assertThat(comments.getLast()).contains("Repartí la épica en 2 tarea(s)").contains("KUA-20 API de pacientes (BACKEND)");
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test
    void aSplitWithATaskForTheArchitectIsRedoneOnceWithTheReason() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "Esqueleto", null, "EPIC", "DESIGN", "HIGH", 8, List.of(), null, PO, PERSON,
                List.of()));
        modelBehaviour = c -> {
            boolean corrected = c.comments().stream().anyMatch(x -> x.body().contains("no se pudo crear"));
            String role = corrected ? "BACKEND" : "ARCHITECT";
            return new ProductOwnerModel.Result(new PoProposal("Plan.", "Como equipo quiero el esqueleto", List.of("Dado…"), List.of(), List.of(), 8,
                    PoProposal.Complexity.SUBSTANTIAL, PLAN_WITH_DIAGRAM, List.of(new PoProposal.PlannedTask("Contrato OpenAPI y ADR", role, "Contrato primero", List.of()))),
                    "m", 100, 10);
        };

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen.getFirst().taskRoles()).as("al modelo solo se le ofrecen roles que escriben código")
                .anyMatch(r -> r.startsWith("BACKEND")).noneMatch(r -> r.startsWith("ARCHITECT") || r.startsWith("PRODUCT_OWNER"));
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).comments().getLast().body()).contains("ARCHITECT", "Corrígelo");
        assertThat(planned).extracting(IssueApi.PlannedTask::role).containsExactly("BACKEND");
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(onlyRun().inputTokens()).as("cuenta las dos llamadas").isEqualTo(200);
    }

    static final String PLAN_WITH_DIAGRAM = "# Plan\n\n## Diagrama de la solución\n\n```mermaid\nflowchart LR\n  app --> api\n```\n";

    @Test
    void anEpicPlanWithoutTheDiagramIsRedoneOnceWithTheReason() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "Esqueleto", null, "EPIC", "DESIGN", "HIGH", 8, List.of(), null, PO, PERSON,
                List.of()));
        modelBehaviour = c -> {
            boolean corrected = c.comments().stream().anyMatch(x -> x.body().contains("Corrígelo"));
            return new ProductOwnerModel.Result(new PoProposal("Plan.", "Como equipo quiero el esqueleto", List.of("Dado…"), List.of(), List.of(), 8,
                    PoProposal.Complexity.SUBSTANTIAL, corrected ? PLAN_WITH_DIAGRAM : "# Plan sin diagrama",
                    List.of(new PoProposal.PlannedTask("API", "BACKEND", "Endpoints", List.of()))), "m", 100, 10);
        };

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen.getFirst().comments()).as("se le avisa antes de proponer").anyMatch(x -> x.body().contains("```mermaid"));
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).comments().getLast().body()).contains("diagrama de la solución", "Corrígelo");
        assertThat(writes).as("solo se escribe el plan corregido").hasSize(1);
        assertThat(planned).extracting(IssueApi.PlannedTask::role).containsExactly("BACKEND");
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test
    void anEpicPlanThatTwiceComesWithoutTheDiagramGoesBackToThePersonWithTheReason() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "Esqueleto", null, "EPIC", "DESIGN", "HIGH", 8, List.of(), null, PO, PERSON,
                List.of()));
        modelBehaviour = c -> new ProductOwnerModel.Result(new PoProposal("Plan.", "Como equipo quiero el esqueleto", List.of("Dado…"), List.of(), List.of(), 8,
                PoProposal.Complexity.SUBSTANTIAL, "# Plan sin diagrama", List.of(new PoProposal.PlannedTask("API", "BACKEND", "Endpoints", List.of()))),
                "m", 100, 10);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen).hasSize(2);
        assertThat(writes).as("nada a medias").isEmpty();
        assertThat(planned).isEmpty();
        assertThat(comments.getLast()).contains("no pude cerrar mi parte").contains("diagrama de la solución");
        assertThat(issueStore.get("KUA-8").assigneeId()).as("vuelve a la persona").isEqualTo(PERSON);
        assertThat(issueStore.get("KUA-8").status()).as("sigue en Diseño").isEqualTo("DESIGN");
    }

    @Test
    void anEpicPlanWithoutTasksFailsSoItCanBeRetried() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "CRUD de pacientes", null, "EPIC", "DESIGN", "HIGH", 8,
                List.of(), null, PO, PERSON, List.of()));

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(onlyRun().error()).contains("sin tareas");
        assertThat(planned).isEmpty();
    }

    @Test
    void aStoryOrAnEpicInRefinementIsNotSplit() {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "CRUD de pacientes", null, "EPIC", "REFINEMENT", "HIGH", null,
                List.of(), null, PO, PERSON, List.of()));

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(seen.getFirst().splitsIntoTasks()).as("de Refinamiento sigue Aprobación, no trabajo").isFalse();
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        // Sin preguntas y en su etapa: pasa sola a la siguiente (una puerta) y queda con la persona para aprobar.
        assertThat(issueStore.get("KUA-8").status()).isNotEqualTo("REFINEMENT");
        assertThat(issueStore.get("KUA-8").assigneeId()).isEqualTo(PERSON);
        assertThat(comments.getLast()).contains("➡️ Lo pasé a «").contains("espera tu aprobación");
        assertThat(onlyRun().summary()).contains("pasó a");
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

    /** Una historia en Refinamiento con {@code rounds} vueltas de preguntas del agente, cada una respondida por la persona. */
    private void refiningAfter(int rounds) {
        projects.add("KUA", "SOFTWARE_FACTORY", "SDD");
        List<IssueApi.CommentView> thread = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            AgentRun asked = AgentRun.start(PO, "KUA-8", "KUA", UUID.randomUUID(), PERSON, NOW).succeeded("m", 1, 1, "comentó · 1 pregunta(s) abiertas", NOW);
            runs.put(asked.id(), asked);
            thread.add(new IssueApi.CommentView(PO, "**Agente PO:** Resumen\n\n**Necesito que respondas**\n1. ¿Se aprueba la excepción P-1?", NOW));
            thread.add(new IssueApi.CommentView(PERSON, "Sí; queda pendiente la aprobación formal del responsable técnico.", NOW));
        }
        issueStore.put("KUA-8", new IssueApi.IssueContext("KUA-8", "KUA", "Contrato OpenAPI", "Usuarios y roles", "STORY", "REFINEMENT", "HIGH", 5,
                List.of(), null, PO, PERSON, thread));
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SUBSTANTIAL, 5,
                List.of("¿Se aprueba formalmente la excepción P-1?")), "m", 1, 1);
    }

    @Test
    void itDoesNotAskTheSameThingForeverAfterTwoAnsweredRoundsItMovesOn() {
        refiningAfter(2);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(PoPrompt.user(seen.getFirst())).as("se le dice que lo respondido está decidido").contains("no lo vuelvas a preguntar")
                .as("y recibe el núcleo fijo y el idioma").contains("Las puertas de aprobación las cierra una persona").contains("Idioma:");
        assertThat(comments.getLast()).doesNotContain("**Necesito que respondas**").contains("Pendientes (no frenan el avance; ya pregunté 2 veces)")
                .contains("¿Se aprueba formalmente la excepción P-1?");
        assertThat(issueStore.get("KUA-8").status()).as("pasó a la siguiente etapa").isEqualTo("APPROVAL");
        assertThat(runs.values()).anyMatch(r -> r.summary() != null && r.summary().contains("avanzó con 1 pendiente(s)"));
    }

    @Test
    void theTextsComeFromTheSpaceRules() {
        AgentPolicy f = catalog.policy;
        catalog.policy = new AgentPolicy(f.id(), f.key(), f.name(), f.description(), f.rules(), f.limits(),
                new AgentPolicy.Messages("Listo para revisión, {persona}.", f.messages().returnWithQuestions(), f.messages().toGate(), f.messages().toAgent(),
                        f.messages().toPerson(), "Dudas", f.messages().pendingTitle(), f.messages().readDocument(), f.messages().askFooter(),
                        f.messages().failedStart(), f.messages().failedFinish()), f.repo(), true, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(comments.getLast()).contains("Listo para revisión, Diana.").doesNotContain("muévelo a la siguiente columna");
    }

    @Test
    void afterOneRoundItCanStillAsk() {
        refiningAfter(1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(comments.getLast()).contains("**Necesito que respondas**");
        assertThat(issueStore.get("KUA-8").status()).isEqualTo("REFINEMENT");
    }

    @Test
    void openQuestionsAreHighlightedForThePerson() {
        modelBehaviour = c -> new ProductOwnerModel.Result(proposal(PoProposal.Complexity.SUBSTANTIAL, 3, List.of("¿Aplica fines de semana?")), "m", 1, 1);

        agent.onAssigned(assignedBy(PERSON, PO));

        assertThat(comments.getFirst()).contains("**Necesito que respondas**").contains("1. ¿Aplica fines de semana?")
                .contains("responde las preguntas");
    }

    @Test
    void skipsWhenAssignedByAnotherAgentOrForARoleWithoutAutomationOrWithoutKey() {
        agent.onAssigned(assignedBy(QA, PO));
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SKIPPED);
        assertThat(onlyRun().summary()).as("lo encadenó otro agente y nadie supervisa el issue").contains("Nadie supervisa");
        runs.clear();

        agent.onAssigned(assignedBy(PERSON, QA));
        assertThat(onlyRun().summary()).contains("«QA / validación» no tiene automatización");
        runs.clear();

        workspaceKey = null; // el espacio todavía no puso su clave
        agent.onAssigned(assignedBy(PERSON, PO));
        assertThat(onlyRun().summary()).contains("Conexiones → IA");

        assertThat(seen).as("en ningún caso se llamó al modelo").isEmpty();
        assertThat(comments).isEmpty();
    }

    @Test
    void alwaysUsesTheCompanyKey() {
        workspaceKey = "sk-ant-de-la-empresa";
        agent.onAssigned(assignedBy(PERSON, PO));
        assertThat(onlyRun().status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(keysUsed).containsExactly("sk-ant-de-la-empresa");
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
