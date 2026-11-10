---
name: Kuador Factory
description: Tablero de trabajo donde personas y agentes de IA mueven el mismo flujo; noche técnica con un solo sol.
colors:
  sol: "#ffb81c"
  sol-hover-dark: "#ffc53d"
  sol-hover-light: "#e09a00"
  sol-text-dark: "#ffc53d"
  sol-text-light: "#8a5a00"
  on-sol: "#0b1017"
  night-page: "#0a0e14"
  night-sunken: "#070a0f"
  night-surface: "#10151d"
  night-raised: "#151c26"
  night-hover: "#1e2833"
  night-line: "#1e2833"
  night-line-strong: "#2a3644"
  night-line-hover: "#4a5461"
  night-fg-strong: "#ffffff"
  night-fg: "#c6d2db"
  night-fg-muted: "#94a3af"
  night-fg-faint: "#75838f"
  paper-page: "#fafafa"
  paper-sunken: "#f4f4f5"
  paper-surface: "#ffffff"
  paper-hover: "#f4f4f5"
  paper-line: "#e7e7ea"
  paper-line-strong: "#d8d8dc"
  paper-line-hover: "#a8a8b0"
  paper-fg-strong: "#0c0c0e"
  paper-fg: "#2b2b30"
  paper-fg-muted: "#5a5a63"
  paper-fg-faint: "#6e6e78"
  ok-dark: "#34d399"
  ok-light: "#0e7a55"
  danger-dark: "#ff6b78"
  danger-light: "#c42b40"
  info-dark: "#8fb4ff"
  info-light: "#2f5fc4"
  warn-dark: "#ff8a4c"
  warn-light: "#b4501a"
  epic-dark: "#c4a7ff"
  epic-light: "#6f4bc9"
typography:
  headline:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  body-compact:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 400
    lineHeight: 1.375
  label:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.33
  caption:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.6875rem"
    fontWeight: 500
    lineHeight: 1.4
  mono-key:
    fontFamily: "Martian Mono, ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.6875rem"
    fontWeight: 400
    lineHeight: 1.4
    fontFeature: "tnum"
  prose:
    fontFamily: "Archivo, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 400
    lineHeight: 1.7
rounded:
  sm: "4px"
  md: "6px"
  lg: "8px"
  agent: "28%"
  full: "9999px"
spacing:
  xs: "6px"
  sm: "8px"
  md: "12px"
  lg: "20px"
  gutter: "24px"
  sidebar: "240px"
  column: "288px"
  content-max: "64rem"
components:
  button-primary:
    backgroundColor: "{colors.sol}"
    textColor: "{colors.on-sol}"
    rounded: "{rounded.md}"
  button-primary-hover-dark:
    backgroundColor: "{colors.sol-hover-dark}"
    textColor: "{colors.on-sol}"
  button-primary-hover-light:
    backgroundColor: "{colors.sol-hover-light}"
    textColor: "{colors.on-sol}"
  button-secondary:
    textColor: "{colors.night-fg}"
    rounded: "{rounded.md}"
  input:
    backgroundColor: "{colors.night-page}"
    textColor: "{colors.night-fg-strong}"
    rounded: "{rounded.md}"
    padding: "6px 10px"
  issue-card:
    backgroundColor: "{colors.night-raised}"
    textColor: "{colors.night-fg-strong}"
    rounded: "{rounded.md}"
    padding: "10px 12px"
  board-column:
    backgroundColor: "{colors.night-sunken}"
    rounded: "{rounded.lg}"
    width: "{spacing.column}"
  section:
    backgroundColor: "{colors.night-surface}"
    rounded: "{rounded.lg}"
    padding: "16px 20px"
  nav-item:
    textColor: "{colors.night-fg-muted}"
    rounded: "{rounded.md}"
    height: "32px"
    padding: "0 10px"
  nav-item-active:
    backgroundColor: "{colors.night-hover}"
    textColor: "{colors.night-fg-strong}"
  badge:
    rounded: "{rounded.sm}"
    padding: "1px 6px"
    typography: "{typography.caption}"
  avatar-person:
    rounded: "{rounded.full}"
    textColor: "{colors.on-sol}"
  avatar-agent:
    rounded: "{rounded.agent}"
    textColor: "{colors.on-sol}"
