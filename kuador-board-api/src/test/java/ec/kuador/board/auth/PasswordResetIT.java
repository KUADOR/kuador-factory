package ec.kuador.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.auth.application.PasswordResetService;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.mail.Mailer;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Recuperar la contraseña con el enlace o con el código del correo, sin revelar qué correos existen. */
@Testcontainers
@SpringBootTest(properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.web-url=https://tablero.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class PasswordResetIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final Map<String, String> CODES = new ConcurrentHashMap<>();
    static final Map<String, String> MAIL = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer codes() {
            return CODES::put;
        }

        @Bean
        @Primary
        Mailer mail() {
            return (to, subject, text) -> MAIL.put(to, text);
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired PasswordResetService resets;

    private void account(String email) {
        signup.requestCode(email);
        signup.complete(signup.verifyCode(email, CODES.get(email)), new SignupService.Company("Esp " + email.charAt(0), null, null, null, null, null),
                "Persona", "clave-original-123", null);
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }

    @Test
    void theLinkResetsThePasswordOnceAndClosesSessions() {
        account("rita@reset.test");
        String oldSession = auth.login("rita@reset.test", "clave-original-123").token();

        resets.request("Rita@Reset.test");
        String token = find(MAIL.get("rita@reset.test"), "https://tablero\\.test/recuperar\\?token=([A-Za-z0-9_-]+)");
        resets.reset(token, null, null, "clave-nueva-de-rita");

        assertThat(auth.authenticate(oldSession)).as("las sesiones abiertas se cierran").isEmpty();
        assertThat(auth.login("rita@reset.test", "clave-nueva-de-rita").token()).isNotBlank();
        assertThatThrownBy(() -> resets.reset(token, null, null, "otra-clave-larga")).hasMessageContaining("ya se usó");
    }

    @Test
    void theCodeWorksWithLimitedAttemptsAndUnknownEmailsLookTheSame() {
        account("tom@reset.test");
        resets.request("tom@reset.test");
        String code = find(MAIL.get("tom@reset.test"), "código en la página de recuperación: (\\d{6})");
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> resets.reset(null, "tom@reset.test", wrong, "clave-nueva-de-tom")).hasMessageContaining("4 intento(s)");
        assertThatThrownBy(() -> resets.reset(null, "tom@reset.test", wrong, "clave-nueva-de-tom")).hasMessageContaining("3 intento(s)");
        resets.reset(null, "tom@reset.test", code, "clave-nueva-de-tom");
        assertThat(auth.login("tom@reset.test", "clave-nueva-de-tom").token()).isNotBlank();

        // Un correo sin cuenta responde igual y no recibe nada.
        resets.request("nadie@reset.test");
        assertThat(MAIL).doesNotContainKey("nadie@reset.test");
        assertThatThrownBy(() -> resets.reset(null, "nadie@reset.test", "123456", "clave-nueva-larga")).isInstanceOf(DomainException.class);
    }
}
