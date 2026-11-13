package ec.kuador.board.member.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.member.domain.MemberRepository;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.ForbiddenException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ec.kuador.board.billing.BillingApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService implements MemberApi, CatalogUsage {

    private final MemberRepository members;
    private final CatalogApi catalog;
    private final Clock clock;
    private final BillingApi billing;

    MemberService(MemberRepository members, CatalogApi catalog, Clock clock, BillingApi billing) {
        this.members = members;
        this.catalog = catalog;
        this.clock = clock;
        this.billing = billing;
    }

    @Transactional(readOnly = true)
    public List<Member> list() {
        return members.findAll();
    }

    /** Un agente debe tener un rol del catálogo; para una persona el rol es solo una etiqueta. */
    @Transactional
    public Member create(String name, MemberKind kind, String role, String description, String avatarColor, MemberAccess access) {
        if (kind == MemberKind.AGENT && catalog.role(role).isEmpty()) {
            throw new DomainException("El rol " + role + " no existe en el catálogo del espacio. Créalo primero en Catálogo → Roles.");
        }
        if (kind == MemberKind.HUMAN) billing.requireRoom(BillingApi.Limit.MEMBERS, members.findAll().stream().filter(m -> m.kind() == MemberKind.HUMAN).count());
        MemberAccess level = kind == MemberKind.AGENT ? MemberAccess.EDIT : access != null ? access : MemberAccess.defaultFor(role);
        return members.save(Member.create(name, kind, role, description, avatarColor, level, clock.instant()));
    }

    /** Crea un miembro a pedido de {@code actorId}: solo un dueño crea administradores o dueños. */
    @Transactional
    public Member create(String name, MemberKind kind, String role, String description, String avatarColor, MemberAccess access, UUID actorId) {
        if (kind == MemberKind.HUMAN && access != null) requireCanManage(actorId, access);
        return create(name, kind, role, description, avatarColor, access);
    }

    /**
     * Cambia el acceso de una persona. Nadie cambia el suyo (lo hace otra persona); quien administra
     * gestiona a quienes editan o miran, y solo un dueño nombra o quita administradores y dueños.
     * Siempre queda al menos un dueño.
     */
    @Override
    @Transactional
    public MemberView setAccess(UUID memberId, MemberAccess access, UUID actorId) {
        Member m = members.findById(memberId).orElseThrow(() -> new NotFoundException("No existe el miembro " + memberId + "."));
        if (m.kind() == MemberKind.AGENT) throw new DomainException("Un agente siempre tiene acceso de edición.");
        if (m.id().equals(actorId)) throw new DomainException("No puedes cambiar tu propio acceso; pídeselo a otra persona del equipo.");
        requireCanManage(actorId, m.access());
        requireCanManage(actorId, access);
        if (m.access() == access) return view(m);
        if (m.access() == MemberAccess.OWNER) {
            long owners = members.findAll().stream().filter(x -> x.kind() == MemberKind.HUMAN && x.access() == MemberAccess.OWNER).count();
            if (owners <= 1) throw new ConflictException("Debe quedar al menos un dueño del espacio.");
        }
        return view(members.save(m.withAccess(access)));
    }

    /**
     * {@code actorId} puede gestionar a alguien de nivel {@code level} (darlo, quitarlo, invitarlo o darle su
     * cuenta): quien administra gestiona a quienes editan o miran; administradores y dueños, solo un dueño.
     */
    @Override
    @Transactional(readOnly = true)
    public void requireCanManage(UUID actorId, MemberAccess level) {
        Member actor = actorId == null ? null : members.findById(actorId).filter(a -> a.kind() == MemberKind.HUMAN).orElse(null);
        if (actor == null || !actor.access().administers()) throw new ForbiddenException("Solo quien administra el espacio gestiona el equipo.");
        if (level != null && level.administers() && actor.access() != MemberAccess.OWNER) {
            throw new ForbiddenException("Solo un dueño del espacio nombra o cambia administradores y dueños.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID memberId) {
        return members.existsById(memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberView> find(UUID memberId) {
        return members.findById(memberId).map(MemberService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberView> humans() {
        return members.findAll().stream()
                .filter(m -> m.kind() == MemberKind.HUMAN)
                .sorted(Comparator.comparing(Member::createdAt))
                .map(MemberService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberView> agents() {
        return members.findAll().stream()
                .filter(m -> m.kind() == MemberKind.AGENT)
                .sorted(Comparator.comparing(Member::createdAt))
                .map(MemberService::view)
                .toList();
    }

    @Override
    @Transactional
    public MemberView createHuman(String name, String role, String description, String avatarColor) {
        return view(create(name, MemberKind.HUMAN, role, description, avatarColor, MemberAccess.EDIT));
    }

    @Override
    @Transactional
    public MemberView createHuman(String name, String role, String description, String avatarColor, MemberAccess access) {
        return view(create(name, MemberKind.HUMAN, role, description, avatarColor, access));
    }

    @Override
    @Transactional
    public MemberView createAgent(String roleKey, String name, String description, String avatarColor) {
        return view(create(name, MemberKind.AGENT, roleKey, description, avatarColor, MemberAccess.EDIT));
    }

    // ---- CatalogUsage: el catálogo no deja borrar un rol que usa algún agente ----

    @Override
    public long projectsUsingMethodology(String methodologyKey) {
        return 0;
    }

    @Override
    @Transactional(readOnly = true)
    public long membersWithRole(String roleKey) {
        return members.countAgentsWithRole(roleKey);
    }

    private static MemberView view(Member m) {
        return new MemberView(m.id(), m.workspaceId(), m.name(), m.kind() == MemberKind.AGENT, m.role(), m.avatarColor(), m.description(), m.access());
    }
}
