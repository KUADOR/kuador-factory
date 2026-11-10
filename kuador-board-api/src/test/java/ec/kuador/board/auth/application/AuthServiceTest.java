package ec.kuador.board.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.domain.AccessToken;
import ec.kuador.board.auth.domain.AccessTokenRepository;
import ec.kuador.board.auth.domain.Credential;
import ec.kuador.board.auth.domain.CredentialRepository;
import ec.kuador.board.auth.domain.PasswordHasher;
import ec.kuador.board.auth.domain.TokenKind;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.NoTx;
import ec.kuador.board.testing.TestTenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestTenant.class)
class AuthServiceTest {

    private static final UUID HUMAN = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final String PASSWORD = "una-clave-segura";

    private MutableClock clock;
    private FakeTokens tokens;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        tokens = new FakeTokens();
        InMemoryMembers memberApi = new InMemoryMembers();
        memberApi.add(new MemberView(HUMAN, TestTenant.WORKSPACE, "Equipo Kuador", false, "OWNER", "#ffb81c", null));
        memberApi.add(new MemberView(AGENT, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#ff6b78", null));
        PasswordHasher hasher = new PasswordHasher() {
            public String hash(String raw) { return "h:" + raw; }
            public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
        };
        auth = new AuthService(new FakeCredentials(), tokens, hasher, memberApi, clock, new NoTx());
        auth.grantAccess(HUMAN, "Admin@Kuador.local", PASSWORD);
    }

    @Test
    void loginIssuesSessionThatAuthenticates() {
        AuthService.Session session = auth.login("admin@kuador.local", PASSWORD);

        assertThat(session.token()).startsWith("kfs_");
        assertThat(session.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(auth.authenticate(session.token())).map(AuthenticatedMember::memberId).contains(HUMAN);
        assertThat(tokens.store.values()).noneMatch(t -> t.tokenHash().equals(session.token()));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        assertThatThrownBy(() -> auth.login("admin@kuador.local", "otra-clave-xx"))
                .isInstanceOf(AuthService.BadCredentialsException.class);
        assertThatThrownBy(() -> auth.login("nadie@kuador.local", PASSWORD))
                .isInstanceOf(AuthService.BadCredentialsException.class)
                .hasMessage("Correo o contraseña incorrectos.");
    }

    @Test
    void blocksAfterFiveFailuresEvenWithTheRightPasswordAndUnblocksLater() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("admin@kuador.local", "mala-clave-xx")).isInstanceOf(AuthService.BadCredentialsException.class);
        }
        assertThatThrownBy(() -> auth.login("admin@kuador.local", PASSWORD)).isInstanceOf(AuthService.LoginBlockedException.class);