---

# Design System: Kuador Factory

## Overview

**Creative North Star: "La noche de turno con un solo sol"**

Kuador Factory es un tracker de trabajo serio, a la altura de Jira y Linear, donde los agentes de IA son compañeros visibles del equipo. La interfaz es una noche técnica: capas de azul pizarra casi negro, líneas finas de 1 px y una sola luz cálida, el sol ecuatorial `#ffb81c`, que solo aparece donde hay que actuar, donde algo está seleccionado o enfocado, y donde un agente está presente. Todo lo demás es neutro, compacto y legible en sesiones largas.

La densidad es de herramienta de trabajo: texto base de 14 px, filas de 13 px, columnas de 288 px, barra lateral fija de 240 px. El sistema se apoya en PrimeReact 11 con un preset propio (`src/theme/kuador-preset.ts`) y en los mismos tokens expuestos a Tailwind (`--k-*` en `src/app/globals.css`), de modo que los componentes Prime y la maquetación propia leen las mismas variables y nunca se desalinean. Un único preset sirve para los dos temas mediante `light-dark()`.

El oscuro es el tema por defecto; el claro es papel neutro (grises sin tinte) con la misma estructura y el sol oscurecido cuando es texto. El sistema rechaza el panel oscuro «hecho por IA»: nada de brillos, degradados, tarjetas dentro de tarjetas ni rótulos sobre títulos.

**Key Characteristics:**
- Un solo acento (el sol), reservado a acción primaria, selección, foco y presencia de agentes.
- Profundidad por capas tonales y líneas de 1 px; sombra solo en lo que flota.
- Archivo como única familia de interfaz; Martian Mono solo para claves, rutas y cifras.
- Personas y agentes se distinguen por la forma del avatar, no solo por un ícono.
- Detalle y creación en panel lateral derecho; el diálogo centrado es solo para confirmar lo destructivo.

## Colors

Una noche pizarra en capas con un único sol cálido; los colores semánticos son apagados y existen en par claro/oscuro.

### Primary
- **Sol ecuatorial** (`sol`): relleno del botón primario (una acción primaria por vista), indicador de pestaña activa, borde de la opción elegida en ChoiceCards, borde de la tarjeta al arrastrar y de la columna receptora, anillo de foco (2 px, desplazado 2 px), cursor de texto y selección de texto al 35 %. También marca la presencia de agentes: el punto pulsante de WorkingLine, la chispa del avatar de agente y los badges de automatización.
- **Sol como texto** (`sol-text-dark` / `sol-text-light`): el sol cuando es texto o ícono sobre la superficie (WorkingLine, «Automatizado», badge en tono sol). En claro se oscurece a ámbar profundo para cumplir AA.
- **Sobre el sol** (`on-sol`): texto e iconos sobre rellenos de sol y sobre los avatares de color.
- La escala completa del sol (`sol` 50–950 en el preset) alimenta los estados de PrimeReact: hover (600 en claro, 400 en oscuro), activo (700 / 300) y resaltado (fondo al 14 % y 22 % en oscuro).

### Neutral
- **Página nocturna** (`night-page`) / **Papel** (`paper-page`): fondo de página y de la cabecera de página. También el fondo de los campos de formulario en oscuro.
- **Hundido** (`night-sunken` / `paper-sunken`): barra lateral y columnas del tablero; lo que contiene tarjetas queda por debajo de la página.
- **Superficie** (`night-surface` / `paper-surface`): Section, paneles laterales, diálogos.
- **Elevado** (`night-raised` / `paper-surface`): tarjetas de issue, menús y desplegables.
- **Hover** (`night-hover` / `paper-hover`): fondo al pasar el puntero y del elemento de navegación actual.
- **Líneas** (`night-line`, `night-line-strong`, `night-line-hover` y sus pares `paper-*`): divisores, borde de tarjeta y borde al pasar el puntero, en ese orden.
- **Texto** (`*-fg-strong`, `*-fg`, `*-fg-muted`, `*-fg-faint`): títulos y valores; cuerpo; descripciones y etiquetas; migas, contadores y ayudas.

