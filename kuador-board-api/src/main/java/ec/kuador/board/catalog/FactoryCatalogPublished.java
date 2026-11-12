package ec.kuador.board.catalog;

import java.util.List;

/**
 * Se publicó una versión del catálogo de la plataforma. Los espacios reciben lo agregado y, en las entradas
 * actualizadas, la versión nueva si no habían modificado la anterior ({@code previousPayload}).
 */
public record FactoryCatalogPublished(int version, List<Update> updates) {

    /** {@code type}: el nombre del tipo de catálogo (p. ej. AGENT_ROLE). */
    public record Update(String type, String key, String previousPayload) {}
}
