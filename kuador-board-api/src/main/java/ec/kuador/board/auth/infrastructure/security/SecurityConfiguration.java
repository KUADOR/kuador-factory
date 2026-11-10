package ec.kuador.board.auth.infrastructure.security;

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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Reglas de acceso. Todo exige token salvo el login, health y la documentación. Gestionar el
 * equipo, los accesos y los tokens queda reservado a personas: un agente no puede crear agentes
 * ni emitirse tokens.
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
class SecurityConfiguration {

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
                        .requestMatchers(HttpMethod.POST, "/api/projects", "/api/members", "/api/members/*/tokens").hasRole("HUMAN")
                        .requestMatchers(HttpMethod.PUT, "/api/projects/*", "/api/projects/*/columns", "/api/projects/*/methodology", "/api/projects/*/transitions", "/api/projects/*/stages", "/api/workspace").hasRole("HUMAN")
                        // El catálogo lo lee cualquiera del espacio; solo personas lo editan.
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/**").hasRole("HUMAN")
                        .requestMatchers(HttpMethod.POST, "/api/catalog/**").hasRole("HUMAN")
                        .requestMatchers(HttpMethod.DELETE, "/api/catalog/**").hasRole("HUMAN")
                        // Sincronizar lee y escribe en el disco del servidor: solo personas.
                        .requestMatchers("/api/projects/*/repository", "/api/projects/*/sync", "/api/projects/*/sync/**").hasRole("HUMAN")
                        .requestMatchers("/api/members/*/credentials", "/api/auth/accounts", "/api/auth/tokens/**",
                                "/api/auth/password").hasRole("HUMAN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "No autenticado",
                                "Falta un token válido en Authorization: Bearer."))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Sin permiso",
                                "Esta acción está reservada a personas del equipo.")))
                .addFilterBefore(new BearerTokenFilter(auth), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void problem(HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}".formatted(status, title, detail));
    }
}
