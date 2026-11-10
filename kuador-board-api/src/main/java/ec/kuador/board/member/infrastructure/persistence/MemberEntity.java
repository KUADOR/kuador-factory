package ec.kuador.board.member.infrastructure.persistence;

import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "member")
class MemberEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    private String name;

    @Enumerated(EnumType.STRING)
    private MemberKind kind;

    private String role;

    private String description;

    @Column(name = "avatar_color")
    private String avatarColor;

    @Enumerated(EnumType.STRING)
    private MemberAccess access;

    @Column(name = "created_at")
    private Instant createdAt;

    protected MemberEntity() {}

    static MemberEntity from(Member m) {
        MemberEntity e = new MemberEntity();
        e.id = m.id();
        e.name = m.name();
        e.kind = m.kind();
        e.role = m.role();
        e.description = m.description();
        e.avatarColor = m.avatarColor();
        e.access = m.access();
        e.createdAt = m.createdAt();
        return e;
    }

    Member toDomain() {
        return new Member(id, workspaceId, name, kind, role, description, avatarColor, access, createdAt);
    }
}