### Semánticos
- **ok**, **danger**, **info**, **warn**, **epic**: estado y tipo de issue. Se usan como texto o ícono a tono pleno y como fondo al 12–15 % (Badge, TypeIcon, etiqueta de épica). Historia = ok, Tarea = info, Bug = danger, Épica = epic. Prioridad: máxima danger, alta warn, media muted, baja info, mínima faint.
- **Azul Pacífico** (`info-dark`, `#8fb4ff`): además, el color con el que se muestra un avatar guardado con el color del sol.

### Named Rules
**The Un Solo Sol Rule.** El acento es de la acción primaria, la selección, el foco y la presencia de agentes (WorkingLine, chispa del agente, badges de automatización). Nada más lo lleva: ni decoración, ni encabezados, ni enlaces, ni avatares.

**The Sol De Cada Marca Rule.** El acento es el color base del espacio de trabajo: el sol #ffb81c en Kuador, el rojo o verde de otra empresa. `src/theme/brand.ts` deriva de ese único color la escala 50–950 del preset de PrimeReact, el texto encima del relleno (negro o blanco, el que contraste más) y la versión «como texto» con contraste AA en cada tema (`--brand`, `--brand-on`, `--brand-text-light`, `--brand-text-dark`). Ningún componente escribe el hex del sol directamente: todos leen `sol` / `sol-text` / `on-sol`.

**The Enlace Sin Sol Rule.** Un enlace dentro de texto se distingue por el subrayado, no por el color: `.k-link` usa el texto fuerte con subrayado en `line-hover` desplazado 2 px, que pasa a `currentColor` al pasar el puntero.

**The Avatar Sin Sol Rule.** Ningún avatar se pinta con el sol. Un color guardado `#ffb81c` se remapea a `#8fb4ff` al mostrarse, sin tocar el dato del miembro.

**The Par Claro/Oscuro Rule.** Todo color existe en `:root` (claro) y en `.app-dark` (oscuro), y en el preset como `light-dark(claro, oscuro)`. Un color con un solo valor no entra al sistema.

## Typography

**Display Font:** no hay; el título de página es la cumbre de la jerarquía.
**Body Font:** Archivo (con ui-sans-serif, system-ui, sans-serif), cargada con `next/font` como `--font-archivo`.
**Label/Mono Font:** Martian Mono (con ui-monospace, SFMono-Regular, Menlo, monospace), cargada como `--font-martian`.

**Character:** Archivo es una grotesca de trabajo, firme y compacta, que sostiene la densidad sin parecer genérica; Martian Mono aporta el registro técnico solo donde un dato debe leerse como dato.

### Hierarchy
- **Headline** (600, 20 px, tracking -0.025em, `text-balance`): título de página en PageHeader.
- **Title** (600, 14 px): título de Section; 500–600 a 12 px para el nombre de columna; 500 a 14 px para el título de SidePanel; 500 a 16 px para el título de Dialog.
- **Body** (400, 14 px): tamaño base del `body` y del contenido de diálogos.
- **Body compact** (400–500, 13 px): navegación, pestañas, título de tarjeta, descripciones de página y sección (`leading-relaxed`, máx. `max-w-3xl`).
- **Label** (500, 12 px, `fg-muted`): etiqueta de Field; ayudas y errores también a 12 px.
- **Caption** (500, 11 px): badges, etiquetas de issue, encabezados de grupo de la barra lateral, WorkingLine.
- **Mono key** (400, 10–11 px, números tabulares): claves (KUA-12), contadores de columna, story points, contadores de pestaña.
- **Prose** (400, 15 px, interlineado 1.7): documentos Markdown (`.doc-prose`); títulos internos a 500 con tracking -0.01em, h1 1.6 rem, h2 1.25 rem con línea inferior. La variante de comentarios (`.doc-prose-compact`) baja a 13 px / 1.55.

