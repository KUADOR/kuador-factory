package ec.kuador.board.auth.infrastructure.security;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.application.AuthService;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
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
 * Reglas de acceso. Todo exige token salvo el login, health y la documentación. Gestionar el
 * equipo, los accesos y los tokens queda reservado a personas que editan: un agente no puede
 * crear agentes ni emitirse tokens. Quien tiene acceso de solo lectura (VIEW) solo lee, salvo
 * cerrar las puertas donde su rol aprueba.
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
class SecurityConfiguration {

    /** Persona con acceso de edición: administra el equipo, el catálogo, los proyectos y los repositorios. */
    private static final AuthorizationManager<RequestAuthorizationContext> HUMAN_EDITOR =
            AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasRole("HUMAN"), AuthorityAuthorizationManager.hasRole("EDITOR"));

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthService auth, ec.kuador.board.auth.application.PlatformAdmins platform,
            @org.springframework.beans.factory.annotation.Value("${board.public-url:http://localhost:8091}") String configuredPublicUrl) throws Exception {
        String publicUrl = configuredPublicUrl.replaceAll("/+$", "");
        http.csrf(c -> c.disable()) // API sin cookies: el token va en un header que el navegador no envía solo.
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Alta pública de espacios (modo SaaS): verificación por correo, ver SignupService.
                        .requestMatchers("/api/signup/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password-reset", "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/plans").permitAll()
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM")
                        .requestMatchers(HttpMethod.PUT, "/api/workspace/plan").access(HUMAN_EDITOR)
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        // OAuth del servidor MCP: metadatos, registro de clientes y canje del código (ver OAuthController).
                        .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp",
                                "/.well-known/oauth-authorization-server").permitAll()
                        .requestMatchers(HttpMethod.POST, "/oauth/register", "/oauth/token").permitAll()
                        // La pantalla de permiso: personas con su sesión (el servicio exige edición para aprobar).
                        .requestMatchers("/api/oauth/**").hasRole("HUMAN")
                        // Avisos: cada persona sus preferencias (también quien solo mira). El webhook de Telegram valida su secreto.
                        .requestMatchers(HttpMethod.POST, "/api/telegram/webhook").permitAll()
                        .requestMatchers("/api/notifications", "/api/notifications/**").hasRole("HUMAN")
                        // Servidor MCP del tablero: solo con la conexión de agentes locales de una persona.
                        .requestMatchers("/mcp").hasRole("RUNNER")
                        .requestMatchers(HttpMethod.GET, "/api/agent-kit", "/api/agent-kit.zip").authenticated()
                        // Lo único que escribe quien solo mira: su sesión, su clave, comentarios y las puertas donde
                        // su rol aprueba (el servicio comprueba el rol aprobador).
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout", "/api/issues/*/review", "/api/issues/*/comments").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/projects", "/api/members", "/api/members/*/tokens").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.PUT, "/api/projects/*", "/api/projects/*/columns", "/api/projects/*/methodology",
                                "/api/projects/*/transitions", "/api/projects/*/stages", "/api/workspace", "/api/workspace/languages", "/api/members/*/access").access(HUMAN_EDITOR)
                        // El catálogo lo lee cualquiera del espacio; solo personas que editan lo cambian.
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/**").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/catalog/**").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.DELETE, "/api/catalog/**").access(HUMAN_EDITOR)
                        // Sincronizar lee y escribe en el disco del servidor: solo personas que editan.
                        .requestMatchers("/api/projects/*/repository", "/api/projects/*/repository/**", "/api/projects/*/components", "/api/projects/*/components/**",
                                "/api/projects/*/sync", "/api/projects/*/sync/**").access(HUMAN_EDITOR)
                        .requestMatchers("/api/members/*/credentials", "/api/auth/accounts", "/api/auth/tokens/**", "/api/auth/runner-tokens", "/api/auth/runner-tokens/**",
                                "/api/github", "/api/github/**").access(HUMAN_EDITOR)
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
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Sin permiso", viewOnly()
                                ? "Tu acceso a este espacio es de solo lectura."
                                : "Esta acción está reservada a personas del equipo.")))
                .addFilterBefore(new BearerTokenFilter(auth, platform), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static boolean viewOnly() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedMember who && who.viewOnly();
    }

    private static void problem(HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}".formatted(status, title, detail));
    }
}
