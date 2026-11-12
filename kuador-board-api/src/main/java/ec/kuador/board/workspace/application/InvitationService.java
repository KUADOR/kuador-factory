package ec.kuador.board.workspace.application;

import ec.kuador.board.auth.AuthApi;
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.mail.Mailer;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.domain.Invitation;
import ec.kuador.board.workspace.domain.InvitationStore;
import ec.kuador.board.workspace.domain.Workspace;
import ec.kuador.board.workspace.domain.WorkspaceRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitar personas por correo. La persona queda en el equipo desde que se la invita (cuenta para el límite del
 * plan); al abrir el enlace elige su contraseña y entra. Un enlace vale 7 días y una vez; reenviar invalida el
 * anterior. Los correos son únicos en toda la plataforma.
 */
@Service
public class InvitationService {

    private static final String[] COLORS = {"#8fb4ff", "#34d399", "#f59e0b", "#c4a7ff", "#f472b6", "#22d3ee", "#a3e635"};

    private final InvitationStore invitations;
    private final MemberApi members;
    private final AuthApi auth;
    private final WorkspaceRepository workspaces;
    private final Mailer mailer;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    InvitationService(InvitationStore invitations, MemberApi members, AuthApi auth, WorkspaceRepository workspaces, Mailer mailer,
            PlatformTransactionManager txManager, Clock clock, @Value("${board.web-url:http://localhost:3600}") String webUrl) {
        this.invitations = invitations;
        this.members = members;
        this.auth = auth;
        this.workspaces = workspaces;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    /** Invitación pendiente con el nombre de la persona. */
    public record Pending(UUID id, UUID memberId, String memberName, String email, Instant createdAt, Instant expiresAt, boolean expired) {}

    /** Lo que ve quien abre el enlace. */
    public record Preview(String workspaceName, String memberName, String email) {}

    /**
     * Invita a {@code memberId} (una persona sin acceso) o, si es nulo, crea la persona con {@code name},
     * {@code role} y {@code access} y la invita. Si el correo no sale, no queda nada creado.
     */
    @Transactional
    public Pending invite(UUID memberId, String name, String role, MemberAccess access, String rawEmail, UUID inviterId) {
        String email = SignupService.normalize(rawEmail);
        if (auth.emailInUse(email)) throw new ConflictException("Ese correo ya tiene una cuenta en Kuador Go.");
        MemberView person;
        if (memberId != null) {
            person = members.find(memberId).orElseThrow(() -> new NotFoundException("No existe esa persona en el equipo."));
            if (person.agent()) throw new DomainException("A los agentes no se los invita: conéctalos con un token o desde Agentes locales.");
            if (auth.emailOf(memberId).isPresent()) throw new DomainException(person.name() + " ya tiene acceso al tablero.");
        } else {
            if (name == null || name.isBlank()) throw new DomainException("Escribe el nombre de la persona.");
            person = members.createHuman(name.strip(), role == null || role.isBlank() ? "EQUIPO" : role.strip(), null,
                    COLORS[random.nextInt(COLORS.length)], access);
        }
        return send(person, email, inviterId);
    }

    @Transactional(readOnly = true)
    public List<Pending> pending() {
        UUID ws = TenantContext.require();
        Instant now = clock.instant();
        return invitations.pending(ws).stream().map(i -> pending(i, members.find(i.memberId()).map(MemberView::name).orElse(""), now)).toList();
    }

    /** Envía un enlace nuevo; el anterior deja de servir. */
    @Transactional
    public Pending resend(UUID invitationId, UUID inviterId) {
        Invitation i = invitations.find(TenantContext.require(), invitationId).filter(x -> x.acceptedAt() == null)
                .orElseThrow(() -> new NotFoundException("Esa invitación ya no está pendiente."));
        if (auth.emailInUse(i.email())) throw new ConflictException("Ese correo ya tiene una cuenta en Kuador Go.");
        MemberView person = members.find(i.memberId()).orElseThrow(() -> new NotFoundException("Esa persona ya no está en el equipo."));
        return send(person, i.email(), inviterId);
    }

    /** Anula el enlace. La persona sigue en el equipo, sin acceso. */
    @Transactional
    public void cancel(UUID invitationId) {
        UUID ws = TenantContext.require();
        Invitation i = invitations.find(ws, invitationId).orElseThrow(() -> new NotFoundException("No existe esa invitación."));
        invitations.deletePending(ws, i.memberId());
    }

    // ---- Sin sesión, con el enlace ----

    public Preview preview(String token) {
        Invitation i = usable(token);
        String workspace = TenantContext.asRoot(() -> tx.execute(s -> workspaces.findById(i.workspaceId()).map(Workspace::name).orElse("")));
        String name = TenantContext.call(i.workspaceId(), () -> tx.execute(s -> members.find(i.memberId()).map(MemberView::name).orElse("")));
        return new Preview(workspace, name, i.email());
    }

    /** Acepta: da el acceso con la contraseña elegida. Devuelve el correo para entrar. */
    public String accept(String token, String password) {
        Invitation i = usable(token);
        Instant now = clock.instant();
        TenantContext.run(i.workspaceId(), () -> tx.executeWithoutResult(s -> {
            if (members.find(i.memberId()).isEmpty()) throw new DomainException("Ya no formas parte de ese equipo.");
            auth.grantAccess(i.memberId(), i.email(), password);
            invitations.markAccepted(i.id(), now);
        }));
        return i.email();
    }

    private Invitation usable(String token) {
        Instant now = clock.instant();
        return invitations.findByTokenHash(SignupService.hash(token == null ? "" : token.strip())).filter(i -> i.usable(now))
                .orElseThrow(() -> new DomainException("La invitación venció o ya se usó. Pide a tu equipo que te invite de nuevo."));
    }

    private Pending send(MemberView person, String email, UUID inviterId) {
        UUID ws = TenantContext.require();
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        invitations.deletePending(ws, person.id());
        Invitation invitation = Invitation.create(ws, person.id(), email, SignupService.hash(token), inviterId, now);
        invitations.save(invitation);
        String workspace = workspaces.findById(ws).map(Workspace::name).orElse("tu equipo");
        String inviter = inviterId == null ? "Tu equipo" : members.find(inviterId).map(MemberView::name).orElse("Tu equipo");
        // Dentro de la transacción: si el correo no sale, no queda ni la persona ni la invitación.
        mailer.send(email, inviter + " te invitó a " + workspace + " en Kuador Go", """
                Hola %s:

                %s te invitó a trabajar con %s en Kuador Go, el tablero donde el equipo y sus agentes de IA avanzan el mismo flujo.

                Acepta la invitación y elige tu contraseña aquí:
                %s/invitacion?token=%s

                El enlace vence en 7 días y sirve una sola vez.
                """.formatted(person.name(), inviter, workspace, webUrl, token));
        return pending(invitation, person.name(), now);
    }

    private static Pending pending(Invitation i, String name, Instant now) {
        return new Pending(i.id(), i.memberId(), name, i.email(), i.createdAt(), i.expiresAt(), !i.expiresAt().isAfter(now));
    }
}
