package ec.kuador.board.workspace.infrastructure.bootstrap;

import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Primer arranque: si no existe ningún espacio, crea el inicial con las variables de entorno. */
@Component
class WorkspaceBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBootstrap.class);

    private final WorkspaceService workspaces;
    private final String slug;
    private final String name;
    private final String ownerName;
    private final String email;
    private final String password;

    WorkspaceBootstrap(
            WorkspaceService workspaces,
            @Value("${board.bootstrap.workspace-slug:kuador}") String slug,
            @Value("${board.bootstrap.workspace-name:Kuador}") String name,
            @Value("${board.bootstrap.owner-name:Equipo Kuador}") String ownerName,
            @Value("${board.bootstrap.admin-email:}") String email,
            @Value("${board.bootstrap.admin-password:}") String password) {
        this.workspaces = workspaces;
        this.slug = slug;
        this.name = name;
        this.ownerName = ownerName;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Los espacios existentes reciben lo nuevo del catálogo de fábrica (una sola vez cada versión).
        workspaces.upgradeCatalogs();
        if (TenantContext.asRoot(workspaces::any)) return;
        if (email.isBlank() || password.isBlank()) {
            log.warn("No hay ningún espacio de trabajo. Define BOARD_BOOTSTRAP_ADMIN_EMAIL y BOARD_BOOTSTRAP_ADMIN_PASSWORD para crear el primero.");
            return;
        }
        workspaces.provision(WorkspaceService.Brand.of(slug, name), new WorkspaceService.Owner(ownerName, email, password));
        log.info("Espacio de trabajo «{}» creado con su catálogo de fábrica, la persona dueña y los agentes de cada rol.", name);
    }
}