### Named Rules
**The Mono Es Dato Rule.** Martian Mono solo para claves, rutas y cifras, siempre con `.tabular`. Nunca para títulos, etiquetas ni texto corrido.

**The Sin Rótulo Rule.** Ningún texto pequeño en mayúsculas se coloca sobre un título. La jerarquía la hacen el tamaño y el peso.

## Layout

Armazón de dos zonas: barra lateral fija de 240 px (`sidebar`) sobre fondo hundido, y la zona principal con su propio scroll. No hay barra superior global: las migas viven en la cabecera de cada página. Por debajo de `lg` (1024 px) la barra lateral se oculta y una barra mínima de 48 px con el botón de menú, el punto de sol y el nombre del espacio abre la misma navegación como panel izquierdo de 256 px.

Las pantallas de trabajo (tablero, listas, tablas, catálogo, actividad) usan todo el ancho disponible, con 24 px de relleno lateral. Solo una página de un único formulario (Mi cuenta) usa `narrow` (`max-w-3xl`). La cabecera lleva migas (12 px, `fg-faint`), título, descripción y acciones al borde derecho, y puede alojar una fila inferior de pestañas o filtros; termina en una línea de 1 px. Las configuraciones con varias secciones (proyecto) usan un menú lateral de 13 rem y una sección a la vez, con un ancho de lectura de 56 rem por sección.

El tablero es una fila horizontal de columnas de 288 px (85 vw en móvil) con `snap-start`; dentro, tarjetas separadas 6 px. Ritmo: 6 / 8 / 12 / 20 / 24 px. Section usa 20 px horizontal y 16 px vertical; las tarjetas 12 × 10 px.

### Named Rules
**The Mismo Cuadro Rule.** El título de la página y su contenido comparten el mismo ancho y el mismo relleno (`px-6`, ancho completo; `max-w-3xl px-6` en `narrow`): bordes izquierdo y derecho del título, las acciones y el contenido coinciden siempre.

## Elevation & Depth

El sistema es plano por capas tonales. En oscuro, la profundidad va de hundido (`#070a0f`) a página, superficie, elevado y hover; las líneas de 1 px separan. En claro, la página es gris papel y superficies y tarjetas son blancas. La sombra existe en un solo token, `--k-shadow` (utilidad `shadow-float`), y solo en lo que flota: la tarjeta mientras se arrastra (con un giro de 1.5°), la tarjeta de inicio de sesión y las capas de PrimeReact (menús, desplegables, panel, diálogo). Los campos de formulario no llevan sombra. El fondo detrás de paneles y diálogos se oscurece con la máscara del preset (35 % en claro, 70 % en oscuro).

### Shadow Vocabulary
- **Flotante oscuro** (`box-shadow: 0 2px 6px rgb(0 0 0 / 0.35), 0 12px 32px rgb(0 0 0 / 0.35)`): capas flotantes en `.app-dark`.
- **Flotante claro** (`box-shadow: 0 1px 2px rgb(11 16 23 / 0.06), 0 4px 12px rgb(11 16 23 / 0.06)`): capas flotantes en claro.

### Named Rules
**The Solo Flota Rule.** Una superficie en reposo no tiene sombra. La sombra significa «esto está por encima»: arrastre, menú, panel, diálogo.

## Shapes

Esquinas contenidas y bordes de 1 px. Tres radios: 4 px para piezas pequeñas (badges, etiquetas, TypeIcon, `code`), 6 px para controles y tarjetas (botones, campos, tarjetas de issue, elementos de navegación, ChoiceCards) y 8 px para contenedores (Section, columnas, EmptyState, bloques `pre`). Los estados vacíos usan borde discontinuo (`line-strong`); el avatar sin asignar, un círculo discontinuo.

La forma también comunica identidad: las personas son círculos, los agentes cuadrados redondeados (radio 28 %) con una chispa de sol en la esquina superior derecha. Los puntos de categoría (8 px) y de presencia (6 px) son círculos.

