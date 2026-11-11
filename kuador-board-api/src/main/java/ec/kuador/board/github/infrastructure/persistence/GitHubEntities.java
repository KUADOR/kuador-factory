package ec.kuador.board.github.infrastructure.persistence;

import ec.kuador.board.github.domain.FlowState;
import ec.kuador.board.github.domain.GitHubApp;
import ec.kuador.board.github.domain.Installation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Tabla global (sin @TenantId): la app es una sola para la plataforma. */
@Entity
@Table(name = "github_app")
class GitHubAppEntity {

    @Id
    private short id = 1;
    @Column(name = "app_id")
    private long appId;
    private String slug;
    private String name;
    @Column(name = "owner_login")
    private String ownerLogin;
    @Column(name = "html_url")
    private String htmlUrl;
    @Column(name = "client_id")
    private String clientId;
    @Column(name = "client_secret")
    private String clientSecret;
    @Column(name = "private_key")
    private String privateKey;
    @Column(name = "webhook_secret")
    private String webhookSecret;
    @Column(name = "registered_by")
    private UUID registeredBy;
    @Column(name = "registered_at")
    private Instant registeredAt;

    protected GitHubAppEntity() {}

    static GitHubAppEntity from(GitHubApp a) {
        GitHubAppEntity e = new GitHubAppEntity();
        e.appId = a.appId();
        e.slug = a.slug();
        e.name = a.name();
        e.ownerLogin = a.ownerLogin();
        e.htmlUrl = a.htmlUrl();
        e.clientId = a.clientId();
        e.clientSecret = a.clientSecret();
        e.privateKey = a.privateKey();
        e.webhookSecret = a.webhookSecret();
        e.registeredBy = a.registeredBy();
        e.registeredAt = a.registeredAt();
        return e;
    }

    GitHubApp toDomain() {
        return new GitHubApp(appId, slug, name, ownerLogin, htmlUrl, clientId, clientSecret, privateKey, webhookSecret, registeredBy, registeredAt);
    }
}

@Entity
@Table(name = "github_installation")
class InstallationEntity {

    @Id
    private UUID id;
    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;
    @Column(name = "installation_id")
    private long installationId;
    @Column(name = "account_login")
    private String accountLogin;
    @Column(name = "account_type")
    private String accountType;
    @Column(name = "linked_by")
    private UUID linkedBy;
    @Column(name = "linked_at")
    private Instant linkedAt;

    protected InstallationEntity() {}

    static InstallationEntity from(Installation i) {
        InstallationEntity e = new InstallationEntity();
        e.id = i.id();
        e.installationId = i.installationId();
        e.accountLogin = i.accountLogin();
        e.accountType = i.accountType();
        e.linkedBy = i.linkedBy();
        e.linkedAt = i.linkedAt();
        return e;
    }

    Installation toDomain() {
        return new Installation(id, installationId, accountLogin, accountType, linkedBy, linkedAt);
    }
}

@Entity
@Table(name = "github_flow_state")
class FlowStateEntity {

    @Id
    private String state;
    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;
    @Enumerated(EnumType.STRING)
    private FlowState.Purpose purpose;
    @Column(name = "member_id")
    private UUID memberId;
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected FlowStateEntity() {}

    static FlowStateEntity from(FlowState s) {
        FlowStateEntity e = new FlowStateEntity();
        e.state = s.state();
        e.purpose = s.purpose();
        e.memberId = s.memberId();
        e.expiresAt = s.expiresAt();
        return e;
    }

    FlowState toDomain() {
        return new FlowState(state, purpose, memberId, expiresAt);
    }
}
