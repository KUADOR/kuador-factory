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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Alta SaaS de punta a punta: código por correo, verificación, espacio con su marca y login. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class SignupIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /** Buzón en memoria: guarda el último código enviado a cada correo. */
    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
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
        String token = signup.verifyCode("ana@andes.test", code);

        Workspace w = signup.complete(token, new SignupService.Company("Clínica Andes", null, "Salud en Quito", "#E11D48", null, LOGO), "Ana", "clave-de-ana-123", "FACTORY");

        assertThat(w.slug()).isEqualTo("clinica-andes");
        assertThat(w.brandColor()).isEqualTo("#e11d48");
        assertThat(w.logo()).isEqualTo(LOGO);
        String session = auth.login("ana@andes.test", "clave-de-ana-123").token();
        assertThat(auth.authenticate(session)).get().extracting(a -> a.workspaceId()).isEqualTo(w.id());
        // Listo para usar: catálogo de fábrica y un agente por rol, además de la persona dueña.
        assertThat(TenantContext.call(w.id(), () -> catalog.methodologies())).hasSize(3);
        assertThat(TenantContext.call(w.id(), () -> members.list())).hasSize(9);

        assertThatThrownBy(() -> signup.complete(token, new SignupService.Company("Otra", null, null, null, null, null), "Ana", "clave-de-ana-123", "FACTORY"))
                .as("el token de alta se usa una sola vez").hasMessageContaining("venció");
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
