package ec.kuador.board.member.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: persistencia de miembros. */
public interface MemberRepository {

    List<Member> findAll();

    Optional<Member> findById(UUID id);

    boolean existsById(UUID id);

    Member save(Member member);

    long countAgentsWithRole(String role);
}
