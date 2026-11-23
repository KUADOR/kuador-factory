package ec.kuador.board.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.catalog.application.CatalogService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.application.WorkspaceService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Alta SaaS de punta a punta: código por correo, verificación, espacio con su marca y login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class SignupIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    /** Buzón en memoria: guarda el último código enviado a cada correo. */
    static final Map<String, String> INBOX = new ConcurrentHashMap<>();
    /** Correos que fallan una vez (SMTP caído). */
    static final java.util.Set<String> FAIL_ONCE = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return (email, code) -> {
                if (FAIL_ONCE.remove(email)) throw new ec.kuador.board.shared.mail.Mailer.MailUnavailableException();
                INBOX.put(email, code);
            };
        }
    }

    @Autowired SignupService signup;
    @Autowired WorkspaceService workspaces;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired CatalogService catalog;

    private static final String LOGO = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    @Test
    void aVerifiedEmailCreatesABrandedWorkspaceReadyToUse() {
        signup.requestCode("Ana@Andes.test");
        String code = INBOX.get("ana@andes.test");
        assertThat(code).matches("\\d{6}");

        assertThatThrownBy(() -> signup.verifyCode("ana@andes.test", code.equals("000000") ? "111111" : "000000"))
                .isInstanceOf(DomainException.class).hasMessageContaining("4 intento(s)");
        assertThatThrownBy(() -> signup.verifyCode("ana@andes.test", code.equals("000000") ? "111111" : "000000"))
                .as("cada intento fallido queda guardado").isInstanceOf(DomainException.class).hasMessageContaining("3 intento(s)");
        String token = signup.verifyCode("ana@andes.test", code);

        Workspace w = signup.complete(token, new SignupService.Company("Clínica Andes", null, "Salud en Quito", "#E11D48", null, LOGO), "Ana", "clave-de-ana-123", "FACTORY");

        assertThat(w.slug()).isEqualTo("clinica-andes");
        assertThat(w.brandColor()).isEqualTo("#e11d48");
        assertThat(w.logo()).isEqualTo(LOGO);
        String session = auth.login("ana@andes.test", "clave-de-ana-123").token();
        assertThat(auth.authenticate(session)).get().extracting(a -> a.workspaceId()).isEqualTo(w.id());
        // Listo para usar: catálogo de fábrica y un agente por rol, además de la persona dueña.
        assertThat(TenantContext.call(w.id(), () -> catalog.methodologies())).hasSize(4);
        assertThat(TenantContext.call(w.id(), () -> members.list())).hasSize(13);

        assertThatThrownBy(() -> signup.complete(token, new SignupService.Company("Otra", null, null, null, null, null), "Ana", "clave-de-ana-123", "FACTORY"))
                .as("el token de alta se usa una sola vez").hasMessageContaining("venció");

        // Con cuenta: al verificar el código (ya probó que el correo es suyo) se le dice que entre o recupere su contraseña.
        workspaces.provision(new WorkspaceService.Brand("existe", "Existe", null, null, null, null),
                new WorkspaceService.Owner("Eli", "eli@existe.test", "clave-de-eli-123"));
        signup.requestCode("eli@existe.test");
        assertThatThrownBy(() -> signup.verifyCode("eli@existe.test", INBOX.get("eli@existe.test")))
                .isInstanceOf(SignupService.AccountExistsException.class).hasMessageContaining("ya tiene una cuenta");
    }

    @Test
    void aFailedEmailDoesNotBlockRetrying() {
        FAIL_ONCE.add("fio@falla.test");
        assertThatThrownBy(() -> signup.requestCode("fio@falla.test")).isInstanceOf(ec.kuador.board.shared.mail.Mailer.MailUnavailableException.class);
        signup.requestCode("fio@falla.test");
        assertThat(INBOX.get("fio@falla.test")).as("el reintento inmediato sí envía el código").matches("\\d{6}");
    }

    @Test
    void abuseLimitsAndExistingAccounts() {
        signup.requestCode("beto@beta.test");
        assertThatThrownBy(() -> signup.requestCode("beto@beta.test")).isInstanceOf(SignupService.TooManyRequestsException.class)
                .hasMessageContaining("Espera un minuto");
        assertThatThrownBy(() -> signup.requestCode("no-es-correo")).isInstanceOf(DomainException.class);

        String token = signup.verifyCode("beto@beta.test", INBOX.get("beto@beta.test"));
        signup.complete(token, new SignupService.Company("Beta", "beta", null, null, null, null), "Beto", "clave-de-beto-123", "FACTORY");

        assertThat(signup.suggestSlug("Beta")).isEqualTo("beta-2");
        signup.requestCode("carla@gamma.test");
        String other = signup.verifyCode("carla@gamma.test", INBOX.get("carla@gamma.test"));
        assertThatThrownBy(() -> signup.complete(other, new SignupService.Company("Beta", "beta", null, null, null, null), "Carla", "clave-de-carla-123", "FACTORY"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("identificador beta");
    }
}
