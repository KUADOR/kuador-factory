package ec.kuador.board.catalog;

/**
 * Puerto que implementan los módulos que usan el catálogo, para impedir borrar algo en uso sin que
 * el catálogo dependa de ellos. Una implementación responde por lo suyo y 0 por lo demás.
 */
public interface CatalogUsage {

    long projectsUsingMethodology(String methodologyKey);

    long membersWithRole(String roleKey);

    /** Piezas de tableros armadas con esta plantilla de pieza. */
    default long componentsUsingTemplate(String templateKey) {
        return 0;
    }

    /** Tableros que nacieron de esta plantilla de flujo (son copias: borrarla no los cambia). */
    default long projectsFromTemplate(String templateKey) {
        return 0;
    }
}