### Named Rules
**The Forma Es Identidad Rule.** Círculo = persona, cuadrado redondeado con chispa = agente. Ninguna otra pieza usa el radio del 28 %.

## Components

### Buttons
Sobrios y de herramienta; el color lo pone solo el primario.
- **Shape:** esquinas suaves (6 px).
- **Primary:** relleno de sol con texto `on-sol`; una por vista («Crear», «Guardar»). Hover hacia 600 en claro y 400 en oscuro.
- **Secondary:** contorno neutro (variante `outlined`, severidad `secondary`); el tono por defecto.
- **Ghost:** sin borde ni fondo hasta el hover; acciones de barra o fila y «Cancelar».
- **Danger:** contorno en `danger`; la acción de confirmación en ConfirmDialog.
- **Tamaños:** `md` y `sm`. Ícono de 16 px a la izquierda; `iconOnly` exige `label` (aria-label y título nativo). En carga, el ícono se reemplaza por un spinner y el botón queda deshabilitado con `aria-busy`.
- **Botón ícono propio:** 32 px cuadrado, `fg-muted`, hover `bg-hover` + `fg-strong` (cerrar panel, tema, salir, menú móvil).

### Chips / Badges
- **Badge:** 11 px, 500, radio 4 px, relleno 1 × 6 px; tonos neutral, sol, ok, danger, info, warn, epic con fondo al 12 % (sol al 15 %) y texto a tono pleno. El tono sol se usa solo para automatización y estado de agentes («Trabajando…»).
- **Etiqueta de issue:** `bg-hover` y `fg-muted`; la épica lleva `epic` al 12 %.

### Cards / Containers
- **Section:** radio 8 px, borde `line`, fondo superficie; cabecera con título de 14 px, descripción opcional y acciones, separada por una línea. Un solo nivel: nunca una Section dentro de otra. `flush` quita el relleno para tablas y listas.
- **EmptyState:** borde discontinuo, título 14 px y texto que explica qué va aquí y cómo empezar, con acción opcional.
- **Tarjeta de issue:** radio 6 px, fondo elevado, borde `line-strong` (hover `line-hover`). Título 13 px; épica y etiquetas; WorkingLine si un agente la trabaja; pie con TypeIcon, clave en mono, prioridad, story points en píldora mono y avatar xs. Al arrastrar: borde de sol, giro de 1.5° y sombra flotante.
- **Columna:** 288 px, fondo hundido, radio 8 px; cabecera de 40 px con punto de categoría, nombre de 12 px, contador mono y «+»; el borde se vuelve sol al 70 % cuando recibe una tarjeta.

### Inputs / Fields
- **Field:** etiqueta 12 px 500 `fg-muted` arriba, control, y debajo ayuda (`fg-faint`) o error (`danger`, `role="alert"`), separados 6 px.
- **Style:** borde de 1 px (`surface.300` claro / `surface.600` oscuro), radio 6 px, relleno 6 × 10 px, fondo blanco en claro y página nocturna en oscuro, sin sombra. Hover oscurece el borde.
- **Focus:** anillo de sol de 2 px desplazado 2 px; cursor de texto en sol.
- **SelectInput:** opciones planas con contenido a la izquierda (avatar, punto, ícono) y check de 14 px en la elegida; filtro opcional «Buscar…».
- **Switch:** interruptor de PrimeReact con etiqueta de 14 px a la derecha.
- **ChoiceCards:** grupo de radios para pocas opciones que necesitan explicación; borde `line-strong`, elegido con borde de sol y fondo sol al 8 %, punto de radio propio.

### Navigation
- **Barra lateral:** cabecera de 56 px con punto de sol de 10 px, nombre del espacio y «Kuador Factory»; grupos «Proyectos» (con «+») y «Espacio de trabajo» con encabezado de 11 px `fg-faint`. Ítems de 32 px, radio 6 px, 13 px `fg-muted`; los proyectos llevan su clave en mono de 10 px. Activo: fondo `selected`, 500 `fg-strong` y contorno de 1 px en `line`. Pie con persona, ThemeToggle y salir.
- **Pestañas de proyecto (entre páginas):** Tablero · Documentos · Repositorio · Actividad · Configuración; 13 px, separación 20 px, subrayado de 2 px en sol para la activa.
- **Tabs (dentro de una vista):** pestañas de PrimeReact a 13 px 500, activa en `fg-strong`, contador mono opcional de 11 px.
- **Migas:** 12 px `fg-faint`, separadas por chevron de 12 px, enlaces a `fg-strong` al pasar el puntero.

