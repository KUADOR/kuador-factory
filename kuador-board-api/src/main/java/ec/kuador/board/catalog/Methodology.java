package ec.kuador.board.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Metodología: qué documentos pide y cómo deben trabajar los agentes. */
public record Methodology(
        UUID id, String key, String name, String summary, List<String> agentRules, List<DocumentDef> documents, boolean builtIn,
        long version) {

    /** El documento del tipo: de la metodología o, si no lo trae, uno propio del tablero (Despliegue). */
    public Optional<DocumentDef> document(String kind) {
        return documents.stream().filter(d -> d.kind().equals(kind)).findFirst().or(() -> builtIn(kind));
    }

    /** Los de la metodología y, en los del proyecto, también Despliegue (lo trae el tablero si la metodología no). */
    public List<DocumentDef> documents(DocumentDef.Scope scope) {
        List<DocumentDef> own = new ArrayList<>(documents.stream().filter(d -> d.scope() == scope).toList());
        if (scope == DocumentDef.Scope.PROJECT && own.stream().noneMatch(d -> d.kind().equals(DEPLOYMENT_KIND))) own.add(DEPLOYMENT);
        return List.copyOf(own);
    }

    /** Lo mínimo de ODD: el resto de sus documentos se escribe cuando hace falta. */
    public static final List<String> ODD_MINIMUM = List.of("PRODUCT", "AGENT_RULES");

    /**
     * Los documentos base: se redactan al empezar un proyecto nuevo y sin ellos no se crea el repo (van en su primer
     * commit y guían a los agentes). En ODD, lo mínimo; en las demás, todos los del proyecto. Despliegue no: es opcional
     * y crece con el proyecto.
     */
    public List<DocumentDef> baseDocuments() {
        return documents(DocumentDef.Scope.PROJECT).stream()
                .filter(d -> !d.kind().equals(DEPLOYMENT_KIND))
                .filter(d -> !"ODD".equals(key) || ODD_MINIMUM.contains(d.kind())).toList();
    }

    // ---- Documentos propios del tablero ----

    public static final String DEPLOYMENT_KIND = "DEPLOYMENT";

    /**
     * Despliegue: documento vivo del proyecto, en cualquier metodología. Dice dónde y cómo se despliega cada entorno
     * (develop → pruebas, main → producción), con sus dependencias, variables y secretos. Cada PR que cambia el despliegue
     * (p. ej. sumar Grafana o Keycloak) deja su nota en {@link AgentRules#DEPLOYMENT_CHANGES} y el release las pasa aquí. Vive en el código y no en el catálogo para que llegue
     * también a los espacios que ya existían.
     */
    public static final DocumentDef DEPLOYMENT = new DocumentDef(DEPLOYMENT_KIND, "Despliegue", DocumentDef.Scope.PROJECT, false,
            "Dónde y cómo se despliega cada entorno, con sus dependencias, variables y secretos. Se actualiza con cada cambio de despliegue.",
            "docs/deployment.md", """
                    # Despliegue de {project}

                    Documento vivo. Cada Pull Request que cambia cómo se despliega deja su nota en `docs/deployment/changes/<tarjeta>.md`;
                    al preparar cada release esas notas se pasan aquí (y a «Cambios») y se borran.

                    ## Entornos

                    | Entorno | Rama | Dónde corre | URL |
                    |---|---|---|---|
                    | Pruebas | develop | [servidor o plataforma] | [https://…] |
                    | Producción | main | [servidor o plataforma] | [https://…] |

                    ## Infraestructura

                    [Servidor (proveedor, sistema operativo, tamaño), plataforma (Dokploy, Docker Compose…), proxy y certificados.]

                    ## Piezas

                    | Pieza | Cómo se construye | Puerto | Dominio por entorno |
                    |---|---|---|---|
                    | [pieza] | [imagen Docker o build] | [puerto] | [pruebas / producción] |

                    ## Dependencias

                    | Servicio | Versión | Para qué | Cómo se levanta | Datos que guarda |
                    |---|---|---|---|---|
                    | [ej. PostgreSQL] | [18] | [qué pieza lo usa] | [contenedor, servicio administrado] | [volumen y respaldo] |

                    ## Variables y secretos

                    Solo nombres: los valores nunca van en este documento ni en el repo.

                    | Nombre | Pieza | Entornos | Dónde vive | Secreto |
                    |---|---|---|---|---|
                    | [NOMBRE] | [pieza] | [pruebas, producción] | [Dokploy, secretos de GitHub…] | [sí / no] |

                    ## Paso a paso

                    ### Primera vez

                    1. [Preparar el servidor]
                    2. [Crear las aplicaciones y dependencias]
                    3. [Cargar variables y secretos]
                    4. [Primer despliegue y verificación]

                    ### Cada despliegue

                    [Qué pasa al fusionar en develop y en main.]

                    ## CI/CD

                    [Workflow que construye, publica y despliega cada rama en su entorno.]

                    ## Respaldos y vuelta atrás

                    [Qué se respalda, cada cuánto, dónde, y cómo volver a la versión anterior.]

                    ## Verificación

                    [Cómo comprobar que cada entorno quedó bien: salud, logs, una prueba de punta a punta.]

                    ## Cambios

                    | Fecha | Qué cambió | Tarjeta |
                    |---|---|---|
                    | {date} | Primera versión | — |
                    """,
            "DEVOPS", DocumentDef.Condition.ALWAYS,
            "Documento vivo del despliegue. Básate en las piezas del proyecto y en lo que dice la persona sobre su infraestructura; lo que no sepas,"
                    + " márcalo [POR DEFINIR]. develop despliega a pruebas y main a producción. Nunca escribas valores de secretos: solo sus nombres y"
                    + " dónde viven. Al redactarlo o actualizarlo, incorpora las notas pendientes de " + AgentRules.DEPLOYMENT_CHANGES + " (una fila por"
                    + " tarjeta en «Cambios»). Un cambio de una tarjeta no edita este documento: deja su nota en " + AgentRules.DEPLOYMENT_CHANGES
                    + "<clave>.md, para que los PR en paralelo no choquen.");

    private static Optional<DocumentDef> builtIn(String kind) {
        return DEPLOYMENT_KIND.equals(kind) ? Optional.of(DEPLOYMENT) : Optional.empty();
    }
}
