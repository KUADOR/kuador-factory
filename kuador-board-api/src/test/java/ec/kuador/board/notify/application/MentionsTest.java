package ec.kuador.board.notify.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.member.MemberView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MentionsTest {

    private final MemberView diego = person("Diego Acosta");
    private final MemberView ana = person("Ana Pérez");
    private final MemberView anaMaria = person("Ana María Ruiz");
    private final MemberView noe = person("Noé");
    private final List<MemberView> people = List.of(diego, ana, anaMaria, noe);

    @Test
    void byFirstNameWhenUniqueOrByFullNameWithoutSpacesOrAccents() {
        assertThat(Mentions.of("@diego revisa esto", people)).containsExactly(diego.id());
        assertThat(Mentions.of("Listo, @DiegoAcosta.", people)).containsExactly(diego.id());
        assertThat(Mentions.of("@noe y @anaperez", people)).containsExactly(noe.id(), ana.id());
        assertThat(Mentions.of("@ana", people)).as("dos personas se llaman Ana").isEmpty();
        assertThat(Mentions.of("@anamariaruiz", people)).containsExactly(anaMaria.id());
    }

    @Test
    void ignoresEmailsCodeAndUnknownNames() {
        assertThat(Mentions.of("escribe a diego@empresa.com", people)).isEmpty();
        assertThat(Mentions.of("usa `@diego` o\n```\n@diego\n```", people)).isEmpty();
        assertThat(Mentions.of("@Transactional en el servicio", people)).isEmpty();
        assertThat(Mentions.of(null, people)).isEmpty();
    }

    private static MemberView person(String name) {
        return new MemberView(UUID.randomUUID(), UUID.randomUUID(), name, false, "DEV", "#ccc", null);
    }
}
