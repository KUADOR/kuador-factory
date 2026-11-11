-- Tareas de una épica creadas desde el plan: el rol que las trabaja y de qué otras tareas dependen.
alter table issue add column work_role varchar(40);
alter table issue add column depends_on text[] not null default '{}';
create index issue_parent_idx on issue (workspace_id, parent_key) where parent_key is not null;
