-- Ahorro de tokens:
-- 1. Caché de prompts: cada ejecución guarda cuántos tokens leyó del caché (cuestan ~10%) y cuántos guardó en él.
-- 2. Esfuerzo por agente (low…max), junto al modelo.
-- 3. Ejecutar código solo para los roles que lo usan: la herramienta «CodeExecution» del rol. Marketing y el Analista la
--    reciben; los roles propios de cada espacio también, para que sigan trabajando igual (quien administra la puede quitar).
-- 4. Modelo y esfuerzo sugeridos por rol en las reglas de agentes de la plataforma.

alter table agent_run add column cache_read_tokens integer, add column cache_write_tokens integer;
alter table agent_profile add column effort varchar(10) check (effort in ('low', 'medium', 'high', 'xhigh', 'max'));

-- Copias de los espacios: Marketing y el Analista de fábrica (aunque el equipo los haya cambiado) y los roles propios.
update catalog_entry
set payload = jsonb_set(payload::jsonb, '{tools}', coalesce(payload::jsonb -> 'tools', '[]'::jsonb) || '["CodeExecution"]'::jsonb)::text,
    version = version + 1, updated_at = now()
where type = 'AGENT_ROLE'
  and (entry_key in ('MARKETING', 'ANALYST') or not built_in)
  and not jsonb_exists(coalesce(payload::jsonb -> 'tools', '[]'::jsonb), 'CodeExecution')
  and not jsonb_exists_any(coalesce(payload::jsonb -> 'tools', '[]'::jsonb), array['Edit', 'Write']);

-- El catálogo de la plataforma: los dos roles y la sugerencia por rol en las reglas de agentes.
update platform_catalog_entry
set payload = jsonb_set(payload::jsonb, '{tools}', coalesce(payload::jsonb -> 'tools', '[]'::jsonb) || '["CodeExecution"]'::jsonb)::text,
    published_payload = case when published_payload is null then null
        else jsonb_set(published_payload::jsonb, '{tools}', coalesce(published_payload::jsonb -> 'tools', '[]'::jsonb) || '["CodeExecution"]'::jsonb)::text end,
    updated_at = now()
where type = 'AGENT_ROLE' and entry_key in ('MARKETING', 'ANALYST')
  and not jsonb_exists(coalesce(payload::jsonb -> 'tools', '[]'::jsonb), 'CodeExecution');

update platform_catalog_entry
set payload = jsonb_set(payload::jsonb, '{roles}', r.roles)::text,
    published_payload = case when published_payload is null then null else jsonb_set(published_payload::jsonb, '{roles}', r.roles)::text end,
    updated_at = now()
from (select '{"PRODUCT_OWNER":{"model":"claude-sonnet-5-5","effort":"medium"},"ARCHITECT":{"model":"claude-opus-5-5","effort":"high"},"QA":{"model":"claude-sonnet-5-5","effort":"medium"},"RELEASE":{"model":"claude-sonnet-5-5","effort":"low"},"MARKETING":{"model":"claude-sonnet-5-5","effort":"medium"},"ANALYST":{"model":"claude-sonnet-5-5","effort":"medium"}}'::jsonb as roles) r
where type = 'AGENT_POLICY' and entry_key = 'DEFAULT' and payload::jsonb -> 'roles' is null;

insert into platform_catalog_release (version, added, updated, published_at)
select coalesce(max(version), 0) + 1, '[]',
       '[{"type":"AGENT_ROLE","key":"MARKETING"},{"type":"AGENT_ROLE","key":"ANALYST"},{"type":"AGENT_POLICY","key":"DEFAULT"}]', now()
from platform_catalog_release
having exists (select 1 from platform_catalog_entry);
