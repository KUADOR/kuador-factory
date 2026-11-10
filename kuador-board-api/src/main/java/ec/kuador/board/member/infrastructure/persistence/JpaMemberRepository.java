package ec.kuador.board.member.infrastructure.persistence;

import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.member.domain.MemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataMemberRepository extends JpaRepository<MemberEntity, UUID> {

    long countByKindAndRole(MemberKind kind, String role);
}

@Repository
class JpaMemberRepository implements MemberRepository {

    private final SpringDataMemberRepository jpa;

    JpaMemberRepository(SpringDataMemberRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Member> findAll() {
        return jpa.findAll(Sort.by("kind", "name")).stream().map(MemberEntity::toDomain).toList();
    }

    @Override
    public Optional<Member> findById(UUID id) {
        return jpa.findById(id).map(MemberEntity::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public long countAgentsWithRole(String role) {
        return jpa.countByKindAndRole(MemberKind.AGENT, role);
    }

    @Override
    public Member save(Member member) {
        return jpa.save(MemberEntity.from(member)).toDomain();
    }
}
