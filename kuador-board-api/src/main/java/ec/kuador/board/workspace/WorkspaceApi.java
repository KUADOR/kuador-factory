package ec.kuador.board.workspace;

/** API pública del módulo: identidad del espacio de trabajo actual. */
public interface WorkspaceApi {

    /**
     * Identificador (slug), nombre visible de la empresa e idiomas: {@code teamLanguage} el de las
     * personas y los documentos, {@code codeLanguage} el del código (códigos ISO 639-1).
     */
    record Identity(String slug, String name, String teamLanguage, String codeLanguage) {

        public Identity(String slug, String name) {
            this(slug, name, "es", "en");
        }
    }

    Identity identity();
}
