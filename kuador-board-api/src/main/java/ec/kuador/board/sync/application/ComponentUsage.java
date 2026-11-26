package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.sync.domain.ComponentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Le dice al catálogo cuántas piezas de tableros salieron de una plantilla de pieza: una en uso no se borra. */
@Component
class ComponentUsage implements CatalogUsage {

    private final ComponentRepository components;

    ComponentUsage(ComponentRepository components) {
        this.components = components;
    }

    @Override
    public long projectsUsingMethodology(String methodologyKey) {
        return 0;
    }

    @Override
    public long membersWithRole(String roleKey) {
        return 0;
    }

    @Override
    @Transactional(readOnly = true)
    public long componentsUsingTemplate(String templateKey) {
        return components.countByTemplate(templateKey);
    }
}
