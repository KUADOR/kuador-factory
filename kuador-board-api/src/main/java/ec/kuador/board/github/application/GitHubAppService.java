package ec.kuador.board.github.application;

import ec.kuador.board.github.GitHubApi;
import ec.kuador.board.github.domain.FlowState;
import ec.kuador.board.github.domain.GitHubApp;
import ec.kuador.board.github.domain.GitHubAppGateway;
import ec.kuador.board.github.domain.GitHubAppGateway.AppKeys;
import ec.kuador.board.github.domain.GitHubAppGateway.Token;
import ec.kuador.board.github.domain.GitHubStore;
import ec.kuador.board.github.domain.Installation;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.workspace.WorkspaceApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub App de la plataforma y conexiones de cada espacio.
 *
 * <p>Registro: solo la dueña del espacio que opera la plataforma ({@code board.platform.workspace}),
 * con el flujo «manifiesto» de GitHub (un clic, sin copiar claves). Instalación: cualquier persona que
 * edite un espacio; al volver de GitHub se comprueba con OAuth que esa persona administra la
 * instalación, para que nadie conecte la organización de otro con solo adivinar un número.
 */
@Service
public class GitHubAppService implements GitHubApi {

    private static final Duration STATE_TTL = Duration.ofMinutes(15);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Lo que ve la pantalla de conexiones. */
    public record Overview(Optional<AppView> app, boolean canRegister, List<Installation> installations) {}

    public record AppView(String slug, String name, String ownerLogin, String htmlUrl) {}

    /** Formulario que el navegador envía a GitHub para crear la app. */
    public record ManifestForm(String postUrl, String manifest) {}

    private final GitHubStore store;
    private final GitHubAppGateway github;
    private final SecretBox secrets;
    private final WorkspaceApi workspace;
    private final MemberApi members;
    private final ObjectMapper json;
    private final Clock clock;
    private final String platformWorkspace;
    private final String webUrl;
    private final String apiPublicUrl;
    private final String githubWeb;
    private final Map<Long, Token> tokens = new ConcurrentHashMap<>();

    GitHubAppService(GitHubStore store, GitHubAppGateway github, SecretBox secrets, WorkspaceApi workspace, MemberApi members, ObjectMapper json,
            Clock clock, @Value("${board.platform.workspace:${board.bootstrap.workspace-slug:kuador}}") String platformWorkspace,
            @Value("${board.web-url:http://localhost:3600}") String webUrl, @Value("${board.public-url:http://localhost:8091}") String apiPublicUrl,
            @Value("${board.github.web-url:https://github.com}") String githubWeb) {
        this.store = store;
        this.github = github;
        this.secrets = secrets;
        this.workspace = workspace;
        this.members = members;
        this.json = json;
        this.clock = clock;
        this.platformWorkspace = platformWorkspace;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.apiPublicUrl = apiPublicUrl.replaceAll("/+$", "");
        this.githubWeb = githubWeb.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID actorId) {
        Optional<AppView> app = store.app().map(GitHubAppService::view);
        return new Overview(app, app.isEmpty() && operatesPlatform(actorId), store.installations());
    }

    // ---- Registro de la app (una vez, por quien opera la plataforma) ----

