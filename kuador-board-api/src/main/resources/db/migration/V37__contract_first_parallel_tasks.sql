-- Trabajo en paralelo: el reparto de una épica empieza por el contrato de la API y las tareas dependen solo de él (el
-- frontend contra un mock), y un cambio pequeño va en una sola tarea vertical. Se actualizan las reglas «epicPlan» y
-- «taskSplit» de la plataforma y las copias de los espacios que todavía tienen el texto de fábrica (las personalizadas no
-- se tocan). Va junto con el cambio de código: una dependencia libera a las demás al pasar su revisión, no al terminar.

update catalog_entry
set payload = jsonb_set(payload::jsonb, '{rules,epicPlan}', to_jsonb(n.txt))::text, version = version + 1, updated_at = now()
from (select $txt$Esta tarjeta es una épica. Si te toca el plan, termínalo creando las tareas con create_tasks (título, rol que la trabaja, qué hacer y de qué tareas depende) antes de entregar. Contrato primero: si hay una API entre partes (backend y frontend, o servicios), la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml: rutas, request, response, errores y ejemplos) y las demás dependen solo de ella, no entre sí; el frontend trabaja contra un mock del contrato y una tarea corta final integra. Divide solo si conviene: un cambio pequeño (p. ej. un CRUD simple) va en una sola tarea vertical. Según el tablero, el reparto lo aprueba una persona o arranca solo. Si te devolvieron el reparto con cambios, vuelve a llamar a create_tasks: reemplaza las tareas que no arrancaron.$txt$ as txt, $txt$Esta tarjeta es una épica. Si te toca el plan, termínalo creando una tarjeta por cada tarea con create_tasks (título, rol que la trabaja, qué hacer y de qué tareas depende) antes de entregar. Una persona revisa el reparto y, al aprobarlo, cada tarea la toma el agente de su rol. Si te devolvieron el reparto con cambios, vuelve a llamar a create_tasks: reemplaza las tareas que no arrancaron.$txt$ as old) n
where type = 'AGENT_POLICY' and payload::jsonb #>> '{rules,epicPlan}' = n.old;

update catalog_entry
set payload = jsonb_set(payload::jsonb, '{rules,taskSplit}', to_jsonb(n.txt))::text, version = version + 1, updated_at = now()
from (select $txt$Es una épica y después de esta etapa se trabaja: reparte el plan en tareas chicas y verificables, cada una de un solo rol, en su propia rama y PR. Contrato primero: si hay una API entre partes, la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml) y las demás dependen solo de ella para avanzar en paralelo (el frontend contra un mock del contrato); al final, una tarea corta de integración. En dependsOn van solo las dependencias reales (números desde 1): una tarea queda libre cuando su dependencia pasa su revisión. Si el cambio es pequeño, una sola tarea vertical es mejor que repartirlo.$txt$ as txt, $txt$Es una épica y después de esta etapa se trabaja: reparte el plan en tareas, una por cada tarea del plan, en orden. Cada tarea la trabaja un solo rol en su propia rama y PR; que sea chica y verificable. En dependsOn van los números (desde 1) de las tareas que deben terminar antes. Una persona revisa el reparto y, al aprobarlo, arrancan.$txt$ as old) n
where type = 'AGENT_POLICY' and payload::jsonb #>> '{rules,taskSplit}' = n.old;

update platform_catalog_entry
set payload = jsonb_set(jsonb_set(payload::jsonb, '{rules,epicPlan}', to_jsonb(n.epic)), '{rules,taskSplit}', to_jsonb(n.split))::text,
    published_payload = case when published_payload is null then null
        else jsonb_set(jsonb_set(published_payload::jsonb, '{rules,epicPlan}', to_jsonb(n.epic)), '{rules,taskSplit}', to_jsonb(n.split))::text end,
    updated_at = now()
from (select $txt$Esta tarjeta es una épica. Si te toca el plan, termínalo creando las tareas con create_tasks (título, rol que la trabaja, qué hacer y de qué tareas depende) antes de entregar. Contrato primero: si hay una API entre partes (backend y frontend, o servicios), la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml: rutas, request, response, errores y ejemplos) y las demás dependen solo de ella, no entre sí; el frontend trabaja contra un mock del contrato y una tarea corta final integra. Divide solo si conviene: un cambio pequeño (p. ej. un CRUD simple) va en una sola tarea vertical. Según el tablero, el reparto lo aprueba una persona o arranca solo. Si te devolvieron el reparto con cambios, vuelve a llamar a create_tasks: reemplaza las tareas que no arrancaron.$txt$ as epic, $txt$Es una épica y después de esta etapa se trabaja: reparte el plan en tareas chicas y verificables, cada una de un solo rol, en su propia rama y PR. Contrato primero: si hay una API entre partes, la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml) y las demás dependen solo de ella para avanzar en paralelo (el frontend contra un mock del contrato); al final, una tarea corta de integración. En dependsOn van solo las dependencias reales (números desde 1): una tarea queda libre cuando su dependencia pasa su revisión. Si el cambio es pequeño, una sola tarea vertical es mejor que repartirlo.$txt$ as split) n
where type = 'AGENT_POLICY' and entry_key = 'DEFAULT';

insert into platform_catalog_release (version, added, updated, published_at)
select coalesce(max(version), 0) + 1, '[]', '[{"type":"AGENT_POLICY","key":"DEFAULT"}]', now()
from platform_catalog_release
having exists (select 1 from platform_catalog_entry);
