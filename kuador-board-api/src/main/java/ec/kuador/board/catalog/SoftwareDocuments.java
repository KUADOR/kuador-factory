package ec.kuador.board.catalog;

import java.util.Set;

/**
 * Documentos que trae el tablero de software aunque la metodología no los pida, como Despliegue: viven en el código y
 * no en el catálogo para que lleguen también a los espacios que ya existían.
 *
 * <ul>
 *   <li>Identidad visual (del proyecto, la escribe UI/UX): la marca y el sistema de diseño que siguen las pantallas.
 *   <li>Diseño de la solución (de la tarjeta, lo escribe el Arquitecto): solo si la metodología no le da un documento
 *       propio (en SDD es el plan). En las épicas lleva el diagrama Mermaid de la solución.
 *   <li>Pantallas (de la tarjeta, las escribe UI/UX): el flujo y los wireframes de una épica con interfaz.
 * </ul>
 */
public final class SoftwareDocuments {

    public static final String IDENTITY_KIND = "IDENTITY";
    public static final String SOLUTION_KIND = "SOLUTION";
    public static final String SCREENS_KIND = "SCREENS";

    /** Roles que escriben los documentos de tarjeta propios del tablero. */
    public static final String ARCHITECT = "ARCHITECT";
    public static final String UX_UI = "UX_UI";

    private SoftwareDocuments() {}

    /** Los que solo tienen sentido en un tablero con repositorio de código (Despliegue incluido). */
    public static boolean softwareOnly(String kind) {
        return Set.of(Methodology.DEPLOYMENT_KIND, IDENTITY_KIND, SOLUTION_KIND, SCREENS_KIND).contains(kind);
    }

    /** Documentos del proyecto que crecen con él: no son base (no frenan la creación del repo). */
    public static boolean growsWithProject(String kind) {
        return Methodology.DEPLOYMENT_KIND.equals(kind) || IDENTITY_KIND.equals(kind);
    }

    public static final DocumentDef IDENTITY = new DocumentDef(IDENTITY_KIND, "Identidad visual", DocumentDef.Scope.PROJECT, false,
            "La marca y el sistema de diseño del producto: personalidad, colores, tipografía, medidas y componentes. Toda pantalla lo sigue.",
            "DESIGN.md", """
                    # Identidad visual de {project}

                    Lo que siguen todas las pantallas. Un cambio aquí se propone en una tarjeta y lo aprueba una persona.

                    ## Personalidad

                    - **Es**: [tres adjetivos]
                    - **No es**: [lo que la marca evita]

                    ## Referencias

                    | Referencia | Qué se toma de ella |
                    |---|---|
                    | [imagen, captura o enlace de Figma] | [colores, densidad, tono…] |

                    ## Color

                    | Token | Valor | Uso | Contraste |
                    |---|---|---|---|
                    | primary | [#000000] | [acciones principales] | [AA sobre fondo] |
                    | accent | [#000000] | [resaltar] | |
                    | surface / fg | [#FFFFFF / #111111] | [fondo y texto] | |
                    | success / warning / danger | [#… / #… / #…] | [estados] | |

                    ## Tipografía

                    [Familia, escala (p. ej. 12 · 14 · 16 · 20 · 24 · 32), pesos y alto de línea.]

                    ## Medidas

                    [Espaciado (múltiplos de 4), radios, sombras y anchos máximos.]

                    ## Componentes base

                    | Componente | Variantes | Estados |
                    |---|---|---|
                    | Botón | [primario, secundario, peligro] | [normal, foco, deshabilitado, cargando] |
                    | Campo | [texto, selección] | [vacío, error, deshabilitado] |
                    | Tarjeta, tabla, aviso, navegación | […] | […] |

                    ## Iconos y textos

                    [Juego de iconos, tono de los textos y cómo se escriben los errores.]

                    ## Accesibilidad

                    Contraste AA, foco visible, todo usable con teclado y etiquetas para lectores de pantalla.
                    """,
            UX_UI, DocumentDef.Condition.ALWAYS,
            "Documento vivo de la identidad. Básate en las referencias que dio la persona (imágenes, capturas, PDF de Figma), el logo y el color del"
                    + " espacio, el objetivo y el producto; verifica el contraste AA de cada color de texto. Si no hay referencias, propón una identidad sobria"
                    + " y márcala como propuesta para que una persona la apruebe. Nunca copies marcas ni diseños de terceros.");

    public static final DocumentDef SOLUTION = new DocumentDef(SOLUTION_KIND, "Diseño de la solución", DocumentDef.Scope.ISSUE, false,
            "El cómo de la tarjeta: módulos, contratos, datos, decisiones y, en las épicas, el diagrama de la solución.",
            "docs/solution/{task}.md", """
                    # Diseño: {title}

                    **Issue**: {key} | **Fecha**: {date}

                    ## Resumen

                    ## Diagrama de la solución

                    [En épicas, obligatorio: un bloque de código Mermaid con la solución completa, lo nuevo marcado.]

                    ## Módulos y contratos

                    ## Datos

                    ## Decisiones

                    | Decisión | Alternativa descartada | Por qué |
                    |---|---|---|

                    ## Riesgos
                    """,
            ARCHITECT, DocumentDef.Condition.SUBSTANTIAL,
            "Escribe el diseño en Markdown: «## Resumen», «## Diagrama de la solución» (en épicas, obligatorio: un bloque ```mermaid de la solución"
                    + " completa), «## Módulos y contratos», «## Datos», «## Decisiones» (con la alternativa descartada) y «## Riesgos». No cambies el"
                    + " alcance de lo aprobado.");

    public static final DocumentDef SCREENS = new DocumentDef(SCREENS_KIND, "Pantallas", DocumentDef.Scope.ISSUE, false,
            "El flujo de pantallas y los wireframes de una épica con interfaz, según la identidad visual.",
            "docs/screens/{task}.md", """
                    # Pantallas: {title}

                    **Issue**: {key} | **Fecha**: {date}

                    ## Flujo

                    [Un bloque de código Mermaid con el recorrido entre pantallas.]

                    ## Pantallas

                    ### [Nombre de la pantalla]

                    - **Para qué**: [qué hace la persona aquí]
                    - **Contenido**: [qué muestra, en orden]
                    - **Acciones**: [botones y qué pasa al usarlos]
                    - **Estados**: carga, vacío, error
                    - **Móvil**: [qué cambia]

                    ## Accesibilidad
                    """,
            UX_UI, DocumentDef.Condition.SUBSTANTIAL,
            "Escribe en Markdown «## Flujo» (un bloque ```mermaid con el recorrido entre pantallas), «## Pantallas» (una sección por pantalla nueva o"
                    + " cambiada: para qué, contenido, acciones, estados de carga, vacío y error, y qué cambia en móvil; adjunta su wireframe en SVG o PNG"
                    + " si puedes generarlo) y «## Accesibilidad». Todo según la Identidad visual. Si la tarjeta no tiene interfaz, escribe solo «Sin"
                    + " cambios de interfaz» y por qué.");
}
