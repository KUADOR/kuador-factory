package ec.kuador.board.workspace.application;

import ec.kuador.board.auth.AuthApi;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.domain.Workspace;
import ec.kuador.board.workspace.domain.WorkspaceRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkspaceService implements ec.kuador.board.workspace.WorkspaceApi {

    private final WorkspaceRepository workspaces;
    private final CatalogApi catalog;
    private final MemberApi members;
    private final AuthApi auth;
    private final TransactionTemplate tx;
    private final Clock clock;

    WorkspaceService(WorkspaceRepository workspaces, CatalogApi catalog, MemberApi members, AuthApi auth,
            PlatformTransactionManager txManager, Clock clock) {
        this.workspaces = workspaces;
        this.catalog = catalog;
        this.members = members;
        this.auth = auth;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public record Owner(String name, String email, String password) {}

    /** Identidad del espacio: identificador, nombre y su marca. */
    public record Brand(String slug, String name, String description, String brandColor, String brandOnColor, String logo) {
        public static Brand of(String slug, String name) {
            return new Brand(slug, name, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Workspace current() {
        return workspaces.findById(TenantContext.require()).orElseThrow(() -> new NotFoundException("El espacio de trabajo ya no existe."));
    }

    @Override
    @Transactional(readOnly = true)
    public Identity identity() {
        Workspace w = current();
        return new Identity(w.slug(), w.name(), w.languages().team(), w.languages().code());
    }

    /** Nombre, descripción, color base y logo del espacio actual (solo personas). */
    @Transactional
    public Workspace updateBranding(String name, String description, String brandColor, String brandOnColor, String logo) {
        return workspaces.save(current().withBranding(name, description, brandColor, brandOnColor, logo));
    }

    /** Idioma del equipo y del código del espacio actual (solo personas). */
    @Transactional
    public Workspace updateLanguages(String team, String code) {
        return workspaces.save(current().withLanguages(new ec.kuador.board.workspace.domain.Languages(team, code)));
    }

    /**
     * Lleva cada espacio a la versión actual del catálogo de fábrica: recibe una sola vez las
     * entradas nuevas (p. ej. un servidor MCP), sin tocar lo que el equipo editó o borró.
     */
    public void upgradeCatalogs() {
        int current = catalog.factoryVersion();
        for (java.util.UUID id : TenantContext.asRoot(() -> tx.execute(s -> workspaces.allIds()))) {
            int from = TenantContext.asRoot(() -> tx.execute(s -> workspaces.catalogVersion(id)));
            if (from >= current) continue;
            TenantContext.run(id, () -> tx.executeWithoutResult(s -> catalog.receiveFactoryAdditions(from)));
            TenantContext.asRoot(() -> {
                tx.executeWithoutResult(s -> workspaces.setCatalogVersion(id, current));
                return null;
            });
        }
    }

    @Transactional(readOnly = true)
    public boolean slugTaken(String slug) {
        return workspaces.existsBySlug(slug);
    }

    @Transactional(readOnly = true)
    public boolean any() {
        return workspaces.count() > 0;
    }

    /**
     * Crea un espacio listo para usar: catálogo de fábrica, la persona dueña con acceso y un agente
     * por cada rol de fábrica. Todo en una transacción abierta ya dentro del espacio nuevo.
     */
    public Workspace provision(Brand brand, Owner owner) {
        // Se valida todo antes de guardar nada: un error a mitad dejaría un espacio sin dueño.
        Workspace draft = Workspace.create(brand.slug(), brand.name(), brand.description(), brand.brandColor(), brand.brandOnColor(), brand.logo(), clock.instant());
        if (auth.emailInUse(owner.email())) {
            throw new ConflictException("Ese correo ya tiene cuenta en el tablero. Entra con tu contraseña.");
        }
        Workspace workspace = tx.execute(s -> {
            if (workspaces.existsBySlug(draft.slug())) throw new ConflictException("Ya existe un espacio con el identificador " + draft.slug() + ".");
            return workspaces.save(draft);
        });
        TenantContext.run(workspace.id(), () -> tx.executeWithoutResult(s -> {
            var seeds = catalog.provisionDefaults();
            MemberView person = members.createHuman(owner.name(), "OWNER", "Responsable humano: prioriza, aprueba y decide.", "#8fb4ff");
            for (CatalogApi.AgentSeed seed : seeds) {
                AgentRole role = catalog.role(seed.roleKey()).orElseThrow();
                members.createAgent(role.key(), seed.memberName(), role.description(), seed.color());
            }
            auth.grantAccess(person.id(), owner.email(), owner.password());
        }));
        // Recibió el catálogo de fábrica completo: queda en su versión actual.
        tx.executeWithoutResult(s -> workspaces.setCatalogVersion(workspace.id(), catalog.factoryVersion()));
        return workspace;
    }
}