        clock.advance(Duration.ofMinutes(16));
        assertThat(auth.login("admin@kuador.local", PASSWORD).token()).isNotBlank();
    }

    @Test
    void expiredAndRevokedSessionsDoNotAuthenticate() {
        String expiring = auth.login("admin@kuador.local", PASSWORD).token();
        String revoked = auth.login("admin@kuador.local", PASSWORD).token();
        auth.logout(auth.authenticate(revoked).orElseThrow());

        assertThat(auth.authenticate(revoked)).isEmpty();
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertThat(auth.authenticate(expiring)).isEmpty();
        assertThat(auth.authenticate("kfs_inventado")).isEmpty();
    }

    @Test
    void agentTokensAuthenticateAsAgentUntilRevoked() {
        AuthService.IssuedToken issued = auth.createAgentToken(AGENT, "runner local");

        assertThat(issued.token()).startsWith("kfa_");
        AuthenticatedMember who = auth.authenticate(issued.token()).orElseThrow();
        assertThat(who.agent()).isTrue();
        assertThat(who.memberId()).isEqualTo(AGENT);

        auth.revokeAgentToken(issued.info().id());
        assertThat(auth.authenticate(issued.token())).isEmpty();
    }

    @Test
    void agentsCannotHavePasswordsAndHumansCannotHaveServiceTokens() {
        assertThatThrownBy(() -> auth.grantAccess(AGENT, "qa@kuador.local", PASSWORD)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> auth.createAgentToken(HUMAN, "x")).isInstanceOf(DomainException.class);
    }

    @Test
    void changingPasswordClosesOtherSessions() {
        String current = auth.login("admin@kuador.local", PASSWORD).token();
        String other = auth.login("admin@kuador.local", PASSWORD).token();

        auth.changePassword(auth.authenticate(current).orElseThrow(), PASSWORD, "nueva-clave-segura");

        assertThat(auth.authenticate(current)).isPresent();
        assertThat(auth.authenticate(other)).isEmpty();
        assertThat(auth.login("admin@kuador.local", "nueva-clave-segura").token()).isNotBlank();
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> auth.grantAccess(HUMAN, "admin@kuador.local", "corta")).isInstanceOf(DomainException.class);
    }

    @Test
    void sessionsCarryTheWorkspaceOfTheMember() {
        String token = auth.login("admin@kuador.local", PASSWORD).token();

        assertThat(auth.authenticate(token)).get().extracting(AuthenticatedMember::workspaceId).isEqualTo(TestTenant.WORKSPACE);
        assertThat(auth.accounts()).extracting(AuthService.Account::email).containsExactly("admin@kuador.local");
    }

    @Test
    void agentTokensOfAnotherWorkspaceAreInvisibleAndCannotBeRevoked() {
        AuthService.IssuedToken issued = auth.createAgentToken(AGENT, "runner");
        java.util.UUID other = java.util.UUID.randomUUID();

        assertThat(ec.kuador.board.shared.tenant.TenantContext.call(other, auth::agentTokens)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ec.kuador.board.shared.tenant.TenantContext.run(other,
                () -> auth.revokeAgentToken(issued.info().id()))).isInstanceOf(ec.kuador.board.shared.error.NotFoundException.class);
        assertThat(auth.authenticate(issued.token())).isPresent();
    }

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static class FakeCredentials implements CredentialRepository {
        final Map<UUID, Credential> store = new HashMap<>();
        public Optional<Credential> findByEmail(String email) { return store.values().stream().filter(c -> c.email().equals(email)).findFirst(); }
        public Optional<Credential> findByMemberId(UUID id) { return Optional.ofNullable(store.get(id)); }
        public boolean existsByEmailForOtherMember(String email, UUID id) { return store.values().stream().anyMatch(c -> c.email().equals(email) && !c.memberId().equals(id)); }
        public boolean any() { return !store.isEmpty(); }
        public List<Credential> findByMemberIds(List<UUID> ids) { return store.values().stream().filter(c -> ids.contains(c.memberId())).toList(); }
        public Credential save(Credential c) { store.put(c.memberId(), c); return c; }
    }

    static class FakeTokens implements AccessTokenRepository {
        @Override
        public List<AccessToken> findActiveRunnerTokens(UUID memberId) {
            return store.values().stream().filter(t -> t.memberId().equals(memberId) && t.kind() == TokenKind.RUNNER && t.revokedAt() == null).toList();
        }

        final Map<UUID, AccessToken> store = new HashMap<>();
        public Optional<AccessToken> findByHash(String h) { return store.values().stream().filter(t -> t.tokenHash().equals(h)).findFirst(); }
        public Optional<AccessToken> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
        public List<AccessToken> findActiveAgentTokens(UUID ws) { return store.values().stream().filter(t -> t.workspaceId().equals(ws) && t.kind() == TokenKind.AGENT && t.revokedAt() == null).toList(); }
        public List<AccessToken> findActiveSessions(UUID m) { return store.values().stream().filter(t -> t.memberId().equals(m) && t.kind() == TokenKind.SESSION && t.revokedAt() == null).toList(); }
        public AccessToken save(AccessToken t) { store.put(t.id(), t); return t; }
    }
}
