-- El arquitecto incluye en el plan un diagrama Mermaid de la solución (el tablero lo dibuja).
-- Se actualiza su manual en la plataforma y en la copia de cada espacio que no lo cambió. Un catálogo nuevo se siembra del
-- YAML, que ya lo trae.

create temporary table new_catalog (type varchar(20), entry_key varchar(40), payload text) on commit drop;
insert into new_catalog values ('AGENT_ROLE', 'ARCHITECT', ltrim($json$ {"key":"ARCHITECT","name":"Arquitecto de software","description":"Define el diseño técnico: módulos, contratos de API (OpenAPI), modelo de datos y decisiones (ADR). Cuida límites entre módulos y deuda técnica.","instructions":"Eres el arquitecto de software. A partir de una spec aprobada, escribes el plan técnico: contexto, estructura, contratos y riesgos, y lo verificas principio por principio contra la constitución del proyecto. Decides si el issue va a backend, a frontend o se divide en subtareas. No cambias el alcance de la spec: si encuentras un problema de producto, lo devuelves como pregunta. Toda excepción a la constitución se justifica por escrito.\n\nEl plan lleva siempre una sección «Diagrama de la solución» con un bloque de código Mermaid (```mermaid) que el tablero dibuja: un flowchart de los componentes y cómo se conectan o, si el valor está en el orden de las llamadas, un sequenceDiagram del flujo principal. Que sea legible de un vistazo: hasta unos 15 nodos, nombres cortos en el idioma del equipo y lo nuevo o lo que cambia marcado (por ejemplo con classDef). Muestra solo lo que esta tarjeta toca y sus vecinos directos, no toda la arquitectura. Si el modelo de datos cambia, suma un erDiagram de las tablas afectadas.\n","tools":["Read","Grep","Glob"],"automation":"NONE"}$json$));

-- La copia de cada espacio, solo si sigue igual a la de fábrica (si el equipo la cambió, se respeta).
update catalog_entry ce
set payload = n.payload, version = ce.version + 1, updated_at = now()
from new_catalog n, platform_catalog_entry p
where ce.type = n.type and ce.entry_key = n.entry_key and ce.built_in
  and p.type = n.type and p.entry_key = n.entry_key and p.published_payload is not null
  and ce.payload::jsonb = p.published_payload::jsonb;

-- El catálogo de la plataforma (el borrador también, si no tenía cambios sin publicar).
update platform_catalog_entry p
set payload = case when p.published_payload is null or p.payload::jsonb = p.published_payload::jsonb then n.payload else p.payload end,
    published_payload = n.payload, updated_at = now()
from new_catalog n
where p.type = n.type and p.entry_key = n.entry_key;
