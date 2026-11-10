package ec.kuador.board.testing;

import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Equipo en memoria. */
public class InMemoryMembers implements MemberApi {

    public final Map<UUID, MemberView> all = new LinkedHashMap<>();

    public MemberView human(String name) {
        return add(new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, name, false, "OWNER", "#ffffff", null));
    }

    public MemberView agent(String roleKey, String name, String description) {
        return add(new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, name, true, roleKey, "#ffffff", description));
    }

    public MemberView add(MemberView member) {
        all.put(member.id(), member);
        return member;
    }

    @Override
    public boolean exists(UUID memberId) {
        return all.containsKey(memberId);
    }

    @Override
    public Optional<MemberView> find(UUID memberId) {
        return Optional.ofNullable(all.get(memberId));
    }

    @Override
    public List<MemberView> humans() {
        return all.values().stream().filter(m -> !m.agent()).toList();
    }

    @Override
    public List<MemberView> agents() {
        return all.values().stream().filter(MemberView::agent).toList();
    }

    @Override
    public MemberView setAccess(UUID memberId, ec.kuador.board.member.MemberAccess access, UUID actorId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MemberView createHuman(String name, String role, String description, String avatarColor) {
        return add(new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, name, false, role, avatarColor, description));
    }

    @Override
    public MemberView createAgent(String roleKey, String name, String description, String avatarColor) {
        return add(new MemberView(UUID.randomUUID(), TestTenant.WORKSPACE, name, true, roleKey, avatarColor, description));
    }
}
