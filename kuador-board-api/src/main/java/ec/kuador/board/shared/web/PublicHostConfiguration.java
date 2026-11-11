package ec.kuador.board.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Activa {@link PublicHostGate} cuando la API se publica con un dominio propio (BOARD_PUBLIC_HOST_GATE=true). */
@Configuration
@ConditionalOnProperty(name = "board.public-host-gate", havingValue = "true")
class PublicHostConfiguration {

    @Bean
    FilterRegistrationBean<PublicHostGate> publicHostGate(@Value("${board.public-url}") String publicUrl) {
        FilterRegistrationBean<PublicHostGate> registration = new FilterRegistrationBean<>(new PublicHostGate(publicUrl));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
