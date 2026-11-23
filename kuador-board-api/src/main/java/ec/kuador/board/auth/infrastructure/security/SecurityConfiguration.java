package ec.kuador.board.auth.infrastructure.security;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.application.AuthService;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Reglas de acceso. Todo exige token salvo el login, health y la documentación. Niveles (ver MemberAccess):
 * el plan y los pagos son del dueño (OWNER); el equipo, las invitaciones, los accesos, los tokens, el
 * catálogo, la IA, GitHub, los repositorios y la auditoría, de quien administra (ADMIN u OWNER); trabajar en
 * los tableros, de quien edita. Un agente nunca administra: no puede crear agentes ni emitirse tokens. Quien
 * tiene acceso de solo lectura (VIEW) solo lee, salvo cerrar las puertas donde su rol aprueba.
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
class SecurityConfiguration {

    /** Persona con acceso de edición: crea tableros, los configura y conecta sus agentes locales. */
    private static final AuthorizationManager<RequestAuthorizationContext> HUMAN_EDITOR =
            AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasRole("HUMAN"), AuthorityAuthorizationManager.hasRole("EDITOR"));

    /** Persona que administra el espacio (ADMIN u OWNER). */
    private static final AuthorizationManager<RequestAuthorizationContext> HUMAN_ADMIN =
            AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasRole("HUMAN"), AuthorityAuthorizationManager.hasRole("ADMIN"));

    /** Persona dueña del espacio: el plan y los pagos. */
    private static final AuthorizationManager<RequestAuthorizationContext> HUMAN_OWNER =
            AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasRole("HUMAN"), AuthorityAuthorizationManager.hasRole("OWNER"));

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthService auth, ec.kuador.board.auth.application.PlatformAdmins platform,
            @org.springframework.beans.factory.annotation.Value("${board.public-url:http://localhost:8091}") String configuredPublicUrl,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}") int managementPort) throws Exception {
        String publicUrl = configuredPublicUrl.replaceAll("/+$", "");
        http.csrf(c -> c.disable()) // API sin cookies: el token va en un header que el navegador no envía solo.
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // El cierre de una respuesta asíncrona (tiempo real) y la página de error son despachos internos de
                        // una petición que ya pasó estas reglas: sin esto, cada desconexión terminaba en «Access Denied».
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Alta pública de espacios (modo SaaS): verificación por correo, ver SignupService.
                        .requestMatchers("/api/signup/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password-reset", "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/plans", "/api/public/invitations", "/api/public/packages").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/invitations/accept").permitAll()
                        .requestMatchers("/api/invitations", "/api/invitations/**").access(HUMAN_ADMIN)
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM")
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook/*").permitAll()
                        // El plan: quien administra lo ve (límites del equipo); cambiarlo y pagar, solo el dueño.
                        .requestMatchers(HttpMethod.GET, "/api/workspace/plan").access(HUMAN_ADMIN)
                        .requestMatchers("/api/workspace/plan", "/api/workspace/plan/**").access(HUMAN_OWNER)
                        // Las métricas, solo por el puerto de administración (interno, sin dominio): ahí las lee Alloy.
                        .requestMatchers(req -> managementPort > 0 && req.getLocalPort() == managementPort).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/livez", "/readyz", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        // OAuth del servidor MCP: metadatos, registro de clientes y canje del código (ver OAuthController).
                        .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp",
                                "/.well-known/oauth-authorization-server").permitAll()
                        .requestMatchers(HttpMethod.POST, "/oauth/register", "/oauth/token").permitAll()
                        // La pantalla de permiso: personas con su sesión (el servicio exige edición para aprobar).
                        .requestMatchers("/api/oauth/**").hasRole("HUMAN")
                        // Avisos: cada persona sus preferencias (también quien solo mira). El webhook de Telegram valida su secreto.
                        .requestMatchers(HttpMethod.POST, "/api/telegram/webhook", "/api/telegram/webhook/*").permitAll()
                        .requestMatchers("/api/workspace/telegram", "/api/workspace/telegram/**").access(HUMAN_ADMIN)
                        .requestMatchers("/api/notifications", "/api/notifications/**").hasRole("HUMAN")
                        // Servidor MCP del tablero: solo con la conexión de agentes locales de una persona.
                        .requestMatchers("/mcp").hasRole("RUNNER")
                        // Runner: emparejarse es público (el código lo aprueba una persona que edita con su sesión); sus
                        // máquinas las administra cada persona; el resto lo usa solo el runner con su conexión.
                        .requestMatchers(HttpMethod.POST, "/api/runner/pair", "/api/runner/pair/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/runner/pair/approve").access(HUMAN_EDITOR)
                        .requestMatchers("/api/runner/machines", "/api/runner/machines/**").hasRole("HUMAN")
                        .requestMatchers("/api/runner/**").hasRole("RUNNER")
                        .requestMatchers(HttpMethod.GET, "/api/agent-kit", "/api/agent-kit.zip").authenticated()
                        // Lo único que escribe quien solo mira: su sesión, su clave, comentarios y las puertas donde
                        // su rol aprueba (el servicio comprueba el rol aprobador).
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout", "/api/issues/*/review", "/api/issues/*/comments").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/auth/password").authenticated()
                        // Corregir o borrar un comentario propio (también quien solo mira): el servicio comprueba el autor.
                        .requestMatchers(HttpMethod.PUT, "/api/issues/*/comments/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/issues/*/comments/*").authenticated()
                        // Reintentar un trabajo de agente: personas que editan.
                        .requestMatchers(HttpMethod.POST, "/api/agents/runs/*/retry").access(HUMAN_EDITOR)
                        // Crear un tablero: personas que editan. Sumar personas o agentes al equipo, emitir tokens de agentes,
                        // borrar o restaurar un tablero: solo quien administra.
                        .requestMatchers(HttpMethod.POST, "/api/projects").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/members", "/api/members/*/tokens").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*").access(HUMAN_ADMIN)
                        // Quién está en cada tablero: lo ve quien está en él; sumar o quitar personas, solo quien administra.
                        .requestMatchers(HttpMethod.POST, "/api/projects/*/people").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*/people/*").access(HUMAN_ADMIN)
                        // Qué agentes trabajan en un tablero: personas que editan. Crear agentes, su perfil y la clave de IA: quien administra.
                        .requestMatchers(HttpMethod.POST, "/api/projects/*/team/agents").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/projects/*/team").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*/team/*").access(HUMAN_EDITOR)
                        // El estado de la IA (sin la clave) lo ven quienes configuran tableros; cambiarla, solo quien administra.
                        .requestMatchers(HttpMethod.GET, "/api/workspace/ai").access(HUMAN_EDITOR)
                        .requestMatchers("/api/agents/*/profile", "/api/agents/*/prompt-preview", "/api/workspace/ai", "/api/workspace/ai/**").access(HUMAN_ADMIN)
                        // Redactar documentos base con IA: personas que editan (el paso «approve» incluido).
                        .requestMatchers("/api/projects/*/drafting", "/api/projects/*/drafting/**", "/api/drafting/**").access(HUMAN_EDITOR)
                        // Rutinas: el webhook es público (lleva su secreto); configurarlas, personas que editan.
                        .requestMatchers(HttpMethod.POST, "/api/routines/*/hook").permitAll()
                        // ERP de demostración: su propia clave (X-API-Key) y datos de ejemplo.
                        .requestMatchers("/demo-erp/**").permitAll()
                        .requestMatchers("/api/routines/**", "/api/projects/*/routines").access(HUMAN_EDITOR)
                        // Bandeja de correo: usa los buzones de la empresa, así que la configura quien administra.
                        .requestMatchers("/api/inboxes", "/api/inboxes/**", "/api/projects/*/inboxes").access(HUMAN_ADMIN)
                        // El dashboard: cómo va la empresa y su ajuste, quien administra; «mi trabajo», cada persona.
                        .requestMatchers("/api/insights/company", "/api/insights/settings").access(HUMAN_ADMIN)
                        .requestMatchers("/api/insights/**").hasRole("HUMAN")
                        // Diseñar un tablero con IA guarda una plantilla en el catálogo: solo quien administra.
                        .requestMatchers("/api/boards/design", "/api/boards/design/**").access(HUMAN_ADMIN)
                        // Conexiones del negocio (sistemas de la empresa que usan los agentes): solo quien administra.
                        .requestMatchers("/api/connections", "/api/connections/**", "/api/connection-logins", "/api/connection-directory", "/api/connection-directory/**", "/api/projects/*/connections").access(HUMAN_ADMIN)
                        // La auditoría del espacio: solo quien administra.
                        .requestMatchers("/api/workspace/audit", "/api/workspace/audit/**").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/workspace", "/api/workspace/languages", "/api/members/*/access").access(HUMAN_ADMIN)
                        // Columnas, etapas (puertas y aprobadores) y transiciones definen quién aprueba: solo quien administra.
                        .requestMatchers(HttpMethod.PUT, "/api/projects/*/columns", "/api/projects/*/transitions", "/api/projects/*/stages", "/api/projects/*/capabilities", "/api/projects/*/card").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/projects/*", "/api/projects/*/methodology", "/api/projects/*/goal").access(HUMAN_EDITOR)
                        // El catálogo lo lee cualquiera del espacio; solo quien administra lo cambia.
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/**").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/catalog/**").access(HUMAN_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/catalog/**").access(HUMAN_ADMIN)
                        // Repositorios, sincronizar (tokens de GitHub, disco del servidor) y las conexiones de GitHub: las personas
                        // que editan los ven; conectarlos, cambiarlos o sincronizar, solo quien administra.
                        .requestMatchers(HttpMethod.GET, "/api/projects/*/repository", "/api/projects/*/repository/**", "/api/projects/*/components",
                                "/api/projects/*/components/**", "/api/projects/*/sync", "/api/projects/*/sync/**", "/api/github", "/api/github/**").access(HUMAN_EDITOR)
                        .requestMatchers("/api/projects/*/repository", "/api/projects/*/repository/**", "/api/projects/*/components", "/api/projects/*/components/**",
                                "/api/projects/*/sync", "/api/projects/*/sync/**", "/api/github", "/api/github/**").access(HUMAN_ADMIN)
                        .requestMatchers("/api/auth/accounts", "/api/auth/tokens/**").access(HUMAN_ADMIN)
                        // La conexión de agentes locales es de cada persona que edita.
                        .requestMatchers("/api/auth/runner-tokens", "/api/auth/runner-tokens/**").access(HUMAN_EDITOR)
                        // Cualquier otra escritura exige acceso de edición (personas o agentes).
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("EDITOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            // El cliente de IA descubre cómo iniciar sesión (RFC 9728) a partir de este encabezado.
                            if (req.getRequestURI().equals("/mcp")) {
                                res.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + publicUrl + "/.well-known/oauth-protected-resource/mcp\"");
                            }
                            problem(res, 401, "No autenticado", "Falta un token válido en Authorization: Bearer.");
                        })
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Sin permiso", deniedReason())))
                .addFilterBefore(new BearerTokenFilter(auth, platform), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static String deniedReason() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedMember who)) return "Esta acción está reservada a personas del equipo.";
        if (who.viewOnly()) return "Tu acceso a este espacio es de solo lectura.";
        if (who.agent() || who.runner()) return "Esta acción está reservada a personas del equipo.";
        if (who.administers()) return "Esta acción está reservada al dueño del espacio.";
        return "Esta acción está reservada a quien administra el espacio.";
    }

    private static void problem(HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}".formatted(status, title, detail));
    }
}
