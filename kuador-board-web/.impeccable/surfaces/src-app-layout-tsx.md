---
version: 1
slug: "src-app-layout-tsx"
primary_target: "src/app/layout.tsx"
related_targets: ["src/app"]
---

# Kuador Factory · app completa (Operate)

Alcance: todo el tablero (shell, proyectos, tablero, detalle de issue, documentos, sincronización, actividad, configuración de proyecto, equipo, catálogo del espacio, cuenta, login). Modo: Operate. Construcción: code-led.

Audiencia y tarea: equipo Kuador y clientes en sus espacios; mover y refinar issues, leer y aprobar documentos, ver el trabajo de los agentes, configurar el catálogo. Restricciones: PrimeReact 11 + tema Kuador por tokens; oscuro por defecto, claro opcional; densidad compacta; español.

Dirección fijada por el usuario: estándar de la categoría a nivel Jira / Linear / plantillas PrimeReact, con la identidad Kuador.

## Direction contract

THESIS: Un tracker de trabajo serio donde los agentes son compañeros visibles del equipo y las aprobaciones humanas se notan; rechaza el panel oscuro «hecho por IA» (brillos, degradados, tarjetas dentro de tarjetas, rótulos sobre títulos).

OWN-WORLD: Noche Kuador (fondo #0a0e14, superficie #10151d, elevada #151c26, líneas #1e2833/#2a3644); un único sol #ffb81c solo para acción primaria, selección y foco. Archivo como única familia UI (13–14 px), Martian Mono solo para claves (KUA-12), rutas y números tabulares. Componentes PrimeReact con radio 6 px, bordes de 1 px, sombras solo en capas flotantes. Tema claro: papel neutro frío con la misma estructura y sol oscurecido para texto.

STORY: Al entrar se sabe en qué espacio y proyecto se está, qué está en curso, qué hace cada agente y qué espera una aprobación humana; se actúa sin modales innecesarios.

FIRST VIEWPORT: Barra lateral fija 240 px (espacio de trabajo, proyectos con su clave, Equipo, Catálogo; al pie, persona, tema y salir). Sin barra superior global: las migas van en la cabecera de cada página y la búsqueda en el tablero (la paleta ⌘K queda para una entrega posterior); en móvil, una barra mínima abre la navegación. En el proyecto: cabecera con nombre y pestañas Tablero · Documentos · Repositorio · Actividad · Configuración; fila de filtros (buscar, avatares de responsables, tipo) y «Crear» primario a la derecha; columnas compactas de 288 px con contador y punto de categoría. El detalle del issue se abre en panel lateral derecho, no en modal. Movimiento distintivo: presencia de agentes — avatar cuadrado redondeado con chispa para agentes vs círculo para personas, y una línea de estado viva («Agente PO trabajando…») en tarjeta y detalle. Las puertas de aprobación humana (candado) llegan con la Fase 1 (etapas y aprobaciones); esta entrega no las muestra.

FORM: canon fijado por el usuario (Jira, Linear, plantillas PrimeReact), sin ronda de dados. seed: none (unavailable)

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
