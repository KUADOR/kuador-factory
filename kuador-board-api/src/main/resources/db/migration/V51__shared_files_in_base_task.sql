-- Menos conflictos entre PR hermanos: en las épicas las tareas en paralelo chocaban en los mismos archivos centrales
-- (enums de errores o auditoría, rutas, tipos de la API). La regla «epicPlan» pide ponerlos en una tarea base o
-- encadenar esas tareas. Se actualiza la plataforma y las copias de los espacios con el texto de fábrica (las
-- personalizadas no se tocan).

create temporary table epic_rule (old text, txt text) on commit drop;
insert into epic_rule values ($txt$Esta tarjeta es una épica. Si te toca el plan, termínalo creando las tareas con create_tasks (título, rol que la trabaja, qué hacer y de qué tareas depende) antes de entregar. Contrato primero: si hay una API entre partes (backend y frontend, o servicios), la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml: rutas, request, response, errores y ejemplos) y las demás dependen solo de ella, no entre sí; el frontend trabaja contra un mock del contrato y una tarea corta final integra. Divide solo si conviene: un cambio pequeño (p. ej. un CRUD simple) va en una sola tarea vertical. Según el tablero, el reparto lo aprueba una persona o arranca solo. Si te devolvieron el reparto con cambios, vuelve a llamar a create_tasks: reemplaza las tareas que no arrancaron.$txt$, $txt$Esta tarjeta es una épica. Si te toca el plan, termínalo creando las tareas con create_tasks (título, rol que la trabaja, qué hacer y de qué tareas depende) antes de entregar. Contrato primero: si hay una API entre partes (backend y frontend, o servicios), la tarea 1 define el contrato (OpenAPI en contracts/<tema>.openapi.yaml: rutas, request, response, errores y ejemplos) y las demás dependen solo de ella, no entre sí; el frontend trabaja contra un mock del contrato y una tarea corta final integra. Divide solo si conviene: un cambio pequeño (p. ej. un CRUD simple) va en una sola tarea vertical. Según el tablero, el reparto lo aprueba una persona o arranca solo. Si te devolvieron el reparto con cambios, vuelve a llamar a create_tasks: reemplaza las tareas que no arrancaron. Archivos compartidos: si varias tareas tocarían los mismos archivos centrales (listas o enums compartidos como códigos de error o acciones de auditoría, rutas y navegación, tipos de la API, configuración, README), esos cambios van en una tarea base (la del contrato, si hay) y las demás solo los usan; si no se puede, encadena esas tareas con dependencia en vez de dejarlas en paralelo. Así sus PR no chocan al fusionarse.$txt$);

update catalog_entry
set payload = jsonb_set(payload::jsonb, '{rules,epicPlan}', to_jsonb(n.txt))::text, version = version + 1, updated_at = now()
from epic_rule n
where type = 'AGENT_POLICY' and payload::jsonb #>> '{rules,epicPlan}' = n.old;

update platform_catalog_entry
set payload = case when payload::jsonb #>> '{rules,epicPlan}' = n.old
        then jsonb_set(payload::jsonb, '{rules,epicPlan}', to_jsonb(n.txt))::text else payload end,
    published_payload = case when published_payload is not null and published_payload::jsonb #>> '{rules,epicPlan}' = n.old
        then jsonb_set(published_payload::jsonb, '{rules,epicPlan}', to_jsonb(n.txt))::text else published_payload end,
    updated_at = now()
from epic_rule n
where type = 'AGENT_POLICY' and entry_key = 'DEFAULT';

insert into platform_catalog_release (version, added, updated, published_at)
select coalesce(max(version), 0) + 1, '[]', '[{"type":"AGENT_POLICY","key":"DEFAULT"}]', now()
from platform_catalog_release
having exists (select 1 from platform_catalog_entry where type = 'AGENT_POLICY' and entry_key = 'DEFAULT');
