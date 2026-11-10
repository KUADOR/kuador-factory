-- Fase 2: conexión de Claude Code (token RUNNER de una persona) y quién supervisa el trabajo de los agentes.
alter table access_token drop constraint access_token_kind_check;
alter table access_token add constraint access_token_kind_check check (kind in ('SESSION', 'AGENT', 'RUNNER'));

-- La persona que puso a los agentes a trabajar en el issue. Solo sus agentes (en su Claude Code, con su
-- suscripción) lo atienden; se hereda por toda la cadena de agentes.
alter table issue add column supervisor_id uuid references member (id) on delete set null;
create index issue_supervisor_idx on issue (workspace_id, supervisor_id) where supervisor_id is not null;
