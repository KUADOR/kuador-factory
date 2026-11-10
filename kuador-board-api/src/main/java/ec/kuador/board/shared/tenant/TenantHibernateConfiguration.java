package ec.kuador.board.shared.tenant;

import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

/** Conecta {@link TenantContext} con el filtro por espacio de Hibernate ({@code @TenantId}). */
@Configuration
class TenantHibernateConfiguration implements HibernatePropertiesCustomizer, CurrentTenantIdentifierResolver<UUID> {

    @Override
    public void customize(Map<String, Object> properties) {
        properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.ROOT.equals(tenantId);
    }
}
