package ec.kuador.board.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    Optional<Credential> findByEmail(String email);

    Optional<Credential> findByMemberId(UUID memberId);

    boolean existsByEmailForOtherMember(String email, UUID memberId);

    boolean any();

    List<Credential> findByMemberIds(List<UUID> memberIds);

    Credential save(Credential credential);
}