    @Transactional
    public ManifestForm manifest(String appName, String organization, UUID actorId) {
        if (store.app().isPresent()) throw new DomainException("La GitHub App ya está registrada.");
        if (!operatesPlatform(actorId)) {
            throw new ForbiddenException("La GitHub App la registra una vez la dueña del espacio que opera la plataforma.");
        }
        String name = appName == null ? "" : appName.strip();
        if (name.length() < 3 || name.length() > 34) throw new DomainException("El nombre de la app va de 3 a 34 caracteres y debe ser único en GitHub.");
        String org = organization == null || organization.isBlank() ? null : organization.strip();
        if (org != null && !LOGIN.matcher(org).matches()) throw new DomainException("Nombre de organización de GitHub no válido: " + org);
        String state = newState(FlowState.Purpose.REGISTER, actorId);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", name);
        manifest.put("url", webUrl);
        // Sin URL pública todavía: el webhook queda creado pero apagado hasta el despliegue.
        manifest.put("hook_attributes", Map.of("url", apiPublicUrl + "/api/github/webhook", "active", false));
        manifest.put("redirect_url", webUrl + "/conexiones/github/app-creada");
        manifest.put("callback_urls", List.of(webUrl + "/conexiones/github/instalada"));
        manifest.put("request_oauth_on_install", true);
        manifest.put("public", true);
        manifest.put("default_permissions", Map.of(
                "administration", "write", // crear repos y proteger ramas
                "contents", "write",
                "pull_requests", "write",
                "workflows", "write", // el CI mínimo vive en .github/workflows
                "checks", "read", // el agente QA del tablero lee el resultado del CI de un PR
                "statuses", "read",
                "metadata", "read"));
        manifest.put("default_events", List.of());
        String path = org == null ? "/settings/apps/new" : "/organizations/" + org + "/settings/apps/new";
        return new ManifestForm(githubWeb + path + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8), json.writeValueAsString(manifest));
    }

    @Transactional
    public AppView completeRegistration(String code, String state, UUID actorId) {
        take(state, FlowState.Purpose.REGISTER, actorId);
        if (store.app().isPresent()) throw new DomainException("La GitHub App ya está registrada.");
        if (code == null || code.isBlank()) throw new DomainException("GitHub no devolvió el código de la app.");
        GitHubAppGateway.ConvertedApp app = github.convertManifest(code.strip());
        GitHubApp saved = new GitHubApp(app.appId(), app.slug(), app.name(), app.ownerLogin(), app.htmlUrl(), app.clientId(),
                secrets.encrypt(app.clientSecret()), secrets.encrypt(app.privateKeyPem()),
                app.webhookSecret() == null ? null : secrets.encrypt(app.webhookSecret()), actorId, clock.instant());
        store.saveApp(saved);
        return view(saved);
    }

    // ---- Conexiones del espacio (instalar la app en una organización) ----

    @Transactional
    public String installUrl(UUID actorId) {
        GitHubApp app = requireApp();
        String state = newState(FlowState.Purpose.INSTALL, actorId);
        return githubWeb + "/apps/" + app.slug() + "/installations/new?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    @Transactional
    public Installation completeInstallation(long installationId, String code, String state, UUID actorId) {
        take(state, FlowState.Purpose.INSTALL, actorId);
        AppKeys keys = keys(requireApp());
        if (code == null || code.isBlank()) throw new DomainException("GitHub no devolvió la autorización de quien instaló la app.");
        if (!github.userInstallations(keys, code.strip()).contains(installationId)) {
            throw new ForbiddenException("Tu cuenta de GitHub no administra esa instalación.");
        }
        GitHubAppGateway.Account account = github.installationAccount(keys, installationId);
        Installation saved = store.installation(installationId)
                .map(i -> new Installation(i.id(), installationId, account.login(), account.type(), i.linkedBy(), i.linkedAt()))
                .orElse(new Installation(UUID.randomUUID(), installationId, account.login(), account.type(), actorId, clock.instant()));
        store.saveInstallation(saved);
        return saved;
    }

    /** Quita la conexión del espacio. No desinstala la app en GitHub (eso se hace allá). */
    @Transactional
    public void disconnect(long installationId) {
        store.installation(installationId).orElseThrow(() -> new NotFoundException("No hay una conexión de GitHub " + installationId + "."));
        store.deleteInstallation(installationId);
        tokens.remove(installationId);
    }

    // ---- GitHubApi ----

    @Override
    @Transactional(readOnly = true)
    public List<Connection> connections() {
        return store.installations().stream().map(GitHubAppService::connection).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Connection connection(long installationId) {
        return store.installation(installationId).map(GitHubAppService::connection)
                .orElseThrow(() -> new DomainException("Este espacio no tiene conectada la instalación de GitHub " + installationId + "."));
    }

    @Override
    @Transactional(readOnly = true)
    public String installationToken(long installationId) {
        Connection connection = connection(installationId); // solo instalaciones del espacio actual
        Instant now = clock.instant();
        Token cached = tokens.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(now.plus(TOKEN_MARGIN))) return cached.value();
        Token fresh;
        try {
            fresh = github.installationToken(keys(requireApp()), installationId);
        } catch (GitHubAppGateway.InstallationGone e) {
            throw new DomainException("La GitHub App ya no está instalada en «" + connection.account() + "» (se desinstaló o la app se volvió a crear). "
                    + "En Conexiones → GitHub, quita esa conexión y vuelve a conectar la organización; después reintenta.");
        }
        tokens.put(installationId, fresh);
        return fresh.value();
    }

    // ---- Apoyo ----

    /** Opera la plataforma: persona dueña del espacio configurado como plataforma. */
    private boolean operatesPlatform(UUID actorId) {
        if (!platformWorkspace.equals(workspace.identity().slug())) return false;
        Optional<MemberView> actor = members.find(actorId);
        return actor.isPresent() && actor.get().owner();
    }

    private String newState(FlowState.Purpose purpose, UUID actorId) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        store.saveState(new FlowState(state, purpose, actorId, clock.instant().plus(STATE_TTL)));
        return state;
    }

    private void take(String state, FlowState.Purpose purpose, UUID actorId) {
        FlowState s = state == null ? null : store.takeState(state).orElse(null);
        if (s == null || s.purpose() != purpose || !s.memberId().equals(actorId) || s.expiresAt().isBefore(clock.instant())) {
            throw new ForbiddenException("La respuesta de GitHub no corresponde a un pedido tuyo reciente. Vuelve a empezar desde el tablero.");
        }
    }

    private GitHubApp requireApp() {
        return store.app().orElseThrow(() -> new DomainException("La GitHub App todavía no está registrada en la plataforma."));
    }

    private AppKeys keys(GitHubApp app) {
        return new AppKeys(app.appId(), app.clientId(), secrets.decrypt(app.clientSecret()), secrets.decrypt(app.privateKey()));
    }

    private static AppView view(GitHubApp app) {
        return new AppView(app.slug(), app.name(), app.ownerLogin(), app.htmlUrl());
    }

    private static Connection connection(Installation i) {
        return new Connection(i.installationId(), i.accountLogin(), i.accountType());
    }
}
