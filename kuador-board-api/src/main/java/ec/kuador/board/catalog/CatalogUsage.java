package ec.kuador.board.catalog;

/**
 * Puerto que implementan los módulos que usan el catálogo, para impedir borrar algo en uso sin que
 * el catálogo dependa de ellos. Una implementación responde por lo suyo y 0 por lo demás.
 */
public interface CatalogUsage {

    long projectsUsingMethodology(String methodologyKey);

    long membersWithRole(String roleKey);
}
