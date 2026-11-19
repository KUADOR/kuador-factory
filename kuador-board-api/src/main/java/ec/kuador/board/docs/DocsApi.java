package ec.kuador.board.docs;

import java.util.List;
import java.util.UUID;

/** API pública de documentos para otros módulos (sincronización con repositorios, agentes). */
public interface DocsApi {

    /** Documento con el contenido de su versión actual. {@code issueKey} nulo = del proyecto. */
    record DocContent(UUID id, String projectKey, String issueKey, String kind, String title, String path, int version, String content) {}

    /** El tablero de un documento (vacío si no existe). */
    java.util.Optional<String> projectOf(UUID documentId);

    /** Documentos del proyecto y de sus issues. */
    List<DocContent> ofProject(String projectKey);

    /** Guarda {@code content} como versión nueva (si cambió), con una nota que indica su origen. */
    DocContent saveFromRepository(UUID documentId, int baseVersion, String content, String note, UUID actorId);

    /** Crea un documento con el contenido de un archivo del repositorio, conservando su ruta real. */
    DocContent createFromRepository(String projectKey, String kind, String issueKey, String path, String content, UUID actorId);

    /** Tipos de documento que pide la metodología actual del proyecto (vacío si no tiene). */
    List<String> allowedKinds(String projectKey);

    /** Metodología del proyecto para el prompt de un agente: nombre, resumen y reglas. Vacío si no tiene. */
    record MethodologyRules(String methodology, String name, String summary, List<String> rules) {}

    java.util.Optional<MethodologyRules> methodologyRules(String projectKey);

    /**
     * Crea desde su plantilla los documentos del proyecto (no de issue) que pide la metodología y aún no
     * existen. Devuelve todos los documentos del proyecto, con su ruta en el repo.
     */
    List<DocContent> ensureProjectDocuments(String projectKey, UUID actorId);

    /** Nota de la versión que escribe el tablero al reescribir el stack desde las piezas: no cuenta como redacción de una persona. */
    String STACK_NOTE = "El tablero actualizó el stack desde las piezas";

    /** Los documentos base de la metodología que siguen sin redactar (no existen o solo tienen la plantilla), por su nombre. */
    List<String> pendingBaseDocuments(String projectKey);

    /** Crea el documento del issue con {@code content} o guarda una versión nueva si ya existe. */
    DocContent write(String projectKey, String issueKey, String kind, String content, String note, UUID actorId);
}
