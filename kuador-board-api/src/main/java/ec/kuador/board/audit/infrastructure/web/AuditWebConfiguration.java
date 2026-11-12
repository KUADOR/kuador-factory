package ec.kuador.board.audit.infrastructure.web;

import ec.kuador.board.audit.application.AuditLog;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuditWebConfiguration {

    /** Antes que la cadena de seguridad (-100): ve también las peticiones que ella rechaza. */
    @Bean
    FilterRegistrationBean<AuditFilter> auditFilter(AuditLog audit) {
        FilterRegistrationBean<AuditFilter> bean = new FilterRegistrationBean<>(new AuditFilter(audit));
        bean.setOrder(-101);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
