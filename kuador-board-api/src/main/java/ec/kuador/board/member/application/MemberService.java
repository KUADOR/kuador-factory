package ec.kuador.board.member.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.member.domain.MemberRepository;
import ec.kuador.board.shared.error.DomainException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService implements MemberApi, CatalogUsage {

    private final MemberRepository members;
    private final CatalogApi catalog;
    private final Clock clock;

    MemberService(MemberRepository members, CatalogApi catalog, Clock clock) {
        this.members = members;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Member> list() {
        return members.findAll();
    }

    /** Un agente debe tener un rol del catálogo; para una persona el rol es solo una etiqueta. */
    @Transactional
    public Member create(String name, MemberKind kind, String role, String description, String avatarColor) {
        if (kind == MemberKind.AGENT && catalog.role(role).isEmpty()) {
            throw new DomainException("El rol " + role + " no existe en el catálogo del espacio. Créalo primero en Catálogo → Roles.");
        }
        return members.save(Member.create(name, kind, role, description, avatarColor, clock.instant()));
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
    @Transactional
    public MemberView createHuman(String name, String role, String description, String avatarColor) {
        return view(create(name, MemberKind.HUMAN, role, description, avatarColor));
    }

    @Override
    @Transactional
    public MemberView createAgent(String roleKey, String name, String description, String avatarColor) {
        return view(create(name, MemberKind.AGENT, roleKey, description, avatarColor));
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
        return new MemberView(m.id(), m.workspaceId(), m.name(), m.kind() == MemberKind.AGENT, m.role(), m.avatarColor(), m.description());
    }
}
