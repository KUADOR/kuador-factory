package ec.kuador.board.sync.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomIvAndDetectsTampering() {
        SecretBox box = new SecretBox(KEY);
        String a = box.encrypt("github_pat_123");
        String b = box.encrypt("github_pat_123");

        assertThat(a).isNotEqualTo(b).doesNotContain("github_pat");
        assertThat(box.decrypt(a)).isEqualTo("github_pat_123");

        byte[] raw = Base64.getDecoder().decode(a);
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> box.decrypt(Base64.getEncoder().encodeToString(raw))).hasMessageContaining("No se pudo leer el token");
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(other)).decrypt(a)).hasMessageContaining("BOARD_SECRETS_KEY");
    }

    @Test
    void rejectsKeysOfTheWrongSize() {
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 bytes");
    }
}
