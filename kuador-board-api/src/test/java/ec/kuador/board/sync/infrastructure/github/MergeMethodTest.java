package ec.kuador.board.sync.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** El tablero fusiona con el método que el repo permite: así la regla vive en GitHub y vale igual para todos. */
class MergeMethodTest {

    private static String method(String repo) {
        return GitHubCodeHost.mergeMethod(JsonMapper.builder().build().readTree(repo));
    }

    @Test
    void squashSiElRepoLoPermiteComoHastaAhora() {
        assertThat(method("{\"allow_squash_merge\":true,\"allow_merge_commit\":true}")).isEqualTo("squash");
        assertThat(method("{}")).as("sin datos (permiso limitado)").isEqualTo("squash");
    }

    @Test
    void mergeCommitSiElRepoDesactivoSquash() {
        assertThat(method("{\"allow_squash_merge\":false,\"allow_merge_commit\":true,\"allow_rebase_merge\":true}")).isEqualTo("merge");
        assertThat(method("{\"allow_squash_merge\":false,\"allow_merge_commit\":false,\"allow_rebase_merge\":true}")).isEqualTo("rebase");
    }
}
