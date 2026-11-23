-- Reparto de una tarjeta grande en tableros sin repositorio de código: la regla «workSplit» de las reglas de agentes.
-- Es un texto nuevo: se agrega donde falta (plataforma y copias de los espacios) sin tocar nada de lo que cada equipo
-- editó.

create temporary table work_split_rule (txt text) on commit drop;
insert into work_split_rule values ($txt$Esta tarjeta agrupa un trabajo grande. Si te toca planearlo, termina creando las tareas con create_tasks antes de entregar: tareas chicas y verificables, cada una con el rol del equipo que la trabaja, qué hay que hacer, cómo se sabe que está terminada y de qué otras tareas depende. Una persona aprueba el reparto antes de que arranquen. No repartas lo que una sola persona o un solo agente resuelve de una vez.$txt$);

update catalog_entry
set payload = jsonb_set(payload::jsonb, '{rules,workSplit}', to_jsonb(n.txt))::text
from work_split_rule n
where type = 'AGENT_POLICY' and payload::jsonb -> 'rules' is not null and payload::jsonb #>> '{rules,workSplit}' is null;

update platform_catalog_entry
set payload = case when payload::jsonb -> 'rules' is not null and payload::jsonb #>> '{rules,workSplit}' is null
        then jsonb_set(payload::jsonb, '{rules,workSplit}', to_jsonb(n.txt))::text else payload end,
    published_payload = case when published_payload is not null and published_payload::jsonb -> 'rules' is not null
            and published_payload::jsonb #>> '{rules,workSplit}' is null
        then jsonb_set(published_payload::jsonb, '{rules,workSplit}', to_jsonb(n.txt))::text else published_payload end
from work_split_rule n
where type = 'AGENT_POLICY';
