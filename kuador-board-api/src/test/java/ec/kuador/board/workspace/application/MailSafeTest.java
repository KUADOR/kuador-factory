package ec.kuador.board.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Los nombres que elige quien crea un espacio no convierten nuestros correos en phishing. */
class MailSafeTest {

    @Test
    void linksAreDefangedAndControlCharactersRemoved() {
        assertThat(InvitationService.mailSafe("Tu cuenta fue suspendida, paga en https://evil.example/pago"))
                .doesNotContain("https://").doesNotContain("evil.example").contains("evil[.]example");
        assertThat(InvitationService.mailSafe("Visita www.kuador-pagos.com ya")).doesNotContain("www.kuador-pagos.com");
        assertThat(InvitationService.mailSafe("Clínica\r\nBcc: todos@x.com")).doesNotContain("\r").doesNotContain("\n");
        assertThat(InvitationService.mailSafe("Clínica San Andrés")).isEqualTo("Clínica San Andrés");
    }
}