### Overlays
- **SidePanel:** panel derecho (ancho `wide` 960 px o `narrow` 480 px, nunca más que la pantalla) con cabecera de título 14 px, acciones extra y cerrar; contenido con scroll propio y pie opcional. Deja ver la vista detrás y se cierra con Escape. Es donde se abren el detalle y la creación.
- **Dialog / ConfirmDialog:** centrado, máx. 480 px, título 16 px. ConfirmDialog: «Cancelar» ghost + acción danger.
- **DropdownMenu:** menú de acciones (⋯, usuario), mínimo 192 px, ítems con ícono de 16 px; los destructivos en `danger`.
- **Tooltip:** 12 px, máx. 320 px; ayuda breve, nunca sustituye a una etiqueta visible.
- **Toaster:** una sola región abajo a la derecha; avisos de éxito, error, advertencia e información con ícono de 16 px.

### Presencia de agentes (Signature Component)
- **Avatar:** tamaños xs 20, sm 24, md 32, lg 40 px; iniciales en 600 sobre el color del miembro con texto `on-sol`. Persona: círculo. Agente: cuadrado redondeado (28 %) con chispa de sol de 10 px sobre un disco del color de la página. Sin asignar: círculo discontinuo.
- **WorkingLine:** «Agente X trabajando…» en 11 px `sol-text` con un punto de sol de 6 px que pulsa (estático con `prefers-reduced-motion`), `role="status"`. Aparece en la tarjeta y en la cabecera del detalle.
- **Automatización:** badge en tono sol o texto `sol-text` con chispa («Automatizado», «Trabajando…»), y avisos de agente con fondo sol al 8–10 %.

### Tema
- **ThemeToggle:** botón ícono (sol / luna) que alterna la clase `.app-dark` en `<html>` al instante y guarda la elección en la cookie `kuador_theme`, que el servidor lee para pintar el primer render sin destello. Sin cookie, oscuro.

## Do's and Don'ts

### Do:
- **Do** usar el sol solo para la acción primaria, la selección, el foco y la presencia de agentes (WorkingLine, chispa del agente, badges de automatización).
- **Do** marcar los enlaces de texto con `.k-link`: texto fuerte y subrayado, nunca sol.
- **Do** dibujar a las personas como círculos y a los agentes como cuadrados redondeados con chispa.
- **Do** dejar que listas y tablas ocupen todo el ancho, con cabecera y contenido en el mismo cuadro `px-6`.
- **Do** abrir el detalle y la creación en un SidePanel derecho; reservar ConfirmDialog para confirmar acciones destructivas.
- **Do** definir cada color nuevo en los dos temas (`:root` y `.app-dark`, o `light-dark()` en el preset); oscuro por defecto, claro por cookie `kuador_theme`.
- **Do** escribir claves, rutas y cifras en Martian Mono con números tabulares.
- **Do** expresar la profundidad con capas tonales y líneas de 1 px, y la sombra solo en lo que flota.

### Don't:
- **Don't** pintar enlaces, encabezados, iconos decorativos ni avatares con el sol; un avatar guardado `#ffb81c` se muestra `#8fb4ff`.
- **Don't** poner más de un botón primario por vista.
- **Don't** usar un diálogo centrado para ver o editar un issue.
- **Don't** anidar Section dentro de Section ni tarjetas dentro de tarjetas.
- **Don't** usar brillos, degradados ni rótulos en mayúsculas sobre los títulos.
- **Don't** usar Martian Mono para títulos o texto corrido.
- **Don't** añadir sombra a superficies en reposo ni a campos de formulario.
