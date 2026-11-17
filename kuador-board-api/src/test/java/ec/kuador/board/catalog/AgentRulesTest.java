package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.testing.InMemoryCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Las reglas de agentes: una sola fuente para todos los agentes, con el núcleo fijo delante y lo vacío desde fábrica. */
class AgentRulesTest {

    final AgentPolicy factory = InMemoryCatalog.FACTORY.agentPolicies().getFirst();

    @Test
    void fillsVariablesAndLeavesUnknownOnesVisible() {
        assertThat(AgentRules.fill("Te lo devuelvo, {persona}: «{columna}» {otra}", Map.of("persona", "Diana", "columna", "QA")))
                .isEqualTo("Te lo devuelvo, Diana: «QA» {otra}");
        assertThat(AgentRules.fill("for f in {archivos}; do test -f \"$f\" || { echo x; }", Map.of("archivos", "README.md")))
                .as("las llaves de bash no son variables").isEqualTo("for f in README.md; do test -f \"$f\" || { echo x; }");
    }

    @Test
    void everyAgentGetsTheCoreFirstThenTheSpaceRulesAndTheLanguage() {
        List<String> all = AgentRules.common(factory, "es", "en");
        assertThat(all.subList(0, AgentRules.CORE.size())).isEqualTo(AgentRules.CORE);
        assertThat(all).containsAll(factory.rules().common()).containsAll(factory.rules().questions());
        assertThat(all.getLast()).startsWith("Idioma: el código va en inglés");
    }

    @Test
    void anEmptyTextFallsBackToTheFactoryOneButAnEmptyListStaysEmpty() {
        AgentPolicy edited = new AgentPolicy(null, AgentPolicy.KEY, "Mías", null,
                new AgentPolicy.Rules(List.of(), factory.rules().board(), List.of("Pregunta una sola vez."), "", null, "", "", "", "", "", "", "", "", ""),
                new AgentPolicy.Limits(3, 0, 5, 0, 0, 0),
                new AgentPolicy.Messages("Revisa, {persona}.", "", "", "", "", "", "", "", "", "", ""),
                new AgentPolicy.RepoTemplates("", "", "", "", "", "", "", ""), false, 1);

        AgentPolicy p = edited.orElse(factory);

        assertThat(p.rules().common()).as("quitó las comunes a propósito").isEmpty();
        assertThat(p.rules().questions()).containsExactly("Pregunta una sola vez.");
        assertThat(p.rules().epicPlan()).isEqualTo(factory.rules().epicPlan());
        assertThat(p.limits().questionRounds()).isEqualTo(3);
        assertThat(p.limits().commentsRead()).isEqualTo(factory.limits().commentsRead());
        assertThat(p.messages().returnForReview()).isEqualTo("Revisa, {persona}.");
        assertThat(p.messages().toGate()).isEqualTo(factory.messages().toGate());
        assertThat(p.repo().agentsMd()).isEqualTo(factory.repo().agentsMd());
    }

    @Test
    void theSpaceCanTuneARoleOrPickTheSpaceDefaultOnPurpose() {
        assertThat(factory.tuning("PRODUCT_OWNER").model()).as("de fábrica").isEqualTo("claude-sonnet-5-5");
        AgentPolicy edited = new AgentPolicy(null, AgentPolicy.KEY, "Mías", null, factory.rules(), factory.limits(), factory.messages(), factory.repo(),
                java.util.Map.of("PRODUCT_OWNER", new AgentPolicy.Tuning("", "low"), "SUPPORT", new AgentPolicy.Tuning("claude-haiku-5-5", null)), false, 1);

        AgentPolicy p = edited.orElse(factory);

        assertThat(p.tuning("PRODUCT_OWNER")).as("vacío = el del espacio, no el de fábrica").isEqualTo(new AgentPolicy.Tuning(null, "low"));
        assertThat(p.roles().get("PRODUCT_OWNER").model()).as("se guarda vacío para que no vuelva el de fábrica").isEmpty();
        assertThat(p.tuning("SUPPORT").model()).isEqualTo("claude-haiku-5-5");
        assertThat(p.tuning("ARCHITECT")).isEqualTo(factory.tuning("ARCHITECT"));
        assertThat(p.tuning("NADIE")).isEqualTo(AgentPolicy.Tuning.NONE);
    }
}
