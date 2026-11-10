package ec.kuador.board.auth.infrastructure.persistence;

import ec.kuador.board.auth.domain.AccessToken;
import ec.kuador.board.auth.domain.AccessTokenRepository;
import ec.kuador.board.auth.domain.Credential;
import ec.kuador.board.auth.domain.CredentialRepository;
import ec.kuador.board.auth.domain.TokenKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

interface SpringDataCredentialRepository extends JpaRepository<CredentialEntity, UUID> {

    Optional<CredentialEntity> findByEmail(String email);

    boolean existsByEmailAndMemberIdNot(String email, UUID memberId);

    List<CredentialEntity> findByMemberIdIn(List<UUID> memberIds);
}

interface SpringDataAccessTokenRepository extends JpaRepository<AccessTokenEntity, UUID> {

    Optional<AccessTokenEntity> findByTokenHash(String tokenHash);

    @Query("select t from AccessTokenEntity t where t.workspaceId = :workspaceId and t.kind = :kind and t.revokedAt is null order by t.createdAt")
    List<AccessTokenEntity> findActive(UUID workspaceId, TokenKind kind);

    @Query("select t from AccessTokenEntity t where t.memberId = :memberId and t.kind = :kind and t.revokedAt is null")
    List<AccessTokenEntity> findActiveByMember(UUID memberId, TokenKind kind);
}

@Repository
class JpaCredentialRepository implements CredentialRepository {

    private final SpringDataCredentialRepository jpa;

    JpaCredentialRepository(SpringDataCredentialRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Credential> findByEmail(String email) {
        return jpa.findByEmail(email).map(CredentialEntity::toDomain);
    }

    @Override
    public Optional<Credential> findByMemberId(UUID memberId) {
        return jpa.findById(memberId).map(CredentialEntity::toDomain);
    }

    @Override
    public boolean existsByEmailForOtherMember(String email, UUID memberId) {
        return jpa.existsByEmailAndMemberIdNot(email, memberId);
    }

    @Override
    public boolean any() {
        return jpa.count() > 0;
    }

    @Override
    public List<Credential> findByMemberIds(List<UUID> memberIds) {
        return jpa.findByMemberIdIn(memberIds).stream().map(CredentialEntity::toDomain).toList();
    }

    @Override
    public Credential save(Credential credential) {
        return jpa.save(CredentialEntity.from(credential)).toDomain();
    }
}

@Repository
class JpaAccessTokenRepository implements AccessTokenRepository {

    private final SpringDataAccessTokenRepository jpa;

    JpaAccessTokenRepository(SpringDataAccessTokenRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<AccessToken> findByHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(AccessTokenEntity::toDomain);
    }

    @Override
    public Optional<AccessToken> findById(UUID id) {
        return jpa.findById(id).map(AccessTokenEntity::toDomain);
    }

    @Override
    public List<AccessToken> findActiveAgentTokens(UUID workspaceId) {
        return jpa.findActive(workspaceId, TokenKind.AGENT).stream().map(AccessTokenEntity::toDomain).toList();
    }

    @Override
    public List<AccessToken> findActiveRunnerTokens(UUID memberId) {
        return jpa.findActiveByMember(memberId, TokenKind.RUNNER).stream().map(AccessTokenEntity::toDomain).toList();
    }

    @Override
    public List<AccessToken> findActiveSessions(UUID memberId) {
        return jpa.findActiveByMember(memberId, TokenKind.SESSION).stream().map(AccessTokenEntity::toDomain).toList();
    }

    @Override
    public AccessToken save(AccessToken token) {
        return jpa.save(AccessTokenEntity.from(token)).toDomain();
    }
}
