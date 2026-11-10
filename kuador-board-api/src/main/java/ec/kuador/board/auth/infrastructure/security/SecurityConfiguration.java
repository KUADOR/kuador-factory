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
    SecurityFilterChain api(HttpSecurity http, AuthService auth) throws Exception {
        http.csrf(c -> c.disable()) // API sin cookies: el token va en un header que el navegador no envía solo.
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Alta pública de espacios (modo SaaS): verificación por correo, ver SignupService.
                        .requestMatchers("/api/signup/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        // Servidor MCP del tablero: solo con la conexión de Claude Code de una persona.
                        .requestMatchers("/mcp").hasRole("RUNNER")
                        .requestMatchers(HttpMethod.GET, "/api/claude-kit", "/api/claude-kit.zip").authenticated()
                        // Lo único que escribe quien solo mira: su sesión, su clave, comentarios y las puertas donde
                        // su rol aprueba (el servicio comprueba el rol aprobador).
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout", "/api/issues/*/review", "/api/issues/*/comments").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/projects", "/api/members", "/api/members/*/tokens").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.PUT, "/api/projects/*", "/api/projects/*/columns", "/api/projects/*/methodology",
                                "/api/projects/*/transitions", "/api/projects/*/stages", "/api/workspace", "/api/members/*/access").access(HUMAN_EDITOR)
                        // El catálogo lo lee cualquiera del espacio; solo personas que editan lo cambian.
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/**").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/catalog/**").access(HUMAN_EDITOR)
                        .requestMatchers(HttpMethod.DELETE, "/api/catalog/**").access(HUMAN_EDITOR)
                        // Sincronizar lee y escribe en el disco del servidor: solo personas que editan.
                        .requestMatchers("/api/projects/*/repository", "/api/projects/*/sync", "/api/projects/*/sync/**").access(HUMAN_EDITOR)
                        .requestMatchers("/api/members/*/credentials", "/api/auth/accounts", "/api/auth/tokens/**", "/api/auth/runner-tokens", "/api/auth/runner-tokens/**").access(HUMAN_EDITOR)
                        // Cualquier otra escritura exige acceso de edición (personas o agentes).
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("EDITOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("EDITOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "No autenticado",
                                "Falta un token válido en Authorization: Bearer."))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Sin permiso", viewOnly()
                                ? "Tu acceso a este espacio es de solo lectura."
                                : "Esta acción está reservada a personas del equipo.")))
                .addFilterBefore(new BearerTokenFilter(auth), AnonymousAuthenticationFilter.class);
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
